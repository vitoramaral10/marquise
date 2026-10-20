package br.dev.vitormelo.marquise;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen, styled after the Apple TV app:
 *  - at the top left, a greeting for the time of day and the tabs (Explore, Movies, Shows); at the top right, the time
 *    and the weather, then icon-only glass buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below;
 *  - a row of posters recently added to your Jellyfin server in the selected tab (Recs), rotating every half hour; the
 *    background is the first poster, heavily blurred (Backdrop); without one, a gradient;
 *  - at the bottom, the apps (tiles in each brand's color; the focused one grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, the weather for the next
 *    hours); the background lightens (darkens at night). ↑ or Back closes it;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Shelf like the tvOS 27 Dock: 5:3 icons (210 × 126) in each brand's color, 48 apart, SHELF_PAD below
    // L_PLATTER_TOP (room for the focused icon to grow 20%).
    private static final float SHELF_PAD = 40, TILE_GAP = 48;
    private static final float TILE_RADIUS = 22, TILE_MAX_W = 270;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.2f;
    // Bottom drawer (measured with it open; everything moves up by SHIFT).
    private static final float SHIFT = 560, ROW_TITLE = 518, ROW_TOP = 540, ROW_H = 126, CARDS_TOP = 708, CARDS_H = 300;
    // "All Apps" sheet.
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    private static final int SKY = 0, HERO = 1, STATUS = 2, TILES = 3, DRAWER = 4, SHEET = 5, TOAST = 6, MENU = 7;
    private static final int Z_SHELF = 0, Z_DRAWER = 1, Z_TOP = 2, Z_CARDS = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, tab bar, the row of posters and the shelf.
    private static final float L_TITLE_BASE = 130, L_TABBAR_TOP = 158, L_TABBAR_H = 68, L_CARDS_TOP = 262;
    private static final float L_BUTTON = 68, L_BUTTON_GAP = 20, L_BUTTON_CY = 103;
    private static final float L_PLATTER_TOP = 814, L_TILE_H = 126;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static final String[] TABS = {"Explorar", "Filmes", "Séries"};
    private static final String[] TABS_EN = {"Explore", "Movies", "Shows"};
    private static final String[] TAB_KIND = {null, "movie", "tv"};

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Recs recs;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Layer sky, heroLayer, status, tiles, drawerLayer, sheetLayer, toastLayer, menuLayer;
    private int zone = Z_SHELF;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 2) and icon-only buttons (3 to 5); the three cards
    private int tab, topIndex, cardIndex;
    private List<Recs.Pick> cards = new ArrayList<>();
    private final Spring[] topFocus = new Spring[6];
    private final RectF[] topRects = new RectF[6];
    /** Posters in the row (Recs.PER_TAB per tab). */
    private static final int CARDS = Recs.PER_TAB;
    private static final float L_POSTER_H = 400;
    private final Spring[] cardFocus = new Spring[CARDS];
    private final RectF[] cardRects = new RectF[CARDS];
    private long shownMinute;
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lTitle, lTab, lTabOn, lCardTitle, lCardMeta, lTip;
    private Shader lightGradient;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
        // Each brand's own color (top, bottom, logo mode: 0 as is, 1 white, 2 the "-dark" logo, 3 black).
        TILE_STYLE.put("jellyfin", new int[] {0xFFAA5CC3, 0xFF00A4DC, 1});
        TILE_STYLE.put("live", new int[] {0xFF3A7BEA, 0xFF1F4FB8, 1});
        TILE_STYLE.put("youtube", new int[] {0xFFFFFFFF, 0xFFEDEDF0, 2});
        TILE_STYLE.put("spotify", new int[] {0xFF1ED760, 0xFF17B24F, 3});
        TILE_STYLE.put("f1tv", new int[] {0xFFE10600, 0xFFAA0500, 1});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final android.graphics.PorterDuffColorFilter blackLogo =
            new android.graphics.PorterDuffColorFilter(0xFF000000, android.graphics.PorterDuff.Mode.SRC_IN);
    private int shelfIndex;

    // Background
    private long bgFadeAt;
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen;
    private int gridIndex;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private long lastNanos;
    private int w, h;
    private float k = 1;
    private boolean okDown, backDown, okLong;
    // Editing the row on the TV: hold OK on an app for its menu (move, remove, add from All Apps).
    private static final long HOLD_MS = 550;
    private boolean menuOpen;
    private String menuTitle = "";
    private String[] menuLabels = new String[0];
    private Runnable[] menuActions = new Runnable[0];
    private int menuIndex, movingIndex = -1;
    private Paint menuTitlePaint, menuItemPaint, menuPanel, menuDim;

    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp;
    private Paint sheetTitle;
    private TextPaint gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Drawing makes no objects (60 fps on a TV chip; garbage collection mid-animation drops frames): gradients are made
    // once on a unit square and placed with this matrix; how strong they look is the paint's alpha.
    private final android.graphics.Matrix unit = new android.graphics.Matrix();
    // Soft shadows, blurred once per resting size into an alpha bitmap, then only drawn (stretched, tinted): a blur per
    // frame made the GPU keep hundreds of scratch surfaces (~45 MB) and cost time on every focus animation.
    private final android.util.LongSparseArray<Bitmap> shadows = new android.util.LongSparseArray<>();
    private final Paint shadowBitmap = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Map<String, LinearGradient> tileShaders = new HashMap<>();
    private final LinearGradient tileSheen = new LinearGradient(0, 0, 0, 0.5f, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
    private final LinearGradient cardSheen = new LinearGradient(0, 0, 0.5f, 1, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
    private final LinearGradient cardRim = new LinearGradient(0, 0, 0, 1, 0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
    private final LinearGradient gridSheen = new LinearGradient(0, 0, 0, 0.5f, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
    private TextPaint drawerLabelText;
    private final String[] cardTitles = new String[Recs.PER_TAB], cardBadges = new String[Recs.PER_TAB];
    // The focused poster shows its whole title (up to two lines), laid out once with the others.
    private final android.text.StaticLayout[] cardTitleLayouts = new android.text.StaticLayout[Recs.PER_TAB];
    private TextPaint cardTitlePaint;
    private int bgCard;                       // which poster the background shows (follows the focus)
    private static final long TAB_FADE_MS = 220;
    private long tabFadeAt;                   // switching tabs: the new posters fade in and rise a little
    private float cardsAlpha = 1;
    private final LinearGradient velvet = new LinearGradient(0, 0, 0, 1, 0xFF7A1830, 0xFF280E2E, Shader.TileMode.CLAMP);
    private Paint emptyTitle, emptyBody, bulb;
    private final Path clip = new Path();
    private final RectF tmp = new RectF(), tmp2 = new RectF(), cell = new RectF(), screen = new RectF();
    private final Map<String, Integer> artColors = new HashMap<>();   // app key → its art's background color

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (lTitle == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case STATUS: drawLightButtons(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                case MENU: drawMenu(c); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reload(); }
        @Override public void onInputAdded(String inputId) { reload(); }
        @Override public void onInputRemoved(String inputId) { reload(); }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            recs.refreshIfStale();
            checkNotices();
            updatePalette();
            checkNight(true);
            updatePicks();      // the posters rotate every half hour
            long minute = System.currentTimeMillis() / 60_000L;
            if (minute != shownMinute) {    // the clock at the top
                shownMinute = minute;
                heroDirty = true;
            }
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        click = new Click(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        menuLayer = new Layer(a, MENU);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        for (View v : hardwareLayers()) v.setLayerType(LAYER_TYPE_HARDWARE, null);
        for (View v : new View[] {sky, heroLayer, status, tiles, drawerLayer, sheetLayer, toastLayer, menuLayer}) addView(v);
        menuLayer.setVisibility(INVISIBLE);
        drawerLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < 6; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < CARDS; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            cardRects[i] = new RectF();
        }
        makeLightButtons();
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setDrawerItems(drawerList(a));
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (trimmed) {   // back from trim(): layers on again, everything redrawn
            trimmed = false;
            for (View v : hardwareLayers()) v.setLayerType(LAYER_TYPE_HARDWARE, null);
            heroDirty = statusDirty = tilesDirty = drawerDirty = gridDirty = true;
        }
        if (running) return;
        running = true;
        checkNight(false);
        lastNanos = 0;
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        checkNotices();
        reload();
        updatePalette();
        updatePicks();
        if (sheetOpen) closeSheet();
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        wake();
    }

    // The frame loop runs only while something moves; still, it checks a few times a second for changes made
    // elsewhere (new posters, the weather, the clock), and any key wakes it at once.
    private static final long IDLE_CHECK_MS = 250;
    private boolean framePending;
    private final Runnable idleCheck = this::wake;

    private void wake() {
        if (!running || framePending) return;
        framePending = true;
        handler.removeCallbacks(idleCheck);
        Choreographer.getInstance().postFrameCallback(this);
    }

    void pause() {
        if (!running) return;
        running = false;
        framePending = false;
        handler.removeCallbacks(idleCheck);
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        data.removeListener(this);
        recs.removeListener(this);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        click.release();
    }

    private boolean trimmed;

    /**
     * Home is hidden: the layers' GPU textures go (each is a full-screen texture) and the poster cache keeps only the
     * current tab's, so coming back shows them at once. resume() turns the layers back on and redraws.
     */
    void trim() {
        if (running || trimmed) return;
        trimmed = true;
        for (View v : hardwareLayers()) v.setLayerType(LAYER_TYPE_NONE, null);
        crispLogos.clear();
        recs.trim(cards);
    }

    private View[] hardwareLayers() {
        return new View[] {heroLayer, status, tiles, drawerLayer, sheetLayer};
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        zone = Z_SHELF;
        shelfIndex = 0;
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
        }
        if (tab != 0) selectTab(0);
        retarget(!animated);
        motionDirty = true;
    }

    /** The corner buttons, with their names in the current language. Live TV only when it isn't on the row already. */
    private void makeLightButtons() {
        lightButtons.clear();
        boolean liveOnRow = false;
        for (HomeApps.Item it : shelf) liveOnRow |= it.kind == HomeApps.LIVE_TV;
        if (!liveOnRow) lightButtons.add(HomeApps.action(HomeApps.LIVE_TV, "live", Ui.tr("TV aberta", "Live TV"), "live_tv"));
        lightButtons.add(HomeApps.action(HomeApps.INPUTS, "inputs", Ui.tr("Entradas HDMI", "HDMI Inputs"), "hdmi"));
        lightButtons.add(HomeApps.action(HomeApps.SETTINGS, "settings", Ui.tr("Configurações", "Settings"), "settings"));
    }

    private static String[] tabs() {
        return Ui.en ? TABS_EN : TABS;
    }

    @Override public void onData() {
        updatePalette();
        checkNight(true);
        heroDirty = statusDirty = drawerDirty = true;
    }

    @Override public void onRecs() {
        updatePicks();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(heroLayer, width, Math.round(790 * kk));
        measure(status, Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the grown tiles and their shadows): smaller layers are cheaper for the GPU to
        // composite on every frame of the drawer animation.
        measure(tiles, width, Math.round(270 * kk));
        measure(drawerLayer, width, Math.round(600 * kk));
        measure(sheetLayer, width, height);
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        measure(menuLayer, width, height);
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(status, width - status.getMeasuredWidth(), 0);
        place(tiles, 0, Math.round(790 * kk));
        place(drawerLayer, 0, Math.round(470 * kk));
        sheetLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
        menuLayer.layout(0, 0, width, height);
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        makePaints();
        gridLabels = null;
        layoutShelf();
        layoutDrawer();
        layoutLight();
        updatePicks();
        motionDirty = true;
    }

    private void makePaints() {
        lTitle = Ui.text(Ui.displaySemibold, 76 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(Ui.medium, 30 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 30 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 31 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lightGradient = null;
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        shadowDeep.setMaskFilter(new BlurMaskFilter(30 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        framePending = false;
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        boolean topMoving = step(topFocus, dt), cardsMoving = step(cardFocus, dt);
        if (topMoving) status.invalidate();
        boolean heroMoving = topMoving | cardsMoving;
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean tabFading = tickTabFade(up);
        boolean nightMoving = tickNight(up);

        if (drawerMoving || sheetMoving || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty) heroLayer.invalidate();
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (drawerMoving || bgFading) sky.invalidate();
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = false;
        boolean moving = tilesMoving | heroMoving | drawerItemsMoving | gridMoving | drawerMoving | sheetMoving | toastFading
                | bgFading | nightMoving | tabFading;
        if (moving) {
            wake();
        } else {
            lastNanos = 0;   // the next animation starts from a normal frame step, not the time spent idle
            handler.postDelayed(idleCheck, IDLE_CHECK_MS);
        }
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value);
        float home = 1 - s, top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        float lift = -d * shift;
        show(heroLayer, top, lift);
        show(status, top, lift);
        show(tiles, home, lift);
        float da = home * smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(s > 0.001f || sheetOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations and background

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (lTitle == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private void neutralGlass() {
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(0xFF4A4A4F, 0xFFC7C7CC));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lTip.setColor(tone(L_INK, WHITE));
    }

    /** The new tab's posters fading in: cardsAlpha from 0 to 1 (eased), redrawing the top layer meanwhile. */
    private boolean tickTabFade(long up) {
        if (tabFadeAt == 0) return false;
        float t = Math.min(1f, (up - tabFadeAt) / (float) TAB_FADE_MS);
        cardsAlpha = 1 - (1 - t) * (1 - t);
        heroDirty = true;
        if (t >= 1f) {
            tabFadeAt = 0;
            cardsAlpha = 1;
            return false;
        }
        return true;
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            int[] from = frostOf(bgFrom), to = frostOf(bgTo);
            for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
            scene.setFrost(frostMix);
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private static float fadeEase(long start, long up) {
        float t = Ui.clamp((up - start) / (float) FADE_MS);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private static android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        float keep = 1 - amount;
        return new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Shelf

    private void setShelf(List<HomeApps.Item> list) {
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        int buttons = lightButtons.size();
        makeLightButtons();
        if (lightButtons.size() != buttons) {   // Live TV moved between the row and the corner
            topIndex = Math.min(topIndex, 2 + lightButtons.size());
            layoutLight();
            statusDirty = heroDirty = true;
        }
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
    }

    private void layoutShelf() {
        int n = shelf.size();
        float pad = SHELF_PAD * k, gap = TILE_GAP * k, th = L_TILE_H * k;
        float full = (CONTENT_R - MARGIN) * k;
        float tw = Math.min(TILE_MAX_W * k, (full - Math.max(0, n - 1) * gap) / Math.max(1, n));
        float width = n * tw + Math.max(0, n - 1) * gap, left = (w - width) / 2f;
        float top = L_PLATTER_TOP * k;
        float x = left, y = top + pad;
        for (int i = 0; i < n; i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            x += tw + gap;
        }
        crispLogos.clear();
    }

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                drawShelfTile(c, i);
            }
        }
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f) * k);
        softShadow(c, bw, bh, r0, 30 * k, tmp, Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc));
        c.restore();
        int[] style = TILE_STYLE.get(it.key);
        if (it.logo != null && style != null) {
            c.drawRoundRect(cell, r, r, placed(tileFill, tileShader(it.key, style), cell, 1));
            if (fc > 0.01f) c.drawRoundRect(cell, r, r, placed(tileFill, tileSheen, cell, fc));
            tileFill.setShader(null);
            tileFill.setAlpha(255);
            drawLogo(c, it, cell, s, style[2]);
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
        } else if (it.tile == null && it.symbol != null) {
            // A shortcut on the shelf (Live TV): its symbol and name, side by side, on its color (or the dark tile).
            if (style != null) {
                placed(tileFill, tileShader(it.key, style), cell, 1);
            } else {
                tileFill.setShader(null);
                tileFill.setColor(0xFF161618);
            }
            c.drawRoundRect(cell, r, r, tileFill);
            tileFill.setShader(null);
            tileFill.setAlpha(255);
            drawerLabel.setColor(0xF2FFFFFF);
            drawerLabel.setTextSize(32 * k * s);
            float icon = 40 * k * s, gap = 14 * k * s, wide = drawerLabel.measureText(it.label);
            // A narrow tile (a full row): symbol and name shrink together to fit.
            float room = cell.width() - 36 * k * s, fit = Math.min(1f, room / (icon + gap + wide));
            if (fit < 1f) {
                icon *= fit;
                gap *= fit;
                drawerLabel.setTextSize(32 * k * s * fit);
                wide = drawerLabel.measureText(it.label);
            }
            float left = cx - (icon + gap + wide) / 2;
            Symbols.draw(getContext(), c, it.symbol, left + icon / 2, cy, icon, 0xF2FFFFFF);
            drawerLabel.setTextAlign(Paint.Align.LEFT);
            c.drawText(it.label, left + icon + gap, cy + Ui.halfCap(drawerLabel), drawerLabel);
            drawerLabel.setTextAlign(Paint.Align.CENTER);
            drawerLabel.setTextSize(23 * k);
        } else {
            clip.reset();
            clip.addRoundRect(cell, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            if (it.tile != null) {
                // The app's 16:9 art, whole and unsqueezed: fitted inside the tile (a full row makes tiles narrower),
                // with the art's own background color around it.
                tileFill.setShader(null);
                tileFill.setColor(artBackground(it));
                c.drawRect(cell, tileFill);
                float fit = Math.min(cell.width() / it.tile.getWidth(), cell.height() / it.tile.getHeight());
                float dw = it.tile.getWidth() * fit, dh = it.tile.getHeight() * fit;
                tmp.set(cell.centerX() - dw / 2, cell.centerY() - dh / 2, cell.centerX() + dw / 2, cell.centerY() + dh / 2);
                c.drawBitmap(it.tile, null, tmp, bitmapPaint);
            }
            c.restore();
        }
        if (i == movingIndex) {   // being moved: ‹ › above it, clear of the neighbors
            float a = 40 * k, ay = cell.top - 34 * k, spread = 30 * k, mid = cell.centerX();
            Symbols.draw(getContext(), c, "chevron_right", mid + spread, ay, a, 0xFFFFFFFF);
            c.save();
            c.scale(-1, 1, mid - spread, ay);
            Symbols.draw(getContext(), c, "chevron_right", mid - spread, ay, a, 0xFFFFFFFF);
            c.restore();
        }
    }

    /** The color at the art's top-left corner (its background), read once; black if the art can't be read. */
    private int artBackground(HomeApps.Item it) {
        Integer known = artColors.get(it.key);
        if (known != null) return known;
        int color = 0xFF000000;
        try {
            Bitmap b = it.tile.getConfig() == Bitmap.Config.HARDWARE ? it.tile.copy(Bitmap.Config.ARGB_8888, false) : it.tile;
            color = 0xFF000000 | b.getPixel(Math.min(4, b.getWidth() - 1), Math.min(4, b.getHeight() - 1));
            if (b != it.tile) b.recycle();
        } catch (RuntimeException ignored) {
        }
        artColors.put(it.key, color);
        return color;
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size: when focused it is drawn pixel for
     * pixel, without resampling.
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null);
        if (logo == null) return;
        float scale = s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : mode == 3 ? blackLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width());
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        // A share of the tile's area (so a full row of 8 still gets legible logos), never past 80% × 58% of it.
        float scale = (float) Math.sqrt(bw * bh * 0.12f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.8f / lw, bh * 0.58f / lh));
        Bitmap out = downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    /** Halves the size while it can (a 2×2 average, no aliasing), then finishes at the exact size. */
    private static Bitmap downscale(Bitmap src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap b = src;
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != src) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != src && b != out) b.recycle();
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        list.addAll(HomeApps.drawerShortcuts());
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void layoutDrawer() {
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = drawerInputs > 0 && drawerInputs < n ? 2 : 1;
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        float x = left, top = ROW_TOP * k;
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k);
            x += tw + gap;
        }
        float cardsTop = CARDS_TOP * k;
        weatherCard.set(left, cardsTop, right, cardsTop + CARDS_H * k);
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_DRAWER;
        drawerIndex = shelf.isEmpty() ? 0 : nearest(drawerRects, tileRects[shelfIndex].centerX());
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);   // the drawer goes away: its focus doesn't need to animate
        if (!shelf.isEmpty() && drawerIndex < drawerRects.length) shelfIndex = nearest(tileRects, drawerRects[drawerIndex].centerX());
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        float dk = dark();
        if (dk < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            softShadow(c, r.width(), r.height(), radius, 26 * k, tmp, Ui.alpha(shadowColor, 1 - dk));
            scene.glass(c, r, radius, 0xFFFFFFFF, lightTint, 0, 0, 0.02f, 1, 1 - dk);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        float titleBase = ROW_TITLE * k;
        if (drawerInputs > 0) c.drawText(Ui.tr("Entradas", "Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle);
        if (drawerInputs < drawerItems.size()) c.drawText(Ui.tr("Atalhos", "Shortcuts"), drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        drawWeatherCard(c, weatherCard);
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            softShadow(c, base.width(), base.height(), 20 * k, 26 * k, tmp, Ui.alpha(0xFF000000, 0.45f * f));
        }
        if (f < 0.99f) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink);
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(23 * k * s);
        float room = cell.width() - 16 * k, wide = drawerLabel.measureText(it.label);
        // A long name ("Home Settings", an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.setTextSize(Math.max(18 * k * s, 23 * k * s * room / wide));
        if (drawerLabelText == null) drawerLabelText = new TextPaint();
        drawerLabelText.set(drawerLabel);
        String label = TextUtils.ellipsize(it.label, drawerLabelText, room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(23 * k);
    }

    /** Weather for your location: now (large), the next 6 hours, and sunrise and sunset. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        float x = r.left + 34 * k;
        String city = Prefs.city(getContext());
        c.drawText(city.isEmpty() ? Ui.tr("Clima", "Weather") : city, x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("Sem dados do clima agora.", "No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f));
        String now = HomeData.degrees(wx.temp);
        c.drawText(now, x + sw + 18 * k - Ui.leftBearing(bigTemp, now), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(Ui.en ? "H:" + HomeData.degrees(wx.max) + "   L:" + HomeData.degrees(wx.min)
                : "Máx. " + HomeData.degrees(wx.max) + "   Mín. " + HomeData.degrees(wx.min), x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k, hw = (r.right - 30 * k - hx) / 6;
        for (int i = 0; i < wx.hours.size() && i < 6; i++) {
            float hc = hx + hw * (i + 0.5f);
            c.drawText(HomeData.hour(wx.hours.get(i)), hc, r.top + 96 * k, hourPaint);
            Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
            c.drawText(HomeData.degrees(wx.hourTemp.get(i)), hc, r.top + 196 * k, hourTemp);
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", Ui.tr("Nascer ", "Sunrise ") + Ui.time(wx.sunrise), sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", Ui.tr("Pôr do sol ", "Sunset ") + Ui.time(wx.sunset), sx, base);
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the top).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        boolean topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        boolean heroChanged = aim(cardFocus, !sheetOpen && zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || topChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    private void openSheet() {
        sheetOpen = true;
        gridIndex = 0;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
    }

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
    }

    private void drawSheet(Canvas c) {
        c.drawColor(0xA8000000);
        float shift = scroll.value * GRID_ROW * k;
        c.drawText(Ui.tr("Todos os Apps", "All Apps"), w / 2f, 124 * k - shift, sheetTitle);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            RectF base = new RectF(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = gridFocus[i].value;
            drawCard(c, others.get(i).tile, base, GRID_RADIUS * k, f);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            gridLabel.setColor(blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], base.centerX(), base.bottom + (40 + 6 * f) * k, gridLabel);
        }
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, Bitmap image, RectF base, float radius, float f) {
        float s = 1 + 0.08f * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            softShadow(c, base.width(), base.height(), radius, 26 * k, tmp, Ui.alpha(0xFF000000, 0.55f * fc));
        }
        clip.reset();
        clip.addRoundRect(cell, r, r, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        if (image != null) c.drawBitmap(image, null, cell, bitmapPaint);
        if (f > 0.01f) c.drawRect(cell, placed(sheen, gridSheen, cell, fc));
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRect(cell, veil);
        }
        c.restore();
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = HomeApps.shelf(c, true);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = HomeApps.others(c);
            handler.post(() -> {
                setShelf(s);
                setDrawerItems(d);
                if (!sheetOpen) setOthers(o);
            });
        });
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        wake();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) arrow(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) arrow(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) arrow(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) arrow(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                // Held (the key repeats while pressed): the focused app's menu, and the release does nothing more.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okLong = false;
                    } else if (okDown && !okLong && (e.isLongPress() || e.getEventTime() - e.getDownTime() >= HOLD_MS)) {
                        okLong = true;
                        hold();
                    }
                } else {
                    if (okDown && !okLong && !e.isCanceled()) {
                        if (menuOpen) chooseMenu();
                        else if (movingIndex >= 0) finishMove();
                        else activate();
                    }
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        if (menuOpen) closeMenu();
                        else if (movingIndex >= 0) finishMove();
                        else back();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void arrow(int dx, int dy) {
        if (menuOpen) {
            int next = Math.max(0, Math.min(menuLabels.length - 1, menuIndex + dy));
            if (dy == 0 || next == menuIndex) return;
            menuIndex = next;
            menuLayer.invalidate();
            click.move();
        } else if (movingIndex >= 0) {
            if (dx != 0) moveShelfItem(dx);
        } else {
            move(dx, dy);
        }
    }

    /** OK held: the menu for the focused app on the row, or for the focused app in All Apps. */
    private void hold() {
        if (menuOpen || movingIndex >= 0) return;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        final HomeApps.Item it;
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            labels.add(Ui.tr("Adicionar ao Início", "Add to Home"));
            actions.add(() -> addToShelf(it));
        } else if (zone == Z_SHELF && !shelf.isEmpty()) {
            it = shelf.get(shelfIndex);
            labels.add(Ui.tr("Mover", "Move"));
            actions.add(this::startMove);
            labels.add(Ui.tr("Remover do Início", "Remove from Home"));
            actions.add(() -> removeFromShelf(it));
            labels.add(Ui.tr("Restaurar fileira padrão", "Restore the default row"));
            actions.add(this::resetShelf);
        } else {
            return;
        }
        if (it.kind == HomeApps.APP && HomeApps.canUninstall(getContext(), it.pkg)) {
            labels.add(Ui.tr("Desinstalar", "Uninstall"));
            actions.add(() -> HomeApps.uninstall(activity, it.pkg));
        }
        labels.add(Ui.tr("Cancelar", "Cancel"));
        actions.add(null);
        openMenu(it.label, labels.toArray(new String[0]), actions.toArray(new Runnable[0]));
    }

    private void openMenu(String title, String[] labels, Runnable[] actions) {
        menuTitle = title;
        menuLabels = labels;
        menuActions = actions;
        menuIndex = 0;
        menuOpen = true;
        menuLayer.setVisibility(VISIBLE);
        menuLayer.invalidate();
        click.open();
    }

    private void closeMenu() {
        menuOpen = false;
        menuLayer.setVisibility(INVISIBLE);
        click.move();
    }

    private void chooseMenu() {
        Runnable action = menuActions[menuIndex];
        closeMenu();
        if (action != null) action.run();
    }

    private List<String> shelfKeys() {
        List<String> keys = new ArrayList<>();
        for (HomeApps.Item it : shelf) keys.add(it.key);
        return keys;
    }

    private void startMove() {
        movingIndex = shelfIndex;
        tilesDirty = true;
        showToast(Ui.tr("← → para mover · OK para concluir", "← → to move · OK when done"));
    }

    private void moveShelfItem(int dx) {
        int to = movingIndex + dx;
        if (to < 0 || to >= shelf.size()) return;
        java.util.Collections.swap(shelf, movingIndex, to);
        movingIndex = to;
        shelfIndex = to;
        crispLogos.clear();
        tilesDirty = true;
        retarget(false);
        click.move();
    }

    private void finishMove() {
        movingIndex = -1;
        HomeApps.saveShelfOrder(getContext(), shelfKeys());
        tilesDirty = true;
        click.open();
        showToast(Ui.tr("Fileira salva.", "Row saved."));
    }

    private void removeFromShelf(HomeApps.Item it) {
        List<String> keys = shelfKeys();
        keys.remove(it.key);
        HomeApps.saveShelfOrder(getContext(), keys);
        reload();
        showToast(Ui.tr(it.label + " saiu do Início. Volta por Todos os Apps.", it.label + " left Home. Add it back from All Apps."));
    }

    private void addToShelf(HomeApps.Item it) {
        if (shelf.size() >= HomeApps.MAX_SHELF) {
            showToast(Ui.tr("A fileira está cheia: remova um app antes.", "The row is full: remove an app first."));
            return;
        }
        List<String> keys = HomeApps.shelfOrder(getContext());
        keys.add(it.key);
        HomeApps.saveShelfOrder(getContext(), keys);
        closeSheet();
        reload();
        showToast(Ui.tr(it.label + " está no Início.", it.label + " is on Home."));
    }

    private void resetShelf() {
        HomeApps.resetShelfOrder(getContext());
        reload();
        showToast(Ui.tr("Fileira padrão restaurada.", "Default row restored."));
    }

    /** The app's menu: the screen dims and a light card in the middle lists the choices (the focused one dark). */
    private void drawMenu(Canvas c) {
        if (!menuOpen) return;
        if (menuPanel == null) {
            menuDim = new Paint();
            menuDim.setColor(0x8C000000);
            menuPanel = new Paint(Paint.ANTI_ALIAS_FLAG);
            menuTitlePaint = Ui.text(Ui.semibold, 30 * k, 0x991C1C1E);
            menuItemPaint = Ui.text(Ui.medium, 30 * k, 0xFF1C1C1E);
        }
        c.drawRect(0, 0, w, h, menuDim);
        float pw = 640 * k, row = 74 * k, pad = 22 * k, head = 84 * k;
        float ph = head + menuLabels.length * row + pad;
        cell.set((w - pw) / 2, (h - ph) / 2, (w + pw) / 2, (h + ph) / 2);
        tmp.set(cell);
        tmp.offset(0, 18 * k);
        shadowDeep.setColor(0x66000000);
        c.drawRoundRect(tmp, 30 * k, 30 * k, shadowDeep);
        menuPanel.setColor(0xFFF5F5F7);
        c.drawRoundRect(cell, 30 * k, 30 * k, menuPanel);
        String title = TextUtils.ellipsize(menuTitle, new TextPaint(menuTitlePaint), pw - 2 * pad - 20 * k, TextUtils.TruncateAt.END).toString();
        c.drawText(title, cell.left + pad + 18 * k, cell.top + 54 * k, menuTitlePaint);
        for (int i = 0; i < menuLabels.length; i++) {
            float top = cell.top + head + i * row;
            tmp.set(cell.left + pad, top, cell.right - pad, top + row - 8 * k);
            boolean on = i == menuIndex;
            if (on) {
                menuPanel.setColor(0xFF1C1C1E);
                c.drawRoundRect(tmp, 18 * k, 18 * k, menuPanel);
            }
            menuItemPaint.setColor(on ? 0xFFFFFFFF : 0xFF1C1C1E);
            c.drawText(menuLabels[i], tmp.left + 18 * k, tmp.centerY() + Ui.halfCap(menuItemPaint), menuItemPaint);
        }
    }

    private void back() {
        if (sheetOpen) {
            closeSheet();
        } else if (zone == Z_DRAWER) {
            closeDrawer();
            retarget(false);
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    // Only an empty shelf gets here (moveLight handles the rest): up to the tabs.
                    zone = Z_TOP;
                    topIndex = tab;
                } else {
                    if (drawerItems.isEmpty()) return;
                    openDrawer();
                }
                break;
            default:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    closeDrawer();
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        HomeApps.Item it;
        RectF r;
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else if (zone == Z_TOP) {
            if (topIndex < 3) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - 3);
            r = topRects[topIndex];
        } else if (zone == Z_CARDS) {
            if (cards.isEmpty()) {   // "Connect your Jellyfin": the setup, on its server step
                activity.startActivity(new android.content.Intent(activity, SetupActivity.class)
                        .putExtra(SetupActivity.EXTRA_STEP, SetupActivity.STEP_SERVER));
                return;
            }
            Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p));
            it.itemId = p.itemId;
            r = cardRects[cardIndex];
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        click.open();
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            for (int i = 0; i < drawerItems.size(); i++) {
                if (drawerItems.get(i).kind == HomeApps.INPUT) {
                    drawerIndex = i;
                    break;
                }
            }
            retarget(false);
            return;
        }
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.tr("Não deu para abrir ", "Couldn't open ") + it.label + ".");
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private void layoutLight() {
        if (lTab == null) return;
        // Text tabs; the focus pill extends 26 px past the text (the first tab's text starts at the margin).
        float pad = 26 * k, tabTop = L_TABBAR_TOP * k, tabBottom = (L_TABBAR_TOP + L_TABBAR_H) * k;
        float x = MARGIN * k - pad;
        for (int i = 0; i < 3; i++) {
            float tw = Math.max(lTabOn.measureText(tabs()[i]), lTab.measureText(tabs()[i]));
            topRects[i].set(x, tabTop, x + tw + 2 * pad, tabBottom);
            x = topRects[i].right + 4 * k;
        }
        float b = L_BUTTON * k, right = CONTENT_R * k, cy = L_BUTTON_CY * k;
        for (int i = 2; i >= 0; i--) {
            if (i >= lightButtons.size()) {
                topRects[3 + i].setEmpty();
                continue;
            }
            topRects[3 + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + L_BUTTON_GAP * k;
        }
        // Posters (2:3) across the content width, evenly spaced.
        float top = L_CARDS_TOP * k, ch = L_POSTER_H * k, cw = ch * 2 / 3, left = MARGIN * k;
        float g = ((CONTENT_R - MARGIN) * k - CARDS * cw) / (CARDS - 1);
        for (int i = 0; i < CARDS; i++) {
            float l = left + i * (cw + g);
            cardRects[i].set(l, top, l + cw, top + ch);
        }
        fitCardText();
        heroDirty = statusDirty = true;
    }

    /** The three cards of the current tab (Explore, Movies, Shows) and the background taken from the first one. */
    private void updateCards() {
        List<Recs.Pick> list = recs.now(TAB_KIND[tab]);
        if (!samePicks(list, cards)) {
            cards = list;
            fitCardText();
            cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
            if (zone == Z_CARDS && list.isEmpty() && Prefs.jellyfinReady(getContext())) {
                zone = Z_SHELF;
                retarget(false);
            }
        }
        for (Recs.Pick p : recs.wanted()) recs.backdrop(p);   // all three tabs preloaded: switching tabs is instant
        Backdrop b = cards.isEmpty() ? null : recs.backdrop(cards.get(Math.min(bgCard, cards.size() - 1)));
        if (cards.isEmpty() || b != null) showBackdrop(b);
        heroDirty = true;
    }

    private void selectTab(int index) {
        if (index == tab) return;
        tab = index;
        bgCard = 0;
        tabFadeAt = SystemClock.uptimeMillis();
        cardsAlpha = 0;
        updateCards();
        heroDirty = true;
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // Drawer open: the background lightens (by day) or darkens (at night), as a filter on the drawing itself.
        android.graphics.ColorFilter v = veil(tone(0.32f, 0.42f) * Ui.clamp(drawer.value), blend(0xFFFFFFFF, 0xFF000000, night));
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = new LinearGradient(0, 0, 0, h, 0xFFF5F5F7, 0xFFE2E2E7, Shader.TileMode.CLAMP);
            if (nightGradient == null) nightGradient = new LinearGradient(0, 0, 0, h, 0xFF1C1C1E, 0xFF0B0B0D, Shader.TileMode.CLAMP);
            fill.setColorFilter(v);
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    private void drawLightTop(Canvas c) {
        applyTone();
        String hello = greeting();
        c.drawText(hello, MARGIN * k - Ui.leftBearing(lTitle, hello), L_TITLE_BASE * k, lTitle);
        drawClockWeather(c);
        // Text tabs (the selected one in bold, in the main text color); with focus on the tabs, the selected one gets
        // the white pill with a shadow and grows a little (the tvOS focus).
        float focus = Ui.clamp(topFocus[tab].value);
        for (int i = 0; i < 3; i++) {
            RectF r = topRects[i];
            if (i == tab && focus > 0.01f) {
                float s = 1 + 0.05f * focus;
                cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                        r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
                float rr = cell.height() / 2;
                if (focus > 0.01f) {
                    tmp.set(cell);
                    tmp.offset(0, 8 * k * focus);
                    softShadow(c, r.width(), r.height(), r.height() / 2, 26 * k, tmp, Ui.alpha(0xFF000000, tone(0.22f, 0.4f) * focus));
                }
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, focus));
                c.drawRoundRect(cell, rr, rr, fill);
            }
            Paint p = i == tab ? lTabOn : lTab;
            if (i == tab) lTabOn.setColor(blend(tone(L_INK, WHITE), L_INK, focus));   // always dark on the white pill
            c.drawText(tabs()[i], r.centerX() - p.measureText(tabs()[i]) / 2, r.centerY() + Ui.halfCap(p), p);
        }
        if (cards.isEmpty()) {
            drawEmptyCard(c);
            return;
        }
        // Switching tabs: for a moment, the posters are drawn into a layer that fades in and rises.
        int save = -1;
        if (cardsAlpha < 0.999f) {
            save = c.saveLayerAlpha(0, (L_CARDS_TOP - 60) * k, w, (L_CARDS_TOP + L_POSTER_H + 160) * k, Math.round(255 * cardsAlpha));
            c.translate(0, (1 - cardsAlpha) * 18 * k);
        }
        int top = -1;
        for (int i = 0; i < CARDS; i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
        for (int i = 0; i < CARDS; i++) if (i != top) drawLightCard(c, i);
        if (top >= 0) drawLightCard(c, top);
        if (save >= 0) c.restoreToCount(save);
    }

    /**
     * Recommendation card: the title's photo (already at the focused card size, so it stays sharp), rounded corners and
     * a soft shadow; when focused, it grows 5%, rises and lights up its border. Below it, the title (and on the large
     * card, genre · runtime · service).
     */
    private void drawLightCard(Canvas c, int i) {
        RectF base = cardRects[i];
        Recs.Pick p = i < cards.size() ? cards.get(i) : null;
        if (p == null && !cards.isEmpty()) return;   // a tab with fewer titles: no empty placeholders
        float v = cardFocus[i].value, f = Ui.clamp(v), s = 1 + 0.08f * v;
        float bw = base.width(), bh = base.height(), r0 = 18 * k;
        float cx = base.centerX(), cy = base.centerY() - 10 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        softShadow(c, bw, bh, r0, 30 * k, tmp, Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        c.restore();
        // The poster comes with its rounded corners already cut (Backdrop): no clipping on every frame.
        Backdrop b = p == null ? null : recs.backdrop(p);
        Bitmap img = b == null ? null : b.tall;
        if (img != null) {
            c.drawBitmap(img, null, cell, bitmapPaint);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        }
        if (f > 0.01f) c.drawRoundRect(cell, r, r, placed(sheen, cardSheen, cell, f));
        if (f > 0.01f) {
            rim.setStrokeWidth(3.5f * k);
            c.drawRoundRect(cell, r, r, placed(rim, cardRim, cell, f));
            rim.setStrokeWidth(2.5f * k);
        }
        if (p == null || cardTitles[i] == null) return;
        float below = 0;
        android.text.StaticLayout whole = cardTitleLayouts[i];
        if (f > 0.5f && whole != null && cardTitlePaint != null) {
            // Focused: the whole title, on two lines if it needs them; the line below moves down to make room.
            cardTitlePaint.setColor(lCardTitle.getColor());
            float baseline = base.bottom + 46 * k;
            c.save();
            c.translate(base.left, baseline - whole.getLineBaseline(0));
            whole.draw(c);
            c.restore();
            below = whole.getLineBaseline(whole.getLineCount() - 1) - whole.getLineBaseline(0);
        } else {
            c.drawText(cardTitles[i], base.left, base.bottom + 46 * k, lCardTitle);
        }
        if (cardBadges[i] != null) c.drawText(cardBadges[i], base.left, base.bottom + 80 * k + below, lCardMeta);
    }

    /**
     * No posters: a marquee-style card in their place. Not connected yet, it's focusable and OK opens the setup;
     * connected, it says the titles are on their way.
     */
    private void drawEmptyCard(Canvas c) {
        if (emptyTitle == null) {
            emptyTitle = Ui.text(Ui.displaySemibold, 46 * k, 0xFFFFF4E2);
            emptyTitle.setTextAlign(Paint.Align.CENTER);
            emptyBody = Ui.text(Ui.regular, 27 * k, 0xCCFFF4E2);
            emptyBody.setTextAlign(Paint.Align.CENTER);
            bulb = new Paint(Paint.ANTI_ALIAS_FLAG);
            bulb.setColor(0xFFFFD68C);
        }
        boolean ready = Prefs.jellyfinReady(getContext());
        float v = cardFocus[0].value, f = Ui.clamp(v), s = 1 + 0.02f * v;
        float left = MARGIN * k, right = CONTENT_R * k, top = L_CARDS_TOP * k, bottom = top + L_POSTER_H * k;
        float cx = (left + right) / 2, cy = (top + bottom) / 2, w = (right - left) * s, h = (bottom - top) * s;
        cell.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        float r = 24 * k * s;
        tmp.set(cell);
        tmp.offset(0, (12 + 10 * f) * k);
        softShadow(c, right - left, bottom - top, 24 * k, 30 * k, tmp, Ui.alpha(0xFF000000, 0.3f + 0.2f * f));
        c.drawRoundRect(cell, r, r, placed(fill, velvet, cell, 1));
        fill.setShader(null);
        fill.setAlpha(255);
        // The marquee's bulbs along the top and bottom edges.
        float inset = 30 * k * s, step = 58 * k * s, br = 6 * k * s;
        int n = (int) ((cell.width() - 2 * inset) / step);
        float x0 = cell.centerX() - n * step / 2;
        for (int i = 0; i <= n; i++) {
            c.drawCircle(x0 + i * step, cell.top + inset, br, bulb);
            c.drawCircle(x0 + i * step, cell.bottom - inset, br, bulb);
        }
        if (f > 0.01f) c.drawRoundRect(cell, r, r, placed(rim, cardRim, cell, f));
        String title = ready ? Ui.tr("Buscando os títulos…", "Fetching titles…") : Ui.tr("Conecte seu Jellyfin", "Connect your Jellyfin");
        String body = ready ? Ui.tr("Os pôsteres do que chegou no seu servidor aparecem aqui.", "Posters of what just arrived on your server show up here.")
                : Ui.tr("Aperte OK para escolher o servidor e entrar.", "Press OK to pick your server and sign in.");
        c.drawText(title, cell.centerX(), cell.centerY() - 6 * k, emptyTitle);
        c.drawText(body, cell.centerX(), cell.centerY() + 46 * k, emptyBody);
    }

    /**
     * A soft shadow for a rounded rect whose resting size is baseW × baseH (corner baseRadius), drawn into `dst` (the
     * rect as it is now: grown, lifted) in `color`. The blur happens once per resting size.
     */
    private void softShadow(Canvas c, float baseW, float baseH, float baseRadius, float blur, RectF dst, int color) {
        long key = ((long) Math.round(baseW) << 40) | ((long) Math.round(baseH) << 24) | ((long) Math.round(baseRadius) << 12)
                | Math.round(blur);
        Bitmap b = shadows.get(key);
        float pad = blur * 1.5f;
        if (b == null) {
            b = Bitmap.createBitmap(Math.round(baseW + 2 * pad), Math.round(baseH + 2 * pad), Bitmap.Config.ALPHA_8);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setMaskFilter(new BlurMaskFilter(Math.max(0.5f, blur), BlurMaskFilter.Blur.NORMAL));
            new Canvas(b).drawRoundRect(new RectF(pad, pad, pad + baseW, pad + baseH), baseRadius, baseRadius, p);
            b.prepareToDraw();
            shadows.put(key, b);
        }
        float sx = dst.width() / baseW, sy = dst.height() / baseH;
        tmp2.set(dst.left - pad * sx, dst.top - pad * sy, dst.right + pad * sx, dst.bottom + pad * sy);
        shadowBitmap.setColor(color);
        c.drawBitmap(b, null, tmp2, shadowBitmap);
    }

    /** `shader` stretched over `r` (its gradient is drawn on a unit square), at `alpha` (0 to 1). */
    private Paint placed(Paint paint, Shader shader, RectF r, float alpha) {
        unit.setScale(r.width(), r.height());
        unit.postTranslate(r.left, r.top);
        shader.setLocalMatrix(unit);
        paint.setShader(shader);
        paint.setAlpha(Math.round(255 * Ui.clamp(alpha)));
        return paint;
    }

    /** The app's brand gradient (top to bottom), made once per app. */
    private LinearGradient tileShader(String key, int[] style) {
        LinearGradient g = tileShaders.get(key);
        if (g == null) {
            g = new LinearGradient(0, 0, 0, 1, style[0], style[1], Shader.TileMode.CLAMP);
            tileShaders.put(key, g);
        }
        return g;
    }

    /** The posters' titles and lines, cut to fit once (when the posters or the layout change), not on every frame. */
    private void fitCardText() {
        if (lCardTitle == null) return;
        TextPaint tp = new TextPaint(lCardTitle), mp = new TextPaint(lCardMeta);
        cardTitlePaint = new TextPaint(lCardTitle);
        for (int i = 0; i < cardTitles.length; i++) {
            Recs.Pick p = i < cards.size() ? cards.get(i) : null;
            float bw = cardRects[i].width();
            cardTitles[i] = p == null ? null : TextUtils.ellipsize(p.title, tp, bw, TextUtils.TruncateAt.END).toString();
            cardTitleLayouts[i] = p == null || cardTitles[i].equals(p.title) || bw < 1 ? null
                    : android.text.StaticLayout.Builder.obtain(p.title, 0, p.title.length(), cardTitlePaint, Math.round(bw))
                            .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build();
            cardBadges[i] = p == null || p.badge == null || p.badge.isEmpty() ? null
                    : TextUtils.ellipsize(p.badge, mp, bw, TextUtils.TruncateAt.END).toString();
        }
    }

    /** "Bom dia" / "Boa tarde" / "Boa noite", by the TV's clock. */
    private static String greeting() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if (hour >= 5 && hour < 12) return Ui.tr("Bom dia", "Good morning");
        if (hour >= 12 && hour < 18) return Ui.tr("Boa tarde", "Good afternoon");
        return Ui.tr("Boa noite", "Good evening");
    }

    /** At the top right, before the buttons: the time, then the weather symbol and temperature. */
    private void drawClockWeather(Canvas c) {
        float right = topRects[3].left - 40 * k, base = L_BUTTON_CY * k + Ui.halfCap(lTabOn);
        int ink = lTabOn.getColor();
        lTabOn.setColor(tone(L_INK, WHITE));
        HomeData.Weather wx = data.weather;
        if (!Double.isNaN(wx.temp)) {
            String temp = HomeData.degrees(wx.temp);
            c.drawText(temp, right - lTabOn.measureText(temp), base, lTabOn);
            right -= lTabOn.measureText(temp) + 12 * k;
            String symbol = Symbols.weather(wx.code, wx.isDay);
            float sz = 36 * k, sw = Symbols.width(getContext(), symbol, sz);
            Symbols.draw(getContext(), c, symbol, right - sw / 2, L_BUTTON_CY * k, sz, lTabOn.getColor());
            right -= sw + 34 * k;
        }
        String time = android.text.format.DateFormat.getTimeFormat(getContext()).format(new java.util.Date());
        c.drawText(time, right - lTabOn.measureText(time), base, lTabOn);
        lTabOn.setColor(ink);
    }

    /**
     * Live TV, HDMI Inputs and Settings: icon-only glass circles (light by day, smoked at night); the focused one turns
     * white with a dark icon (the tvOS focus) and its name appears below it.
     */
    private void drawLightButtons(Canvas c) {
        applyTone();
        for (int i = 0; i < lightButtons.size(); i++) {
            RectF r = topRects[3 + i];
            float f = Ui.clamp(topFocus[3 + i].value), s = 1 + 0.1f * f, rad = r.width() / 2 * s;
            cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad);
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            softShadow(c, r.width(), r.height(), r.width() / 2, 26 * k, tmp,
                    Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
            scene.liquid(c, cell, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                    tone(0.6f, 0.45f), 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawCircle(cell.centerX(), cell.centerY(), rad, fill);
            }
            Symbols.draw(getContext(), c, lightButtons.get(i).symbol, cell.centerX(), cell.centerY(), 27 * k * s,
                    blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
            if (f > 0.01f) {
                String label = lightButtons.get(i).label;
                float half = lTip.measureText(label) / 2;
                float x = Math.min(r.centerX(), CONTENT_R * k - half);
                lTip.setAlpha(Math.round(255 * f));
                c.drawText(label, x, r.bottom + (40 + 4 * f) * k, lTip);
            }
        }
    }

    /**
     * Moving up into the top row from x: on the right half, straight to the nearest corner button (no walking across
     * the tabs, which would switch them); on the left, the selected tab (as on Apple TV).
     */
    private int upFrom(float x) {
        if (x < w / 2f || lightButtons.isEmpty()) return tab;
        int best = 3;
        for (int i = 3; i < 3 + lightButtons.size(); i++) {
            if (Math.abs(topRects[i].centerX() - x) < Math.abs(topRects[best].centerX() - x)) best = i;
        }
        return best;
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:
                if (dy >= 0 || shelf.isEmpty()) return false;
                if (cards.isEmpty() && !Prefs.jellyfinReady(getContext())) {
                    zone = Z_CARDS;   // the "Connect your Jellyfin" card
                    cardIndex = 0;
                } else if (cards.isEmpty()) {
                    zone = Z_TOP;
                    topIndex = upFrom(tileRects[shelfIndex].centerX());
                } else {
                    zone = Z_CARDS;
                    cardIndex = nearest(cardRects, cards.size(), tileRects[shelfIndex].centerX());
                }
                break;
            case Z_CARDS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(cards.size() - 1, cardIndex + dx));
                    if (next == cardIndex) return true;
                    cardIndex = next;
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = upFrom(cardRects[cardIndex].centerX());
                } else {
                    zone = Z_SHELF;
                    if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, cardRects[cardIndex].centerX());
                }
                break;
            case Z_TOP:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(2 + lightButtons.size(), topIndex + dx));
                    if (next == topIndex) return true;
                    topIndex = next;
                    if (topIndex < 3) selectTab(topIndex);   // like Apple TV tabs: focusing a tab selects it
                } else if (dy > 0) {
                    if (topIndex >= 3) {
                        topIndex = tab;   // ↓ from the corner buttons: the tabs, right below them
                    } else if (cards.isEmpty()) {
                        zone = Z_SHELF;
                    } else {
                        zone = Z_CARDS;
                        cardIndex = nearest(cardRects, cards.size(), topRects[topIndex].centerX());
                    }
                } else if (topIndex < 3 && !lightButtons.isEmpty()) {
                    topIndex = 3;   // ↑ from the tabs: the corner buttons sit above them, at the top right
                } else {
                    return true;
                }
                break;
            default:
                return false;
        }
        if (zone == Z_CARDS && cardIndex < cards.size() && cardIndex != bgCard) {
            // The background follows the focused poster (as on Apple TV); its image is already prepared.
            bgCard = cardIndex;
            Backdrop b = recs.backdrop(cards.get(cardIndex));
            if (b != null) showBackdrop(b);
        }
        retarget(false);
        click.move();
        return true;
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
