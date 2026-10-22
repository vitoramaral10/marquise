package br.dev.vitormelo.marquise;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen's search: type a title (the TV's keyboard), and the titles on the person's Jellyfin server or on
 * their streaming apps (TMDB) are listed; OK opens one in its app, like a poster. Standard views, as in SetupActivity,
 * so typing brings up the TV's own keyboard and a screen reader reads it as it is.
 */
public class SearchActivity extends Activity {
    // The home screen's light look: its background (the window's), ink, grey text and the white focus pill.
    private static final int INK = 0xFF1D1D1F, DIM = 0xFF6E6E73, FAINT = 0xFF8E8E93;

    private final Handler main = new Handler(Looper.getMainLooper());
    // One search at a time: a new one waits for the last (the token drops its answer), so repeated sends don't pile up.
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Recs.Pick> found = new ArrayList<>();
    private ArrayAdapter<Recs.Pick> adapter;
    // The small posters: loaded a few at a time, kept for the screen's life (20 at most per search, a few hundred KB).
    private final ExecutorService thumbs = Executors.newFixedThreadPool(3);
    private final Map<String, Bitmap> posters = new HashMap<>();
    private TextView status;
    private ListView list;
    private EditText input;
    private int token;   // bumped on every search: an answer to an older one is dropped

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        final float k = getResources().getDisplayMetrics().widthPixels / 1920f;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Math.round(160 * k), Math.round(90 * k), Math.round(160 * k), Math.round(60 * k));

        TextView title = text(Ui.tr("Buscar", "Search"), 56 * k, Ui.displaySemibold, INK);
        root.addView(title);
        input = new EditText(this);
        input.setHint(Ui.tr("Nome do filme ou da série", "Movie or show name"));
        input.setHintTextColor(FAINT);
        input.setTextColor(INK);
        input.setBackgroundTintList(ColorStateList.valueOf(FAINT));
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 34 * k);
        input.setTypeface(Ui.regular);
        input.setSingleLine();
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setOnEditorActionListener((v, action, e) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            search(input.getText().toString().trim());
            return true;
        });
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.topMargin = Math.round(28 * k);
        root.addView(input, ip);

        status = text("", 26 * k, Ui.regular, DIM);
        status.setPadding(0, Math.round(18 * k), 0, Math.round(18 * k));
        root.addView(status);

        list = new ListView(this);
        list.setDivider(null);
        GradientDrawable pill = new GradientDrawable();   // the focused row, like a tab or a button on Home
        pill.setColor(0xFFFFFFFF);
        pill.setCornerRadius(22 * k);
        list.setSelector(pill);
        final int posterH = Math.round(150 * k), posterW = posterH * 2 / 3, pad = Math.round(14 * k);
        final ViewOutlineProvider rounded = new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 10 * k);
            }
        };
        adapter = new ArrayAdapter<Recs.Pick>(this, 0, found) {
            @Override public View getView(int position, View convert, ViewGroup parent) {
                if (convert == null) {   // the poster, then the title over a grey line (the service and the year)
                    LinearLayout row = new LinearLayout(SearchActivity.this);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(Math.round(24 * k), pad, Math.round(24 * k), pad);
                    ImageView image = new ImageView(SearchActivity.this);
                    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    image.setBackgroundColor(0xFFDCDCE0);   // the card's grey, until the poster is in
                    image.setOutlineProvider(rounded);
                    image.setClipToOutline(true);
                    row.addView(image, new LinearLayout.LayoutParams(posterW, posterH));
                    LinearLayout texts = new LinearLayout(SearchActivity.this);
                    texts.setOrientation(LinearLayout.VERTICAL);
                    texts.setPadding(Math.round(28 * k), 0, 0, 0);
                    texts.addView(text("", 32 * k, Ui.medium, INK));
                    texts.addView(text("", 25 * k, Ui.regular, DIM));
                    row.addView(texts);
                    convert = row;
                }
                Recs.Pick p = getItem(position);
                LinearLayout row = (LinearLayout) convert, texts = (LinearLayout) row.getChildAt(1);
                ((TextView) texts.getChildAt(0)).setText(p.title);
                ((TextView) texts.getChildAt(1)).setText(p.badge);
                ImageView image = (ImageView) row.getChildAt(0);
                image.setTag(p.poster);
                image.setImageBitmap(posters.get(p.poster));
                if (p.poster != null && !posters.containsKey(p.poster)) loadPoster(p, image, posterH);
                return convert;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> open(found.get(position), view));
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        // TMDB's terms: this notice wherever its data is shown (as on the settings screen).
        if (Recs.tmdb(this)) {
            root.addView(text(Ui.tr("Usa a API do TMDB, sem endosso ou certificação do TMDB.",
                    "Uses the TMDB API but is not endorsed or certified by TMDB."), 22 * k, Ui.regular, FAINT));
        }
        setContentView(root);
    }

    @Override protected void onDestroy() {
        token++;
        io.shutdownNow();
        thumbs.shutdownNow();
        super.onDestroy();
    }

    private TextView text(String s, float px, android.graphics.Typeface face, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        return t;
    }

    private void search(final String query) {
        if (query.isEmpty()) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        final int mine = ++token;
        found.clear();
        adapter.notifyDataSetChanged();
        status.setText(Ui.tr("Buscando…", "Searching…"));
        io.execute(() -> {
            List<Recs.Pick> result = null;
            String error = null;
            try {
                result = Recs.get(this).search(query);
            } catch (Exception e) {
                error = e.getMessage();
                AppLog.w("search: " + e.getMessage());
            }
            final List<Recs.Pick> picks = result;
            final String message = error;
            main.post(() -> {
                if (mine != token) return;
                if (picks == null) {
                    status.setText(message == null ? Ui.tr("A busca falhou.", "The search failed.") : message);
                    return;
                }
                status.setText("");
                found.addAll(picks);
                adapter.notifyDataSetChanged();
                list.requestFocus();
            });
        });
    }

    /** Fetches a poster off the UI thread; if the row still shows that title when it arrives, it is set. */
    private void loadPoster(final Recs.Pick p, final ImageView image, final int height) {
        posters.put(p.poster, null);   // asked once: a row drawn again meanwhile doesn't ask again
        thumbs.execute(() -> {
            final Bitmap b = Recs.get(this).thumbnail(p, height);
            main.post(() -> {
                if (b == null) return;
                posters.put(p.poster, b);
                if (p.poster.equals(image.getTag())) image.setImageBitmap(b);
            });
        });
    }

    private void open(Recs.Pick p, View from) {
        if (p.pkg == null || !HomeApps.open(this, HomeApps.pickItem(p), from, 0, 0, from.getWidth(), from.getHeight())) {
            status.setText(Ui.tr("Não deu para abrir ", "Couldn't open ") + p.title + ".");
        }
    }
}
