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
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

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
        private static final int MAIN = 0, HOME_CHOICE = 1, REPORT = 2, UPDATES = 3, PREFS = 4, APPS = 5, KEYS = 6;
        /**
         * The "This TV" row: opens the report screen. "Updates": the update, if any, and stable or beta releases.
         * "Preferences": language, temperature and the assistant (what only config.properties could set before).
         */
        private static final int THIS_TV = 4, UPDATES_ROW = 5, PREFS_ROW = 6;
        private static final float LIST_X = 1000, LIST_R = 1824, LIST_TOP = 150, ROW_H = 64, ROW_GAP = 6, CHOICE_H = 104;
        private static final int ROWS = 8, PREF_ROWS = 5, APP_ROWS = 8, FACTORY_ROW = 3, KEYS_ROW = 4;
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
        private Bitmap reportQr;               // made when "This TV" opens (off the UI thread), again if the link changed
        private String reportUrl;              // the link reportQr is of
        private String[] errorLines;           // the last problems AppLog kept, newest first, as lines; null until read
        private boolean assistantOff;          // Preferences' third row, read when the screen opens
        // Factory apps (Preferences' fourth row): the system apps to choose from, built off the UI thread when it opens.
        private String[][] appItems;           // {package, label cut to fit}; null while it is being built
        private final java.util.Set<String> appPicked = new java.util.LinkedHashSet<>();
        private int appLoadToken;              // which opening of the list a background load belongs to
        private int appScroll;                 // the first row shown
        private String appsTitle = "", appsSummary = "";
        // Remote buttons (Preferences' fifth row): what each one opens; the app list above picks one for pickFor.
        private String[] keyValues = new String[RemoteKeys.BUTTONS.length];
        private int pickFor = -1;              // the button whose app is being chosen; -1 = the list is Factory apps
        private final Paint qrPaint = new Paint();   // no filtering: the modules stay sharp when scaled up   // the installed streaming apps TMDB titles open in (looked up on resume)
        private int screen = MAIN, focus;
        // For a screen reader (TalkBack): the rows of the screen on show, as virtual nodes. Built only when asked.
        private int a11yLast = View.NO_ID, a11yScreenSeen = MAIN;
        private String a11ySig = "";
        private final HomeAccess access = new HomeAccess(this, this::a11yNodes);
        private final Runnable a11yNotify = () -> {
            access.changed();
            // Another screen (OK on a row, Back): say where the focus is, not only that the screen changed.
            if (screen != a11yScreenSeen) {
                a11yScreenSeen = screen;
                a11yMoved();
            }
        };
        /** Whether the Updates screen has had the "Install" row: when it appears, focus moves down with the rows. */
        private boolean updatesHadInstall;
        // The Updates screen's choices, built again only when what they say changes (nothing is made per frame).
        private String[][] updatesCache;
        private String cachedAvailable = "", cachedError = "";
        private int cachedProgress = -2;
        private boolean cachedBetas;
        private String cachedLang = "";
        private boolean running, okDown, backDown;
        private long startedAt, lastNanos, pendingAt;
        private int w, h;
        private float k = 1;
        private Paint title, subtitle, version, rowLabel, rowValue, rowSub, header, warn, spinner, white, shadow, heading, hint;
        private TextPaint notice;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                if (!state.optString("busy").isEmpty()) pendingAt = 0;
                String sig = state.optString("mode") + "|" + state.optString("busy") + "|" + state.optString("error") + "|"
                        + Updater.available + "|" + Updater.progress + "|" + Updater.error;
                if (!sig.equals(a11ySig)) {
                    a11ySig = sig;
                    a11yChanged();
                }
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
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        void resume() {
            Updater.check(getContext());
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
            heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
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
            if (screen == UPDATES) syncUpdatesFocus();
            if (screen == APPS) return appItems == null ? 0 : appItems.length;
            if (screen == KEYS) return RemoteKeys.BUTTONS.length;
            return screen == MAIN ? ROWS : screen == HOME_CHOICE ? 2 : screen == PREFS ? PREF_ROWS : screen == UPDATES ? (Updater.available.isEmpty() ? 2 : 3) : 0;   // as updateOptions()
        }

        /** The "Install" row came or went while the screen is open: the rows moved, so focus follows its option. */
        private void syncUpdatesFocus() {
            boolean has = !Updater.available.isEmpty();
            if (has == updatesHadInstall) return;
            updatesHadInstall = has;
            focus = Math.max(0, Math.min(has ? 2 : 1, focus + (has ? 1 : -1)));
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            // The rows of a long list are drawn from the first one shown: the springs are of the rows on screen.
            int selected = screen == APPS ? focus - appScroll : focus, shown = screen == APPS ? Math.min(APP_ROWS, rowCount() - appScroll) : rowCount();
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == selected && i < shown ? 1 : 0;
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
            c.drawText(Ui.tr("© 2026 " + AUTHOR + " · GPL-3.0 · sem garantia", "© 2026 " + AUTHOR + " · GPL-3.0 · no warranty",
                    "© 2026 " + AUTHOR + " · GPL-3.0 · sin garantía"),
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

            if (screen == MAIN) drawMain(c, seconds);
            else if (screen == HOME_CHOICE) drawHomeChoice(c);
            else if (screen == UPDATES) drawUpdates(c);
            else if (screen == PREFS) drawPrefs(c);
            else if (screen == APPS) drawApps(c);
            else if (screen == KEYS) drawKeys(c);
            else drawReport(c);
        }

        // MARK: - Screen reader (TalkBack)

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return access;
        }

        /** What is on screen changed: the reader asks again, a moment later (several things change together). */
        private void a11yChanged() {
            if (!access.on()) return;
            removeCallbacks(a11yNotify);
            postDelayed(a11yNotify, 150);
        }

        /** The focus may have moved: say where it is now, if it is somewhere else. */
        private void a11yMoved() {
            if (!access.on()) return;
            int id = screen * 1000 + focus;
            if (id == a11yLast) return;
            a11yLast = id;
            access.focusMoved(id);
        }

        private RectF a11yRect(float top, float rowH, int i) {
            float y = top + i * (rowH + ROW_GAP);
            return new RectF(LIST_X * k, y * k, LIST_R * k, (y + rowH) * k);
        }

        private HomeAccess.Node a11yRow(int index, String text, RectF rect, boolean selected) {
            final int at = index;
            HomeAccess.Node n = new HomeAccess.Node(screen * 1000 + index, text, rect);
            n.selected = selected;
            n.click = () -> {
                focus = at;
                activate();
            };
            n.focus = () -> {
                focus = at;
                a11yLast = screen * 1000 + at;
                invalidate();
            };
            return n;
        }

        /** The rows of the screen on show, in reading order: what a person can choose here. */
        private List<HomeAccess.Node> a11yNodes() {
            List<HomeAccess.Node> out = new ArrayList<>();
            if (screen == MAIN) {
                boolean working = busy();
                String[] labels = mainLabels(), values = mainValues(working);
                for (int i = 0; i < ROWS; i++) {
                    String value = i == 0 && working ? (state.optString("busy").isEmpty() ? Ui.tr("Começando…", "Starting…") : state.optString("busy")) : values[i];
                    out.add(a11yRow(i, labels[i] + (value.isEmpty() ? "" : ", " + value), a11yRect(LIST_TOP, ROW_H, i), false));
                }
            } else if (screen == HOME_CHOICE) {
                String[][] options = homeOptions();
                for (int i = 0; i < options.length; i++) {
                    out.add(a11yRow(i, options[i][0] + ", " + options[i][1], a11yRect(LIST_TOP + 20, CHOICE_H, i), i == (freeActive() ? 0 : 1)));
                }
            } else if (screen == UPDATES) {
                String[][] options = updateOptions();
                int current = options.length - 2 + (Prefs.betas(getContext()) ? 1 : 0);
                for (int i = 0; i < options.length; i++) {
                    out.add(a11yRow(i, options[i][0] + ", " + options[i][1], a11yRect(LIST_TOP + 20, CHOICE_H, i), i == current));
                }
            } else if (screen == PREFS) {
                for (int i = 0; i < PREF_ROWS; i++) {
                    out.add(a11yRow(i, prefLabel(i) + ", " + prefValue(i), a11yRect(LIST_TOP + 20, ROW_H, i), false));
                }
            } else if (screen == KEYS) {
                for (int i = 0; i < RemoteKeys.BUTTONS.length; i++) {
                    out.add(a11yRow(i, RemoteKeys.name(i) + ", " + keyValues[i], a11yRect(LIST_TOP + 20, ROW_H, i), false));
                }
            } else if (screen == APPS) {
                if (appItems == null || appItems.length == 0) {
                    HomeAccess.Node n = new HomeAccess.Node(screen * 1000, appItems == null ? Ui.tr("Procurando os apps…", "Looking for the apps…")
                            : Ui.tr("Nenhum app de fábrica para escolher.", "No factory apps to choose from."), a11yRect(LIST_TOP + 20, ROW_H, 0));
                    out.add(n);
                } else {
                    for (int i = appScroll; i < appItems.length && i < appScroll + APP_ROWS; i++) {
                        out.add(a11yRow(i, appItems[i][1], a11yRect(LIST_TOP + 20, ROW_H, i - appScroll), appPicked.contains(appItems[i][0])));
                    }
                }
            } else {   // the report: one block of text to read, and the code is for a phone's camera
                StringBuilder text = new StringBuilder(Ui.tr("Esta TV. ", "This TV. ")).append(reportLine()).append(". ")
                        .append(reportHow()).append(' ').append(reportHolds()).append(' ');
                if (errorLines != null) {
                    text.append(errorLines.length == 0 ? Ui.tr("Nenhum erro recente.", "No recent errors.")
                            : Ui.tr("Últimos erros: ", "Last errors: ") + String.join(". ", errorLines));
                }
                out.add(new HomeAccess.Node(screen * 1000, text.toString(), new RectF(LIST_X * k, LIST_TOP * k, LIST_R * k, 900 * k)));
            }
            return out;
        }

        /** The names of the main list's rows. */
        private static String[] mainLabels() {
            return new String[] {Ui.tr("Início", "Home Screen"), Ui.tr("Títulos", "Titles"), Ui.tr("Cidade", "City"),
                    Ui.tr("Tela de Descanso", "Screensaver"), Ui.tr("Esta TV", "This TV"), Ui.tr("Atualizações", "Updates"),
                    Ui.tr("Preferências", "Preferences"), Ui.tr("Ajustes da TV", "TV Settings")};
        }

        /** What each main row says on its right (the first one is empty while a switch is working: it shows the step). */
        private String[] mainValues(boolean working) {
            String mode = freeActive() ? "Marquise" : Ui.tr("Original", "Original");
            Context ctx = getContext();
            String who = Prefs.userName(ctx), where = Prefs.serverName(ctx);
            String jellyfin = !Prefs.jellyfinReady(ctx) ? Ui.tr("Configurar", "Set up")
                    : "Jellyfin" + (who.isEmpty() ? "" : " · " + who) + (where.isEmpty() ? "" : " · " + where);
            if (Recs.tmdb(ctx)) jellyfin = Prefs.tmdbReady(ctx) ? "TMDB · " + Prefs.regionName(ctx) : Ui.tr("Configurar", "Set up");
            String city = Prefs.city(ctx);
            return new String[] {working ? "" : mode, jellyfin, city.isEmpty() ? Ui.tr("Escolher", "Choose") : city,
                    Ui.tr("Ver agora", "Preview"), tvName, updateStatus(), "", ""};
        }

        private void drawMain(Canvas c, float seconds) {
            boolean working = busy();
            String busyText = state.optString("busy");
            String[] labels = mainLabels(), values = mainValues(working);
            for (int i = 0; i < ROWS; i++) {
                float y = (LIST_TOP + i * (ROW_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                float f = drawRowBackground(c, i);
                int ink = blend(0xF2FFFFFF, INK, f);
                rowLabel.setColor(ink);
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                float right = rowRect.right - 30 * k;
                if (i == ROWS - 1 || i == PREFS_ROW) {
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
                    // A new version, its download or what went wrong, in amber.
                    boolean news = i == UPDATES_ROW && updateNews();
                    rowValue.setColor(news ? blend(AMBER, 0xFF8A4B00, f) : blend(0x8CFFFFFF, 0x8C1C1C1E, f));
                    c.drawText(values[i], right, base, rowValue);
                }
            }

            float y = (LIST_TOP + ROWS * (ROW_H + ROW_GAP) + 40) * k;
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

        /** "Preferences": language, temperature and the assistant. OK on a row moves to its next option, saved at once. */
        private void drawPrefs(Canvas c) {
            c.drawText(Ui.tr("Preferências", "Preferences"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            for (int i = 0; i < PREF_ROWS; i++) {
                float y = (LIST_TOP + 20 + i * (ROW_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(prefLabel(i), rowRect.left + 30 * k, base, rowLabel);
                rowValue.setColor(blend(0x8CFFFFFF, 0x8C1C1C1E, f));
                c.drawText(prefValue(i), rowRect.right - 30 * k, base, rowValue);
            }
            float ny = (LIST_TOP + 20 + PREF_ROWS * (ROW_H + ROW_GAP) + 40) * k;
            c.drawText(Ui.tr("OK passa para a próxima opção.", "OK moves to the next option."), LIST_X * k + 30 * k, ny, hint);
            c.drawText(Ui.tr("Desligado, o botão do microfone do controle não funciona.",
                    "Turned off, the remote's mic button doesn't work."), LIST_X * k + 30 * k, ny + 34 * k, hint);
        }

        private static String prefLabel(int row) {
            return row == 0 ? Ui.tr("Idioma", "Language") : row == 1 ? Ui.tr("Temperatura", "Temperature")
                    : row == 2 ? Ui.tr("Google Assistente", "Google Assistant") : row == FACTORY_ROW ? Ui.tr("Apps de fábrica", "Factory apps")
                    : Ui.tr("Botões do controle", "Remote buttons");
        }

        private String prefValue(int row) {
            if (row == 0) {
                String l = Prefs.language.trim().toLowerCase(java.util.Locale.ROOT);
                return l.startsWith("pt") ? "Português" : l.startsWith("en") ? "English" : l.startsWith("es") ? "Español"
                        : Ui.tr("Automático (da TV)", "Automatic (the TV's)");
            }
            if (row == 1) {
                String t = Prefs.temperature.trim().toUpperCase(java.util.Locale.ROOT);
                return t.startsWith("F") ? "°F" : t.startsWith("C") ? "°C" : Ui.tr("Automático (da TV)", "Automatic (the TV's)");
            }
            if (row == FACTORY_ROW) return appsSummary;
            if (row == KEYS_ROW) return Ui.tr("Escolher", "Choose");
            return assistantOff ? Ui.tr("Desligar com o Marquise", "Turn off with Marquise") : Ui.tr("Manter ligado", "Leave on");
        }

        /** The next option of a preferences row: Automatic → Português → English → Español, Automatic → °C → °F, on ⇄ off. */
        private void nextPref(int row) {
            Context c = getContext();
            if (row == 0) {
                String l = Prefs.language.trim().toLowerCase(java.util.Locale.ROOT);
                // Automatic → Português → English → Español → Automatic.
                Prefs.setLanguage(c, l.startsWith("pt") ? "en" : l.startsWith("en") ? "es" : l.startsWith("es") ? "auto" : "pt");
            } else if (row == 1) {
                String t = Prefs.temperature.trim().toUpperCase(java.util.Locale.ROOT);
                Prefs.setTemperature(c, t.startsWith("F") ? "auto" : t.startsWith("C") ? "F" : "C");
            } else if (row == 2) {
                assistantOff = !assistantOff;
                Prefs.setAssistantOff(c, assistantOff);
                if (freeActive()) HomeMode.verify(c);   // turns it off or back on now, not at the next switch
            } else if (row == FACTORY_ROW) {
                openApps();
            } else {
                openKeys(0);
            }
        }

        /** "None" or "3 chosen", for Preferences' fourth row; made when the count changes, not per frame. */
        private void updateAppsSummary() {
            int n = appPicked.size();
            appsSummary = n == 0 ? Ui.tr("Nenhum", "None") : n + Ui.tr(n == 1 ? " escolhido" : " escolhidos", " chosen", n == 1 ? " elegida" : " elegidas");
        }

        private static int byLabel(String[] x, String[] y) {
            return String.CASE_INSENSITIVE_ORDER.compare(x[1], y[1]);
        }

        /**
         * Factory apps: the TV's system apps with an icon (never a protected one, Marquise, or what Marquise already
         * turns off by itself), and what was picked before even if it has no icon now. OK on one picks it or not,
         * saved at once; it takes effect the next time Marquise is switched on. Built off the UI thread: it asks the
         * package manager for every app's name.
         */
        private void openApps() {
            final Context c = getContext();
            pickFor = -1;
            appItems = null;
            appScroll = 0;
            appPicked.clear();
            appPicked.addAll(Prefs.extraDisable(c));
            final int token = ++appLoadToken;   // a load of an earlier opening, still running, must not show its list
            final java.util.Set<String> picked = new java.util.LinkedHashSet<>(appPicked);   // its own copy: the screen edits appPicked
            screen = APPS;
            focus = 0;
            appsTitle = Ui.tr("Apps de fábrica", "Factory apps");
            new Thread(() -> {
                java.util.TreeSet<String> pkgs = new java.util.TreeSet<>(TvReport.systemApps(c));
                pkgs.addAll(picked);
                android.content.pm.PackageManager pm = c.getPackageManager();
                java.util.List<String[]> list = new java.util.ArrayList<>();
                TextPaint p = new TextPaint(rowLabel);
                for (String pkg : pkgs) {
                    if (!HomeMode.canPick(pkg)) continue;
                    String label = pkg;
                    try {
                        label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
                    } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                        // A picked app that is gone from the TV: listed by its package name.
                    }
                    list.add(new String[] {pkg, TextUtils.ellipsize(label, p, 640 * k, TextUtils.TruncateAt.END).toString()});
                }
                list.sort(SettingsView::byLabel);
                final String[][] items = list.toArray(new String[0][]);
                post(() -> {
                    if (token != appLoadToken || screen != APPS) return;
                    appItems = items;
                    updateAppsTitle();
                    a11yChanged();
                    invalidate();
                });
            }, "marquise-apps").start();
        }

        private void updateAppsTitle() {
            appsTitle = (pickFor >= 0 ? RemoteKeys.name(pickFor) : Ui.tr("Apps de fábrica", "Factory apps"))
                    + (appItems == null || appItems.length == 0 ? "" : " · " + (focus + 1) + "/" + appItems.length);
        }

        /** Keeps the focused row inside the eight shown. */
        private void scrollToFocus() {
            if (focus < appScroll) appScroll = focus;
            if (focus >= appScroll + APP_ROWS) appScroll = focus - APP_ROWS + 1;
        }

        private void drawApps(Canvas c) {
            float x = LIST_X * k + 30 * k;
            c.drawText(appsTitle, x, (LIST_TOP - 24) * k, heading);
            if (appItems == null || appItems.length == 0) {
                c.drawText(appItems == null ? Ui.tr("Procurando os apps…", "Looking for the apps…")
                        : Ui.tr("Nenhum app de fábrica para escolher.", "No factory apps to choose from."), x, (LIST_TOP + 60) * k, hint);
                return;
            }
            int n = Math.min(APP_ROWS, appItems.length - appScroll);
            for (int i = 0; i < n; i++) {
                String[] item = appItems[appScroll + i];
                float y = (LIST_TOP + 20 + i * (ROW_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                c.drawText(item[1], rowRect.left + 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
                if (appPicked.contains(item[0])) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            float ny = (LIST_TOP + 20 + APP_ROWS * (ROW_H + ROW_GAP) + 36) * k;
            if (pickFor >= 0) {
                c.drawText(Ui.tr("OK escolhe o que este botão do controle abre.", "OK picks what this remote button opens."), x, ny, hint);
                return;
            }
            c.drawText(appItems[focus][0], x, ny, hint);
            c.drawText(Ui.tr("OK escolhe o app para desligar; vale na próxima vez que o Marquise for ligado.",
                    "OK picks the app to turn off; it takes effect the next time Marquise is switched on."), x, ny + 34 * k, hint);
            c.drawText(Ui.tr("Apps do fabricante podem ter funções da TV. O Original liga tudo de volta.",
                    "A maker's apps may hold TV functions. Original mode turns it all back on."), x, ny + 68 * k, hint);
        }

        /** Remote buttons: one row per button, with what it opens; focus on the given one. */
        private void openKeys(int at) {
            Context c = getContext();
            for (int i = 0; i < keyValues.length; i++) {
                String[] b = RemoteKeys.BUTTONS[i];
                keyValues[i] = targetLabel(c, Prefs.remoteTarget(c, b[1], b[3]), i);
            }
            screen = KEYS;
            focus = at;
        }

        /** What a button's target is called: the app's name, or what a target that isn't an app does. */
        private static String targetLabel(Context c, String target, int button) {
            if (RemoteKeys.PASS.equals(target)) return Ui.tr("Como a TV faz", "As the TV does");
            if (RemoteKeys.NOTHING.equals(target)) return Ui.tr("Nada", "Nothing");
            if (RemoteKeys.TV_SETTINGS.equals(target)) return Ui.tr("Ajustes da TV", "TV Settings");
            if (RemoteKeys.LIVE_TV.equals(target)) return Ui.tr("TV ao Vivo", "Live TV");
            try {
                android.content.pm.PackageManager pm = c.getPackageManager();
                return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(target, 0)));
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                // Not installed: the button's own app opens its store page, so it keeps the button's name.
                return target.equals(RemoteKeys.BUTTONS[button][3]) ? RemoteKeys.BUTTONS[button][2] : target;
            }
        }

        /**
         * The list of what a button can open: the TV's own action, nothing, its settings, live TV, the button's own app (even
         * when it isn't installed: then its store page) and every TV app. Built off the UI thread, like Factory apps.
         */
        private void openPick(final int button) {
            final Context c = getContext();
            pickFor = button;
            appItems = null;
            appScroll = 0;
            appPicked.clear();
            final String[] b = RemoteKeys.BUTTONS[button];
            appPicked.add(Prefs.remoteTarget(c, b[1], b[3]));
            final int token = ++appLoadToken;
            screen = APPS;
            focus = 0;
            updateAppsTitle();
            new Thread(() -> {
                android.content.pm.PackageManager pm = c.getPackageManager();
                java.util.List<String[]> first = new java.util.ArrayList<>(), apps = new java.util.ArrayList<>();
                first.add(new String[] {RemoteKeys.PASS, targetLabel(c, RemoteKeys.PASS, button)});
                first.add(new String[] {RemoteKeys.NOTHING, targetLabel(c, RemoteKeys.NOTHING, button)});
                first.add(new String[] {RemoteKeys.TV_SETTINGS, targetLabel(c, RemoteKeys.TV_SETTINGS, button)});
                if (HomeApps.liveTv(c) != null) first.add(new String[] {RemoteKeys.LIVE_TV, targetLabel(c, RemoteKeys.LIVE_TV, button)});
                java.util.Set<String> seen = new java.util.HashSet<>();
                seen.add(c.getPackageName());
                if (b[3].contains(".") && HomeApps.launchIntent(pm, b[3]) == null && seen.add(b[3])) {
                    first.add(new String[] {b[3], b[2] + " · Play Store"});
                }
                TextPaint p = new TextPaint(rowLabel);
                Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                    String pkg = ri.activityInfo.packageName;
                    if (!seen.add(pkg)) continue;
                    apps.add(new String[] {pkg, TextUtils.ellipsize(String.valueOf(ri.loadLabel(pm)), p, 640 * k, TextUtils.TruncateAt.END).toString()});
                }
                apps.sort(SettingsView::byLabel);
                first.addAll(apps);
                final String[][] items = first.toArray(new String[0][]);
                post(() -> {
                    if (token != appLoadToken || screen != APPS) return;
                    appItems = items;
                    for (int i = 0; i < items.length; i++) if (appPicked.contains(items[i][0])) focus = i;
                    scrollToFocus();
                    updateAppsTitle();
                    a11yChanged();
                    invalidate();
                });
            }, "marquise-pick").start();
        }

        private void drawKeys(Canvas c) {
            c.drawText(Ui.tr("Botões do controle", "Remote buttons"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            for (int i = 0; i < RemoteKeys.BUTTONS.length; i++) {
                float y = (LIST_TOP + 20 + i * (ROW_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(RemoteKeys.name(i), rowRect.left + 30 * k, base, rowLabel);
                rowValue.setColor(blend(0x8CFFFFFF, 0x8C1C1C1E, f));
                c.drawText(keyValues[i], rowRect.right - 30 * k, base, rowValue);
            }
            float ny = (LIST_TOP + 20 + RemoteKeys.BUTTONS.length * (ROW_H + ROW_GAP) + 40) * k;
            c.drawText(Ui.tr("Com o Marquise ligado, cada botão abre o que está aqui.",
                    "With Marquise on, each button opens what is set here."), LIST_X * k + 30 * k, ny, hint);
            c.drawText(Ui.tr("No modo Original, os botões voltam a ser da TV.",
                    "In Original mode, the buttons go back to the TV."), LIST_X * k + 30 * k, ny + 34 * k, hint);
        }

        /** Whether "Updates" has news: a new version, its download, or what went wrong. */
        private static boolean updateNews() {
            return !Updater.available.isEmpty() || Updater.progress >= 0;
        }

        /** "Updates": the new version, the download's progress, or what went wrong; otherwise the channel. */
        private String updateStatus() {
            int progress = Updater.progress;
            if (progress >= 0) {
                return progress >= 100 ? Ui.tr("Instalando…", "Installing…") : Ui.tr("Baixando ", "Downloading ") + progress + "%";
            }
            if (!Updater.available.isEmpty()) {
                return !Updater.error.isEmpty() ? Ui.tr("Não deu, tente de novo", "Didn't work, try again")
                        : Ui.tr("Versão " + Updater.available + " disponível", "Version " + Updater.available + " available",
                                "Versión " + Updater.available + " disponible");
            }
            return Prefs.betas(getContext()) ? Ui.tr("Estáveis e betas", "Stable and betas") : Ui.tr("Estáveis", "Stable");
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
                line = Ui.tr(count + " em alta", count + " popular", count + " populares") + " · " + Prefs.regionName(ctx) + (apps.isEmpty() ? "" : " · " + apps);
            } else if (count > 0) {
                line = Ui.tr(count + " títulos recentes", count + " recent titles", count + " títulos recientes") + (ver.isEmpty() ? "" : " · Jellyfin " + ver) + " · " + server;
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
        /** "TCL 55C735 · Android 12 · 5 factory apps off": the line under the report's title. */
        private String reportLine() {
            int off = TvReport.turnedOff(getContext());
            String apps = !freeActive() ? Ui.tr("tudo de fábrica", "all as it came")
                    : Ui.tr(off + (off == 1 ? " app de fábrica desligado" : " apps de fábrica desligados"), off + (off == 1 ? " factory app off" : " factory apps off"),
                    off + (off == 1 ? " app de fábrica desactivada" : " apps de fábrica desactivadas"));
            return tvName + " · Android " + android.os.Build.VERSION.RELEASE + " · " + apps;
        }

        private static String reportHow() {
            return Ui.tr("Aponte a câmera do celular para o código, de perto: ele abre no GitHub um relatório "
                    + "desta TV, já preenchido, para o Marquise aprender a marca dela. Para enviar, precisa de uma conta no GitHub.",
                    "Point your phone's camera at the code, up close: it opens a report of this TV on GitHub, already filled "
                    + "in, so Marquise can learn its brand. Sending it needs a GitHub account.");
        }

        private static String reportHolds() {
            return Ui.tr("Vai junto: marca, modelo, Android, o Início e a tela de descanso de fábrica, os apps que "
                    + "vieram na TV e os últimos erros, sem endereços nem chaves. Nada pessoal: nenhum app seu, conta, rede ou local.",
                    "It holds: brand, model, Android, the factory home screen and screensaver, the apps that came with "
                    + "the TV and the last errors, without addresses or keys. Nothing personal: none of your apps, accounts, "
                    + "network or location.");
        }

        private void drawReport(Canvas c) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("Esta TV", "This TV"), x, (LIST_TOP - 24) * k, heading);
            c.drawText(reportLine(), x, (LIST_TOP + 30) * k, notice);
            float q = 460 * k, top = (LIST_TOP + 80) * k;
            if (reportQr != null) {
                tmp.set(x, top, x + q, top + q);
                c.drawBitmap(reportQr, null, tmp, qrPaint);
            }
            float tx = x + q + 48 * k, width = LIST_R * k - tx - 10 * k, gap = 22 * k;
            float ty = drawWrapped(c, Ui.tr("Outra marca, ou algo não funcionou?", "Another brand, or something didn't work?"),
                    tx, top + 30 * k, width, rowLabel, 0xF2FFFFFF);
            ty = drawWrapped(c, reportHow(), tx, ty + rowLabel.getTextSize() + gap, width, notice, 0xB3FFFFFF);
            drawWrapped(c, reportHolds(), tx, ty + notice.getTextSize() * 1.45f + gap, width, notice, 0x80FFFFFF);
            // The last problems Marquise saw, under the code: what to say when something didn't work.
            float ey = top + q + 56 * k;
            c.drawText(Ui.tr("ÚLTIMOS ERROS", "LAST ERRORS"), x, ey, header);
            if (errorLines != null && errorLines.length == 0) {
                c.drawText(Ui.tr("Nenhum erro recente.", "No recent errors."), x, ey + 42 * k, notice);
            }
            for (int i = 0; errorLines != null && i < errorLines.length; i++) {
                c.drawText(errorLines[i], x, ey + (42 + 34 * i) * k, notice);
            }
        }

        /**
         * The link, its QR code and the last problems, read and made on a background thread (they read files and ask
         * the package manager). The QR is made again only when the link changed since it was last made, so what is
         * on screen under the code is what the code carries.
         */
        private void loadReport() {
            final Context c = getContext();
            new Thread(() -> {
                String url = TvReport.url(c);
                String[] lines = loadErrors();
                Bitmap qr = reportQr == null || !url.equals(reportUrl) ? TvReport.qr(url) : null;
                post(() -> {
                    if (qr != null) {
                        reportQr = qr;
                        reportUrl = url;
                    }
                    errorLines = lines;
                    a11yChanged();
                    invalidate();
                });
            }, "marquise-report").start();
        }

        /**
         * The last three problems, newest first, each on up to two lines the width of the code (made once, not per
         * frame); a second line is indented, and the end of a long one is cut with "…".
         */
        private String[] loadErrors() {
            java.util.List<AppLog.Entry> last = AppLog.recent(3);
            java.util.List<String> lines = new java.util.ArrayList<>();
            TextPaint p = new TextPaint(notice);
            float width = 460 * k;   // under the code, not under the text beside it
            for (int i = last.size() - 1; i >= 0; i--) {
                String text = ago(last.get(i).at) + " · " + last.get(i).text;
                int first = p.breakText(text, true, width, null);
                if (first >= text.length()) {
                    lines.add(text);
                    continue;
                }
                int cut = text.lastIndexOf(' ', first);
                if (cut <= 0) cut = first;
                lines.add(text.substring(0, cut));
                lines.add("  " + TextUtils.ellipsize(text.substring(cut).trim(), p, width - p.measureText("  "), TextUtils.TruncateAt.END));
            }
            return lines.toArray(new String[0]);
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

        private static String[][] homeOptions() {
            return new String[][] {{"Marquise", Ui.tr("Seus pôsteres do Jellyfin, sem anúncios", "Your Jellyfin posters, no ads")},
                    {"Original", Ui.tr("O Início de fábrica da TV", "The TV's factory home screen")}};
        }

        private void drawHomeChoice(Canvas c) {
            drawChoice(c, Ui.tr("Início", "Home Screen"), homeOptions(), freeActive() ? 0 : 1,
                    Ui.tr("A troca leva alguns segundos.", "Switching takes a few seconds."));
        }

        /** "Updates": install the new version (when there is one), then stable releases only or betas too. */
        private String[][] updateOptions() {
            String available = Updater.available, error = Updater.error;
            int progress = Updater.progress;
            boolean betas = Prefs.betas(getContext());
            if (updatesCache != null && available.equals(cachedAvailable) && error.equals(cachedError)
                    && progress == cachedProgress && betas == cachedBetas && Ui.lang.equals(cachedLang)) {
                return updatesCache;
            }
            cachedAvailable = available;
            cachedError = error;
            cachedProgress = progress;
            cachedBetas = betas;
            cachedLang = Ui.lang;
            updatesCache = buildUpdateOptions();
            return updatesCache;
        }

        private String[][] buildUpdateOptions() {
            String[] stable = {Ui.tr("Só versões estáveis", "Stable releases only"),
                    Ui.tr("As que todo mundo recebe", "The ones everyone gets")};
            String[] betas = {Ui.tr("Também versões beta", "Beta releases too"),
                    Ui.tr("As novidades antes, com chance de defeitos", "What's new sooner, with a chance of bugs")};
            if (Updater.available.isEmpty()) return new String[][] {stable, betas};
            String[] install = {Ui.tr("Instalar a versão ", "Install version ") + Updater.available, Updater.progress >= 0
                    ? updateStatus() : Ui.tr("Baixa e abre o instalador do Android", "Downloads it and opens Android's installer")};
            return new String[][] {install, stable, betas};
        }

        private void drawUpdates(Canvas c) {
            String[][] options = updateOptions();
            int first = options.length - 2;
            drawChoice(c, Ui.tr("Atualizações", "Updates"), options, first + (Prefs.betas(getContext()) ? 1 : 0),
                    Ui.tr("Ao voltar para só estáveis, a beta fica até sair a próxima versão estável.",
                            "Back on stable only, a beta stays until the next stable release is out."));
        }

        /** A list of choices under a heading, the current one checked, with a hint below. */
        private void drawChoice(Canvas c, String title, String[][] options, int current, String note) {
            c.drawText(title, LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            for (int i = 0; i < options.length; i++) {
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
            c.drawText(note, LIST_X * k + 30 * k, (LIST_TOP + 20 + options.length * (CHOICE_H + ROW_GAP) + 40) * k, hint);
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
            if (minutes < 60) return Ui.tr("há " + minutes + " min", minutes + " min ago", "hace " + minutes + " min");
            if (minutes < 48 * 60) return Ui.tr("há " + (minutes / 60) + " h", (minutes / 60) + " h ago", "hace " + (minutes / 60) + " h");
            return Ui.tr("há " + (minutes / 1440) + " dias", (minutes / 1440) + " days ago", "hace " + (minutes / 1440) + " días");
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
                    if (screen == APPS) {
                        scrollToFocus();
                        updateAppsTitle();
                    }
                    a11yMoved();
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && focus < rowCount() - 1) focus++;
                    if (screen == APPS) {
                        scrollToFocus();
                        updateAppsTitle();
                    }
                    a11yMoved();
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
                            a11yChanged();
                            if (screen == APPS && pickFor >= 0) {
                                appLoadToken++;   // a list still loading must not show on the buttons' screen
                                openKeys(pickFor);
                            } else if (screen == APPS || screen == KEYS) {
                                focus = screen == APPS ? FACTORY_ROW : KEYS_ROW;
                                screen = PREFS;
                            } else {
                                focus = screen == REPORT ? THIS_TV : screen == UPDATES ? UPDATES_ROW : screen == PREFS ? PREFS_ROW : 0;
                                screen = MAIN;
                            }
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
            a11yChanged();
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
            } else if (screen == KEYS) {
                openPick(focus);
                invalidate();
                return;
            } else if (screen == APPS && pickFor >= 0) {
                if (appItems == null || focus >= appItems.length) return;
                Prefs.setRemoteTarget(c, RemoteKeys.BUTTONS[pickFor][1], appItems[focus][0]);
                openKeys(pickFor);
            } else if (screen == APPS) {
                if (appItems != null && focus < appItems.length) {
                    String pkg = appItems[focus][0];
                    if (!appPicked.remove(pkg)) appPicked.add(pkg);
                    Prefs.setExtraDisable(c, appPicked);
                    updateAppsSummary();
                }
                invalidate();
                return;
            } else if (screen == PREFS) {
                nextPref(focus);
                invalidate();
                return;
            } else if (screen == UPDATES) {
                syncUpdatesFocus();
                int first = updateOptions().length - 2;
                if (focus < first) {
                    Updater.install(c);
                } else if ((focus == first + 1) != Prefs.betas(c)) {
                    Prefs.setBetas(c, focus == first + 1);
                    Updater.recheck(c);
                }
                screen = MAIN;
                focus = UPDATES_ROW;
            } else if (focus == 0) {
                if (busy()) return;
                screen = HOME_CHOICE;
                focus = freeActive() ? 0 : 1;
            } else if (focus == 1 || focus == 2) {
                activity.startActivity(new Intent(c, SetupActivity.class)
                        .putExtra(SetupActivity.EXTRA_STEP, focus == 1 ? SetupActivity.STEP_SOURCE : SetupActivity.STEP_CITY));
            } else if (focus == UPDATES_ROW) {
                screen = UPDATES;
                updatesHadInstall = !Updater.available.isEmpty();
                focus = Updater.available.isEmpty() ? (Prefs.betas(c) ? 1 : 0) : 0;
            } else if (focus == PREFS_ROW) {
                assistantOff = Prefs.assistantOff(c);
                appPicked.clear();
                appPicked.addAll(Prefs.extraDisable(c));
                updateAppsSummary();
                screen = PREFS;
                focus = 0;
            } else if (focus == THIS_TV) {
                screen = REPORT;
                loadReport();
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
