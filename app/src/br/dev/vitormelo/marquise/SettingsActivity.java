package br.dev.vitormelo.marquise;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Marquise's settings (the app's icon, "All Apps", or the gear on the home screen), in the style of Apple TV's
 * settings: the big symbol and title on the left, the list on the right with the focused row in white. "Home screen"
 * opens the Marquise / Original choice (with a check on the current one); the switch runs in the main process
 * (HomeMode) and this screen only asks for it and follows the state file.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private DreamView preview;   // "Screensaver": the screensaver right here, without changing the system setting

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        if (!Prefs.setupDone(this)) {   // first run: the wizard (server, account, city), then back here
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        view = new SettingsView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        if (view == null) return;
        view.resume();
        // From ADB: am start -n br.dev.vitormelo.marquise/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onPause() {
        if (view == null) {
            super.onPause();
            return;
        }
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) closePreview();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() {
        preview = new DreamView(this);
        setContentView(preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        setContentView(view);
        view.resume();
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_CHOICE = 1, REPORT = 2;
        /** The "This TV" row: opens the report screen. */
        private static final int THIS_TV = 4;
        private static final float LIST_X = 1000, LIST_R = 1824, LIST_TOP = 150, ROW_H = 70, ROW_GAP = 8, CHOICE_H = 104;
        private static final int ROWS = 6;
        private static final int AMBER = 0xFFFFC266, INK = 0xFF1C1C1E;

        private final SettingsActivity activity;
        // The marquee's velvet: the same colors as the icon (tools/make_art.py).
        private static final int VELVET_TOP = 0xFF7A1830, VELVET_BOTTOM = 0xFF280E2E, CREAM = 0xFFFFF4E2;
        private final Paint velvet = new Paint(), glow = new Paint(), logoPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Bitmap logo, qr;
        private static final String REPO = "github.com/vitoramaral10/marquise", AUTHOR = "vitoramaral10";
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[8];
        private final RectF tmp = new RectF(), rowRect = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey;
        private String tmdbApps = "";
        private final String tvName = TvReport.brandModel();
        private Bitmap reportQr;               // made the first time "This TV" opens
        private final Paint qrPaint = new Paint();   // no filtering: the modules stay sharp when scaled up   // the installed streaming apps TMDB titles open in (looked up on resume)
        private int screen = MAIN, focus;
        private boolean running, okDown, backDown;
        private long startedAt, lastNanos, pendingAt;
        private int w, h;
        private float k = 1;
        private Paint title, subtitle, version, rowLabel, rowValue, rowSub, header, warn, spinner, white, shadow;
        private TextPaint notice;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                if (!state.optString("busy").isEmpty()) pendingAt = 0;
                invalidate();
                handler.postDelayed(this, 500);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            logo = BitmapFactory.decodeResource(a.getResources(), R.drawable.logo);
            qr = BitmapFactory.decodeResource(a.getResources(), R.drawable.qr_repo);
            setFocusable(true);
        }

        void resume() {
            Updater.check();
            tmdbApps = Tmdb.installedNames(getContext());
            if (running) return;
            running = true;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
        }

        void pause() {
            running = false;
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = busy();
            for (Spring s : pop) moving |= !s.settled();
            if (moving) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            velvet.setShader(new LinearGradient(0, 0, 0, h, VELVET_TOP, VELVET_BOTTOM, Shader.TileMode.CLAMP));
            // A warm halo behind the mark, like the bulbs lighting the wall.
            glow.setShader(new RadialGradient(500 * k, 400 * k, 420 * k, new int[] {0x40FFAA46, 0x14FFAA46, 0x00FFAA46},
                    new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
            title = Ui.text(Ui.displaySemibold, 56 * k, CREAM);
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 26 * k, 0xB3FFF4E2);
            subtitle.setTextAlign(Paint.Align.CENTER);
            version = Ui.text(Ui.regular, 22 * k, 0x66FFFFFF);
            version.setTextAlign(Paint.Align.CENTER);
            rowLabel = Ui.text(Ui.medium, 30 * k, 0xF2FFFFFF);
            rowValue = Ui.text(Ui.regular, 28 * k, 0x8CFFFFFF);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            header = Ui.text(Ui.semibold, 21 * k, 0x73FFFFFF);
            header.setLetterSpacing(0.06f);
            warn = Ui.text(Ui.regular, 23 * k, AMBER);
            notice = new TextPaint(Ui.text(Ui.regular, 23 * k, 0xB3FFFFFF));
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFF5F5F7);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        }

        private boolean busy() {
            return !state.optString("busy").isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        private int rowCount() {
            return screen == MAIN ? mainRows() : screen == HOME_CHOICE ? 2 : 0;
        }

        /** The fixed rows, plus "Update" when a newer release is out. */
        private int mainRows() {
            return Updater.available.isEmpty() ? ROWS : ROWS + 1;
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && i < rowCount() ? 1 : 0;
                pop[i].step(dt);
            }
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;
            c.drawRect(0, 0, w, h, velvet);
            c.drawRect(0, 0, w, h, glow);

            // On the left: the marquee mark, the name, what it is.
            float cx = 500 * k, size = 260 * k;
            if (logo != null) {
                tmp.set(cx - size / 2, 400 * k - size / 2, cx + size / 2, 400 * k + size / 2);
                c.drawBitmap(logo, null, tmp, logoPaint);
            }
            c.drawText("Marquise", cx, 622 * k, title);
            c.drawText(Ui.tr("Seus filmes e séries no Início", "Your movies and shows, up front"), cx, 672 * k, subtitle);
            c.drawText(versionName(getContext()) + Ui.tr(" · por ", " · by ") + AUTHOR, cx, 716 * k, version);
            // The GPL's "appropriate legal notices": copyright, the license, no warranty.
            c.drawText(Ui.tr("© 2026 " + AUTHOR + " · GPL-3.0 · sem garantia", "© 2026 " + AUTHOR + " · GPL-3.0 · no warranty"),
                    cx, 750 * k, version);
            // The project's page, as a QR code for the phone: source, issues, what's new.
            if (qr != null) {
                float q = 168 * k, top = 800 * k;
                tmp.set(cx - q / 2, top, cx + q / 2, top + q);
                c.drawBitmap(qr, null, tmp, logoPaint);
                c.drawText(REPO, cx, top + q + 40 * k, version);
            }
            // TMDB's terms: this notice wherever its data is used.
            if (Recs.tmdb(getContext())) {
                c.drawText(Ui.tr("Usa a API do TMDB, sem endosso ou certificação do TMDB.",
                        "Uses the TMDB API but is not endorsed or certified by TMDB."), cx, 1050 * k, version);
            }

            if (screen == MAIN) drawMain(c, seconds); else if (screen == HOME_CHOICE) drawChoice(c); else drawReport(c);
        }

        private void drawMain(Canvas c, float seconds) {
            boolean working = busy();
            String busyText = state.optString("busy");
            String mode = freeActive() ? "Marquise" : Ui.tr("Original", "Original");
            Context ctx = getContext();
            String who = Prefs.userName(ctx), where = Prefs.serverName(ctx);
            String jellyfin = !Prefs.jellyfinReady(ctx) ? Ui.tr("Configurar", "Set up")
                    : "Jellyfin" + (who.isEmpty() ? "" : " · " + who) + (where.isEmpty() ? "" : " · " + where);
            if (Recs.tmdb(ctx)) jellyfin = Prefs.tmdbReady(ctx) ? "TMDB · " + Prefs.regionName(ctx) : Ui.tr("Configurar", "Set up");
            String city = Prefs.city(ctx);
            String[] labels = {Ui.tr("Início", "Home Screen"), Ui.tr("Títulos", "Titles"), Ui.tr("Cidade", "City"), Ui.tr("Tela de Descanso", "Screensaver"),
                    Ui.tr("Esta TV", "This TV"), Ui.tr("Ajustes da TV", "TV Settings")};
            String[] values = {working ? "" : mode, jellyfin, city.isEmpty() ? Ui.tr("Escolher", "Choose") : city,
                    Ui.tr("Ver agora", "Preview"), tvName, ""};
            for (int i = 0; i < ROWS; i++) {
                float y = (LIST_TOP + i * (ROW_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                float f = drawRowBackground(c, i);
                int ink = blend(0xF2FFFFFF, INK, f);
                rowLabel.setColor(ink);
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                float right = rowRect.right - 30 * k;
                if (i == ROWS - 1) {
                    float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                    Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, rowRect.centerY(), 22 * k, blend(0x66FFFFFF, 0x991C1C1E, f));
                } else if (i == 0 && working) {
                    // Switching: the current step in place of the value, with the spinning arc.
                    String text = busyText.isEmpty() ? Ui.tr("Começando…", "Starting…") : busyText;
                    rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
                    String shown = TextUtils.ellipsize(text, new TextPaint(rowValue), 470 * k, TextUtils.TruncateAt.END).toString();
                    c.drawText(shown, right, base, rowValue);
                    float sx = right - rowValue.measureText(shown) - 26 * k;
                    spinner.setColor(blend(0xFFFFFFFF, INK, f));
                    Ui.spinner(c, sx, rowRect.centerY(), 12 * k, seconds * 1.3f, spinner);
                } else {
                    rowValue.setColor(blend(0x8CFFFFFF, 0x8C1C1C1E, f));
                    c.drawText(values[i], right, base, rowValue);
                }
            }
            if (mainRows() > ROWS) drawUpdateRow(c);

            float y = (LIST_TOP + mainRows() * (ROW_H + ROW_GAP) + 40) * k;
            String error = state.optString("error");
            if (working && (busyText.contains("Permitir") || busyText.contains("Allow"))) {
                c.drawText(Ui.tr("Na TV: marque “Sempre permitir” e aperte OK.", "On the TV: tick “Always allow” and press OK."),
                        LIST_X * k + 30 * k, y, warn);
                y += 46 * k;
            } else if (!working && !error.isEmpty()) {
                c.drawText(TextUtils.ellipsize(error, new TextPaint(warn), 760 * k, TextUtils.TruncateAt.END).toString(), LIST_X * k + 30 * k, y, warn);
                y += 46 * k;
            }
            y = drawJellyfin(c, y + 10 * k);
            y = drawTips(c, y + 50 * k);
            drawNotices(c, y + 50 * k);
        }

        /** "Update": the new version (in amber), the download's progress, or what went wrong. OK installs it. */
        private void drawUpdateRow(Canvas c) {
            float y = (LIST_TOP + ROWS * (ROW_H + ROW_GAP)) * k;
            rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
            float f = drawRowBackground(c, ROWS);
            rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
            float base = rowRect.centerY() + Ui.halfCap(rowLabel);
            c.drawText(Ui.tr("Atualização", "Update"), rowRect.left + 30 * k, base, rowLabel);
            int progress = Updater.progress;
            String value = progress >= 0 ? (progress >= 100 ? Ui.tr("Instalando…", "Installing…") : Ui.tr("Baixando ", "Downloading ") + progress + "%")
                    : !Updater.error.isEmpty() ? Ui.tr("Não deu, tente de novo", "Didn't work, try again")
                    : Ui.tr("Versão " + Updater.available + " disponível", "Version " + Updater.available + " available");
            rowValue.setColor(blend(AMBER, 0xFF8A4B00, f));
            c.drawText(value, rowRect.right - 30 * k, base, rowValue);
        }

        /** The posters: how they're doing, from which Jellyfin server (name, version, address) or TMDB country. */
        private float drawJellyfin(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            Context ctx = getContext();
            boolean tmdb = Recs.tmdb(ctx);
            c.drawText(tmdb ? "TMDB" : "JELLYFIN", x, y, header);
            String error = recs.optString("error");
            int count = recs.optInt("count");
            String server = Prefs.server(ctx).replaceFirst("^[a-zA-Z]+://", "");
            String ver = recs.optString("version");
            String line;
            Paint paint = notice;
            if (!hasKey) {
                line = Ui.tr("Ainda não conectado: escolha Títulos acima.", "Not connected yet: choose Titles above.");
            } else if (!error.isEmpty()) {
                line = Ui.tr("Erro: ", "Error: ") + error;
                paint = warn;
            } else if (count > 0 && tmdb) {
                String apps = tmdbApps;
                line = Ui.tr(count + " em alta", count + " popular") + " · " + Prefs.regionName(ctx) + (apps.isEmpty() ? "" : " · " + apps);
            } else if (count > 0) {
                line = Ui.tr(count + " títulos recentes", count + " recent titles") + (ver.isEmpty() ? "" : " · Jellyfin " + ver) + " · " + server;
            } else {
                line = Ui.tr("Buscando os títulos…", "Fetching titles…");
            }
            c.drawText(TextUtils.ellipsize(line, new TextPaint(paint), 790 * k, TextUtils.TruncateAt.END).toString(), x, y + 42 * k, paint);
            return y + 42 * k;
        }

        /** What the remote can do that nothing else on screen tells. */
        private float drawTips(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("DICAS", "TIPS"), x, y, header);
            c.drawText(Ui.tr("Segure OK num app para mover, remover ou desinstalar.", "Hold OK on an app to move, remove or uninstall it."),
                    x, y + 42 * k, notice);
            c.drawText(Ui.tr("Em Todos os Apps, segure OK para adicionar ao Início.", "In All Apps, hold OK to add one to Home."),
                    x, y + 80 * k, notice);
            return y + 80 * k;
        }

        /** List row: no background; focused, white with a shadow (the Apple TV focus). Returns how focused it is. */
        private float drawRowBackground(Canvas c, int i) {
            float f = Ui.clamp(pop[i].value);
            if (f > 0.01f) {
                float s = 1 + 0.02f * f;
                float cx = rowRect.centerX(), cy = rowRect.centerY();
                tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
                float r = 18 * k * s;
                tmp.offset(0, 10 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                c.drawRoundRect(tmp, r, r, shadow);
                tmp.offset(0, -10 * k * f);
                white.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp, r, r, white);
                rowRect.set(tmp);
            }
            return f;
        }

        private void drawNotices(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("AVISOS", "NOTICES"), x, y, header);
            JSONArray notices = state.optJSONArray("notices");
            int shown = 0;
            for (int i = 0; notices != null && i < notices.length() && shown < 1; i++) {
                JSONObject o = notices.optJSONObject(i);
                if (o == null) continue;
                String line = ago(o.optLong("at")) + " · " + o.optString("text");
                c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + (42 + 38 * shown) * k, notice);
                shown++;
            }
            if (shown == 0) c.drawText(Ui.tr("Nenhum ainda.", "None yet."), x, y + 42 * k, notice);
        }

        /** Home screen choice: Marquise or the original, with a check on the one in use. */
        /**
         * This TV: brand, model, Android, how many factory apps Marquise keeps off, and a QR code to a pre-filled GitHub
         * report (TvReport), so a TV of another brand can be supported with what it really has.
         */
        private void drawReport(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("Esta TV", "This TV"), x, (LIST_TOP - 24) * k, heading);
            int off = TvReport.turnedOff(getContext());
            String apps = !freeActive() ? Ui.tr("tudo de fábrica", "all as it came")
                    : Ui.tr(off + (off == 1 ? " app de fábrica desligado" : " apps de fábrica desligados"), off + (off == 1 ? " factory app off" : " factory apps off"));
            c.drawText(tvName + " · Android " + android.os.Build.VERSION.RELEASE + " · " + apps, x, (LIST_TOP + 30) * k, notice);
            float q = 460 * k, top = (LIST_TOP + 80) * k;
            if (reportQr != null) {
                tmp.set(x, top, x + q, top + q);
                c.drawBitmap(reportQr, null, tmp, qrPaint);
            }
            float tx = x + q + 48 * k, width = LIST_R * k - tx - 10 * k, gap = 22 * k;
            float ty = drawWrapped(c, Ui.tr("Outra marca, ou algo não funcionou?", "Another brand, or something didn't work?"),
                    tx, top + 30 * k, width, rowLabel, 0xF2FFFFFF);
            ty = drawWrapped(c, Ui.tr("Aponte a câmera do celular para o código, de perto: ele abre no GitHub um relatório "
                    + "desta TV, já preenchido, para o Marquise aprender a marca dela. Para enviar, precisa de uma conta no GitHub.",
                    "Point your phone's camera at the code, up close: it opens a report of this TV on GitHub, already filled "
                    + "in, so Marquise can learn its brand. Sending it needs a GitHub account."), tx, ty + rowLabel.getTextSize() + gap,
                    width, notice, 0xB3FFFFFF);
            drawWrapped(c, Ui.tr("Vai junto: marca, modelo, Android, o Início e a tela de descanso de fábrica e os apps que "
                    + "vieram na TV. Nada pessoal: nenhum app seu, conta, rede ou local.",
                    "It holds: brand, model, Android, the factory home screen and screensaver, and the apps that came with "
                    + "the TV. Nothing personal: none of your apps, accounts, network or location."), tx,
                    ty + notice.getTextSize() * 1.45f + gap, width, notice, 0x80FFFFFF);
        }

        /** Text wrapped to a width, from the first line's baseline; returns the last line's baseline. */
        private float drawWrapped(Canvas c, String text, float x, float baseline, float width, Paint paint, int color) {
            int before = paint.getColor();
            paint.setColor(color);
            float line = paint.getTextSize() * 1.45f, y = baseline;
            StringBuilder current = new StringBuilder();
            for (String word : text.split(" ")) {
                String next = current.length() == 0 ? word : current + " " + word;
                if (paint.measureText(next) > width && current.length() > 0) {
                    c.drawText(current.toString(), x, y, paint);
                    y += line;
                    current.setLength(0);
                    current.append(word);
                } else {
                    current.setLength(0);
                    current.append(next);
                }
            }
            if (current.length() > 0) c.drawText(current.toString(), x, y, paint);
            paint.setColor(before);
            return y;
        }

        private void drawChoice(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            c.drawText(Ui.tr("Início", "Home Screen"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String[][] options = {{"Marquise", Ui.tr("Seus pôsteres do Jellyfin, sem anúncios", "Your Jellyfin posters, no ads")},
                    {"Original", Ui.tr("O Início de fábrica da TV", "The TV's factory home screen")}};
            int current = freeActive() ? 0 : 1;
            for (int i = 0; i < 2; i++) {
                float y = (LIST_TOP + 20 + i * (CHOICE_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CHOICE_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                rowSub.setColor(blend(0x8CFFFFFF, 0x991C1C1E, f));
                c.drawText(options[i][0], rowRect.left + 30 * k, rowRect.top + 44 * k, rowLabel);
                c.drawText(options[i][1], rowRect.left + 30 * k, rowRect.top + 80 * k, rowSub);
                if (i == current) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            c.drawText(Ui.tr("A troca leva alguns segundos.", "Switching takes a few seconds."), LIST_X * k + 30 * k,
                    (LIST_TOP + 20 + 2 * (CHOICE_H + ROW_GAP) + 40) * k, hint);
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String ago(long at) {
            long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
            if (minutes < 1) return Ui.tr("agora", "now");
            if (minutes < 60) return Ui.tr("há " + minutes + " min", minutes + " min ago");
            if (minutes < 48 * 60) return Ui.tr("há " + (minutes / 60) + " h", (minutes / 60) + " h ago");
            return Ui.tr("há " + (minutes / 1440) + " dias", (minutes / 1440) + " days ago");
        }

        private static String versionName(Context c) {
            try {
                return "Marquise " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "Marquise";
            }
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && focus > 0) focus--;
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && focus < rowCount() - 1) focus++;
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled()) activate();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (screen == MAIN) return false;   // leaves the settings
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            focus = screen == REPORT ? THIS_TV : 0;
                            screen = MAIN;
                        }
                        backDown = false;
                    }
                    invalidate();
                    return true;
                default:
                    return false;
            }
        }

        private void activate() {
            Context c = getContext();
            if (screen == REPORT) {
                return;
            } else if (screen == HOME_CHOICE) {
                boolean wantFree = focus == 0;
                if (wantFree != freeActive() && !busy()) {
                    HomeMode.request(c, wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                    pendingAt = SystemClock.uptimeMillis();
                }
                screen = MAIN;
                focus = 0;
            } else if (focus == 0) {
                if (busy()) return;
                screen = HOME_CHOICE;
                focus = freeActive() ? 0 : 1;
            } else if (focus == 1 || focus == 2) {
                activity.startActivity(new Intent(c, SetupActivity.class)
                        .putExtra(SetupActivity.EXTRA_STEP, focus == 1 ? SetupActivity.STEP_SOURCE : SetupActivity.STEP_CITY));
            } else if (focus == ROWS) {
                Updater.install(c);
            } else if (focus == THIS_TV) {
                if (reportQr == null) reportQr = TvReport.qr(TvReport.url(c));
                screen = REPORT;
            } else if (focus == 3) {
                pause();
                activity.showPreview();
            } else {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            for (Spring s : pop) s.snap(0);
            invalidate();
        }
    }
}
