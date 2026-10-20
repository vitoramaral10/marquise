package br.dev.vitormelo.marquise;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The first run, on the TV, with the remote: the Jellyfin server (found on the network or typed), signing in (Quick
 * Connect: a code approved on the phone; or a user name and password), the city, and switching Marquise on. Also opened
 * from the settings to change the server or the city. Standard views here (unlike the hand-drawn home screen) so typing
 * brings up the TV's own keyboard.
 */
public class SetupActivity extends Activity {
    static final String EXTRA_STEP = "step";
    static final String STEP_SERVER = "server", STEP_CITY = "city";
    private static final int WELCOME = 0, SERVER = 1, MANUAL = 2, LOGIN = 3, PASSWORD = 4, CITY = 5, DONE = 6;
    private static final int VELVET_TOP = 0xFF7A1830, VELVET_BOTTOM = 0xFF280E2E, CREAM = 0xFFFFF4E2, INK = 0xFF1C1C1E;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private LinearLayout panel;
    private float k;
    private int step = WELCOME;
    private volatile int token;          // token: bumped on every screen change, so late answers are dropped
    private boolean single;              // opened for one step from the settings: back there when it's done
    private String server = "", serverName = "", qcSecret;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {VELVET_TOP, VELVET_BOTTOM}));

        // On the left, as on the settings screen: the mark and the name.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        left.addView(logo, new LinearLayout.LayoutParams(px(260), px(260)));
        TextView name = text("Marquise", 56, Ui.displaySemibold, CREAM);
        name.setPadding(0, px(36), 0, 0);
        left.addView(name);
        left.addView(text(Ui.tr("Configuração", "Setup"), 26, Ui.regular, 0xB3FFF4E2));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(1000), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL);
        root.addView(left, lp);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(px(824), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL);
        pp.leftMargin = px(1000);
        root.addView(panel, pp);
        setContentView(root);

        String start = getIntent().getStringExtra(EXTRA_STEP);
        single = start != null;
        if (STEP_SERVER.equals(start)) showServer();
        else if (STEP_CITY.equals(start)) showCity();
        else showWelcome();
    }

    @Override protected void onResume() {
        super.onResume();
        if (step == DONE) showDone();   // back from the TV's settings: maybe debugging is on now
    }

    @Override protected void onDestroy() {
        token++;
        io.shutdownNow();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) {
            switch (step) {
                case MANUAL: showServer(); return true;
                case LOGIN: case PASSWORD: if (!single) { showServer(); return true; } break;
                case CITY: if (!single && Prefs.jellyfinReady(this)) { showLogin(); return true; } break;
                default: break;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    // MARK: - Steps

    private void showWelcome() {
        begin(WELCOME);
        title(Ui.tr("Vamos deixar sua TV pronta", "Let's get your TV ready"));
        body(Ui.tr("Três passos: seu servidor Jellyfin, sua conta e sua cidade. Leva um minuto.",
                "Three steps: your Jellyfin server, your account and your city. It takes a minute."));
        button(Ui.tr("Começar", "Start"), this::showServer).requestFocus();
    }

    private void showServer() {
        begin(SERVER);
        title(Ui.tr("Seu servidor Jellyfin", "Your Jellyfin server"));
        final TextView status = body(Ui.tr("Procurando na sua rede…", "Looking on your network…"));
        final LinearLayout found = new LinearLayout(this);
        found.setOrientation(LinearLayout.VERTICAL);
        panel.addView(found);
        View manual = button(Ui.tr("Digitar o endereço", "Type the address"), this::showManual);
        manual.requestFocus();
        final int t = token;
        io.execute(() -> {
            Jellyfin.discover(s -> main.post(() -> {
                if (t != token) return;
                String label = (s.name.isEmpty() ? "Jellyfin" : s.name) + " · " + s.url.replaceFirst("^[a-zA-Z]+://", "");
                View b = button(found, label, () -> choose(s.url));
                if (found.getChildCount() == 1) b.requestFocus();
            }));
            main.post(() -> {
                if (t != token) return;
                status.setText(found.getChildCount() == 0
                        ? Ui.tr("Nenhum servidor apareceu. Digite o endereço dele.", "No server showed up. Type its address.")
                        : Ui.tr("Escolha o seu servidor:", "Pick your server:"));
            });
        });
    }

    private void showManual() {
        begin(MANUAL);
        title(Ui.tr("Endereço do servidor", "Server address"));
        final TextView status = body(Ui.tr("O endereço que você usa para abrir o Jellyfin, como 192.168.1.10:8096.",
                "The address you open Jellyfin at, like 192.168.1.10:8096."));
        final EditText input = input("http://192.168.1.10:8096", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        Runnable go = () -> {
            String url = Jellyfin.normalize(input.getText().toString());
            if (url.isEmpty()) return;
            status.setText(Ui.tr("Conferindo…", "Checking…"));
            choose(url, status);
        };
        onDone(input, go);
        button(Ui.tr("Continuar", "Continue"), go);
        input.requestFocus();
    }

    private void choose(String url) {
        choose(url, null);
    }

    private void choose(String url, TextView status) {
        final int t = token;
        io.execute(() -> {
            try {
                Jellyfin.Server s = Jellyfin.info(url);
                main.post(() -> {
                    if (t != token) return;
                    server = s.url;
                    serverName = s.name;
                    showLogin();
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (t != token || status == null) return;
                    status.setText(Ui.tr("Não encontrei um Jellyfin nesse endereço.", "No Jellyfin at that address."));
                });
            }
        });
    }

    private void showLogin() {
        begin(LOGIN);
        title(Ui.tr("Entre na sua conta", "Sign in"));
        final TextView status = body(Ui.tr("Preparando o código…", "Getting a code…"));
        final TextView code = text("", 96, Ui.displaySemibold, CREAM);
        code.setLetterSpacing(0.12f);
        panel.addView(code);
        button(Ui.tr("Entrar com usuário e senha", "Use a user name and password"), this::showPassword).requestFocus();
        final int t = token;
        final String deviceId = Prefs.deviceId(this);
        io.execute(() -> {
            if (!Jellyfin.quickConnectEnabled(server, deviceId)) {
                main.post(() -> { if (t == token) showPassword(); });
                return;
            }
            try {
                String[] qc = Jellyfin.quickConnectStart(server, deviceId);
                qcSecret = qc[0];
                main.post(() -> {
                    if (t != token) return;
                    code.setText(qc[1].length() == 6 ? qc[1].substring(0, 3) + " " + qc[1].substring(3) : qc[1]);
                    status.setText(Ui.tr("No celular, abra o Jellyfin → seu perfil → Quick Connect e digite este código:",
                            "On your phone, open Jellyfin → your profile → Quick Connect and enter this code:"));
                });
                // Waits for the approval (up to ten minutes), then signs in.
                for (int i = 0; i < 200 && t == token; i++) {
                    Thread.sleep(3000);
                    if (t == token && Jellyfin.quickConnectApproved(server, qcSecret, deviceId)) {
                        Jellyfin.Login login = Jellyfin.quickConnectLogin(server, qcSecret, deviceId);
                        main.post(() -> { if (t == token) signedIn(login); });
                        return;
                    }
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                main.post(() -> { if (t == token) status.setText(Ui.tr("Quick Connect falhou: ", "Quick Connect failed: ") + e.getMessage()); });
            }
        });
    }

    private void showPassword() {
        begin(PASSWORD);
        title(Ui.tr("Entre na sua conta", "Sign in"));
        final TextView status = body(Ui.tr("Seu usuário e senha do Jellyfin.", "Your Jellyfin user name and password."));
        final EditText user = input(Ui.tr("Usuário", "User name"), InputType.TYPE_CLASS_TEXT);
        final EditText pass = input(Ui.tr("Senha", "Password"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final int t = token;
        Runnable go = () -> {
            String u = user.getText().toString().trim(), pw = pass.getText().toString();
            if (u.isEmpty()) return;
            status.setText(Ui.tr("Entrando…", "Signing in…"));
            io.execute(() -> {
                try {
                    Jellyfin.Login login = Jellyfin.passwordLogin(server, u, pw, Prefs.deviceId(this));
                    main.post(() -> { if (t == token) signedIn(login); });
                } catch (Exception e) {
                    main.post(() -> { if (t == token) status.setText(Ui.tr("Não deu: ", "Couldn't: ") + e.getMessage()); });
                }
            });
        };
        onDone(pass, go);
        button(Ui.tr("Entrar", "Sign in"), go);
        user.requestFocus();
    }

    private void signedIn(Jellyfin.Login login) {
        Prefs.saveJellyfin(this, server, serverName, login.token, login.userId, login.userName);
        if (single) finishStep();
        else showCity();
    }

    private void showCity() {
        begin(CITY);
        title(Ui.tr("Sua cidade", "Your city"));
        final TextView status = body(Ui.tr("Para o clima e para o modo noturno, que segue o pôr do sol daí.",
                "For the weather and the night look, which follows your sunset."));
        final EditText input = input(Ui.tr("Nome da cidade", "City name"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        final LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        final int t = token;
        Runnable search = () -> {
            String q = input.getText().toString().trim();
            if (q.isEmpty()) return;
            status.setText(Ui.tr("Buscando…", "Searching…"));
            results.removeAllViews();
            io.execute(() -> {
                try {
                    JSONArray list = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=5&language="
                            + (Ui.en ? "en" : "pt") + "&name=" + URLEncoder.encode(q, "UTF-8"))).optJSONArray("results");
                    main.post(() -> {
                        if (t != token) return;
                        if (list == null || list.length() == 0) {
                            status.setText(Ui.tr("Não achei essa cidade. Tente outro nome.", "Couldn't find it. Try another name."));
                            return;
                        }
                        status.setText(Ui.tr("Qual delas?", "Which one?"));
                        for (int i = 0; i < list.length(); i++) {
                            JSONObject o = list.optJSONObject(i);
                            String where = o.optString("admin1") + (o.optString("country").isEmpty() ? "" : ", " + o.optString("country"));
                            View b = button(results, o.optString("name") + " · " + where, () -> {
                                Prefs.saveCity(this, o.optDouble("latitude"), o.optDouble("longitude"), o.optString("name"));
                                if (single) finishStep(); else showDone();
                            });
                            if (i == 0) b.requestFocus();
                        }
                    });
                } catch (Exception e) {
                    main.post(() -> { if (t == token) status.setText(Ui.tr("Sem resposta da busca: ", "No answer: ") + e.getMessage()); });
                }
            });
        };
        onDone(input, search);
        button(Ui.tr("Buscar", "Search"), search);
        panel.addView(results);
        if (!single) button(Ui.tr("Pular", "Skip"), this::showDone);
        input.requestFocus();
    }

    private void showDone() {
        begin(DONE);
        boolean debugging = Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
        title(Ui.tr("Tudo pronto", "All set"));
        if (debugging) {
            body(Ui.tr("Ao ativar, o Marquise vira o Início da TV. A TV vai pedir para permitir a depuração: marque "
                    + "“Sempre permitir” e confirme. Dá para voltar ao Início original quando quiser.",
                    "Switching on makes Marquise your TV's Home. The TV asks to allow debugging: tick “Always allow” and "
                    + "confirm. You can go back to the original Home anytime."));
            button(Ui.tr("Ativar o Marquise", "Switch Marquise on"), () -> {
                Prefs.finishSetup(this);
                HomeMode.request(this, HomeMode.FREE);
                startActivity(new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                finish();
            }).requestFocus();
        } else {
            body(Ui.tr("Falta um ajuste na TV para o Marquise poder trocar o Início:\n"
                    + "1. Em Sobre, aperte 7 vezes em “Build do Android TV OS”.\n"
                    + "2. Em Opções do desenvolvedor, ative “Depuração USB”.\n"
                    + "Depois volte aqui.",
                    "One TV setting lets Marquise switch the Home screen:\n"
                    + "1. In About, press “Android TV OS build” 7 times.\n"
                    + "2. In Developer options, turn on “USB debugging”.\n"
                    + "Then come back here."));
            button(Ui.tr("Abrir Sobre", "Open About"), () -> open(Settings.ACTION_DEVICE_INFO_SETTINGS)).requestFocus();
            button(Ui.tr("Abrir Opções do desenvolvedor", "Open Developer options"),
                    () -> open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        }
        button(Ui.tr("Agora não", "Not now"), () -> {
            Prefs.finishSetup(this);
            startActivity(new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        });
    }

    /** Opened from the settings for one thing: done, back to them. */
    private void finishStep() {
        Prefs.finishSetup(this);
        finish();
    }

    private void open(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    // MARK: - Views

    private void begin(int s) {
        step = s;
        token++;
        panel.removeAllViews();
    }

    private int px(float v) {
        return Math.round(v * k);
    }

    private TextView text(String s, float size, android.graphics.Typeface face, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        return t;
    }

    private void title(String s) {
        TextView t = text(s, 46, Ui.displaySemibold, CREAM);
        t.setGravity(Gravity.START);
        t.setPadding(px(30), 0, px(30), px(18));
        panel.addView(t);
    }

    private TextView body(String s) {
        TextView t = text(s, 27, Ui.regular, 0xCCFFF4E2);
        t.setGravity(Gravity.START);
        t.setLineSpacing(px(8), 1f);
        t.setPadding(px(30), 0, px(30), px(26));
        panel.addView(t);
        return t;
    }

    private View button(String label, Runnable action) {
        return button(panel, label, action);
    }

    /** A row like the settings screen's: no background; focused, white with dark text (the Apple TV focus). */
    private View button(LinearLayout parent, String label, Runnable action) {
        TextView b = text(label, 30, Ui.medium, 0xF2FFFFFF);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setPadding(px(30), 0, px(30), 0);
        b.setFocusable(true);
        b.setClickable(true);
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setColor(0xFFF5F5F7);
        on.setCornerRadius(18 * k);
        bg.addState(new int[] {android.R.attr.state_focused}, on);
        bg.addState(new int[] {}, new GradientDrawable());
        b.setBackground(bg);
        b.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}}, new int[] {INK, 0xF2FFFFFF}));
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(74));
        lp.bottomMargin = px(8);
        parent.addView(b, lp);
        return b;
    }

    private EditText input(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30 * k);
        e.setTypeface(Ui.regular);
        e.setTextColor(0xFFFFFFFF);
        e.setHintTextColor(0x80FFFFFF);
        e.setPadding(px(30), 0, px(30), 0);
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setColor(0x40FFFFFF);
        on.setStroke(Math.max(2, px(3)), 0xFFFFFFFF);
        on.setCornerRadius(18 * k);
        GradientDrawable off = new GradientDrawable();
        off.setColor(0x26FFFFFF);
        off.setCornerRadius(18 * k);
        bg.addState(new int[] {android.R.attr.state_focused}, on);
        bg.addState(new int[] {}, off);
        e.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(80));
        lp.bottomMargin = px(14);
        panel.addView(e, lp);
        return e;
    }

    /** The keyboard's "done" key runs the step's action. */
    private void onDone(EditText e, Runnable action) {
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setOnEditorActionListener((v, id, ev) -> {
            action.run();
            return true;
        });
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }
}
