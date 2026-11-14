package com.gathin.tvhome;

import static com.gathin.tvhome.AuraStyle.t;

import android.app.Activity;
import android.app.SearchManager;
import android.app.SearchableInfo;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.Manifest;
import android.content.pm.PackageManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.graphics.drawable.Drawable;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * AURA search: one screen that asks every installed app that publishes search suggestions what it has for the
 * words typed, and shows each answer under the app's name. No network, no permission beyond what apps allow:
 * an app that keeps its suggestions private is offered as "Search in <app>" instead, which hands it the words.
 */
public class AuraSearchActivity extends Activity {
    static final String EXTRA_QUERY = "query";
    static final String EXTRA_VOICE = "voice";
    private static final int VOICE_REQUEST = 52;
    private static final int PER_APP = 8;
    private static final long WAIT_MS = 1800;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private AuraStyle.Palette pal;
    private EditText input;
    private LinearLayout results;
    private int generation;
    private LinearLayout panel;
    private TextView status;
    private View[] bars;
    private final float[] level = new float[21];

    private static final class Hit {
        final String title, sub;
        final Intent intent;
        Drawable poster;
        String posterUrl;
        Hit(String title, String sub, Intent intent) { this.title = title; this.sub = sub; this.intent = intent; }
    }

    private static final class Source {
        final String label;
        final String pkg;
        final ComponentName searchable;
        SearchableInfo info;
        volatile String why = "";
        Source(String label, String pkg, ComponentName searchable) { this.label = label; this.pkg = pkg; this.searchable = searchable; }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        SharedPreferences sp = AuraStyle.prefs(this);
        pal = AuraStyle.palette(sp);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(AuraStyle.background(pal));
        root.setPadding(dp(48), dp(28), dp(48), dp(20));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(20), dp(14), dp(20), dp(14));
        panel.setBackground(AuraStyle.shape(AuraStyle.alpha(pal.card, 210), pal.border, dp(28), 1));
        FrameLayout mic = new FrameLayout(this);
        mic.setBackground(AuraStyle.shape(pal.accent, 0, dp(32), 0));
        ImageView micGlyph = new ImageView(this);
        micGlyph.setImageDrawable(new AuraStyle.Glyph(AuraStyle.G_MIC, pal.onAccent));
        mic.addView(micGlyph, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        // The mic is a button: press OK on it (Up from the search line) to speak without going back to the home screen.
        mic.setFocusable(true);
        mic.setClickable(true);
        mic.setOnClickListener(v -> { setStatus(t("Слушаю…", "Listening…")); showMessage(t("Скажите название или команду", "Say a name or a command")); listen(); });
        mic.setOnFocusChangeListener((v, focus) -> mic.setBackground(AuraStyle.shape(pal.accent, focus ? pal.text : 0, dp(32), focus ? 3 : 0)));
        panel.addView(mic, new LinearLayout.LayoutParams(dp(64), dp(64)));
        status = new TextView(this);
        status.setTextColor(pal.text);
        status.setTextSize(24);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(6), 0, dp(2));
        panel.addView(status);
        LinearLayout wave = new LinearLayout(this);
        wave.setGravity(Gravity.CENTER);
        bars = new View[level.length];
        for (int i = 0; i < bars.length; i++) {
            bars[i] = new View(this);
            bars[i].setBackground(AuraStyle.shape(AuraStyle.alpha(pal.accent, 200), 0, dp(2), 0));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(5), dp(4));
            bp.leftMargin = bp.rightMargin = dp(3);
            wave.addView(bars[i], bp);
        }
        panel.addView(wave, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        LinearLayout chips = new LinearLayout(this);
        chips.setGravity(Gravity.CENTER);
        String[] ideas = {t("YouTube", "YouTube"), t("Настройки", "Settings"), t("Фильмы", "Films"), t("Музыка", "Music")};
        for (final String idea : ideas) {
            TextView chip = new TextView(this);
            chip.setText(idea);
            chip.setTextColor(pal.text);
            chip.setTextSize(13);
            chip.setFocusable(true);
            chip.setClickable(true);
            chip.setPadding(dp(16), dp(7), dp(16), dp(7));
            chip.setBackground(AuraStyle.card(pal, dp(10), false));
            chip.setOnFocusChangeListener((v, f) -> v.setBackground(AuraStyle.card(pal, dp(10), f)));
            chip.setOnClickListener(v -> { input.setText(idea); input.setSelection(input.length()); });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.leftMargin = cp.rightMargin = dp(5);
            chips.addView(chip, cp);
        }
        panel.addView(chips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(panel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setStatus(t("Что найти?", "What to find?"));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), 0, dp(16), 0);
        bar.setBackground(AuraStyle.shape(AuraStyle.alpha(pal.card, 230), pal.accent, dp(14), 1));
        input = new EditText(this);
        input.setBackground(null);
        input.setSingleLine(true);
        input.setHint(t("Что найти? Приложение, фильм, видео…", "What to find? An app, a film, a video…"));
        input.setHintTextColor(pal.sub);
        input.setTextColor(pal.text);
        input.setTextSize(18);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable e) { schedule(e.toString()); }
        });
        input.setOnEditorActionListener((v, action, ev) -> { schedule(v.getText().toString()); return false; });
        bar.addView(input, new LinearLayout.LayoutParams(0, dp(52), 1f));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        barLp.topMargin = dp(12);
        root.addView(bar, barLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setPadding(0, dp(14), 0, 0);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(results, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        String q = getIntent().getStringExtra(EXTRA_QUERY);
        if (q != null && !q.isEmpty()) input.setText(q);
        else if ((getIntent().getBooleanExtra(EXTRA_VOICE, false) || isAssist(getIntent())) && b == null) { listen(); setStatus(t("Слушаю…", "Listening…")); showMessage(t("Скажите название или команду", "Say a name or a command")); }
        else showMessage(t("Начните вводить или нажмите кнопку микрофона на пульте", "Start typing, or press the mic button on the remote"));
    }

    private SpeechRecognizer recognizer;

    /** True when the system started this screen from the remote's microphone (assist) button. */
    private static boolean isAssist(Intent i) {
        String a = i == null ? null : i.getAction();
        return Intent.ACTION_ASSIST.equals(a) || "android.intent.action.VOICE_ASSIST".equals(a) || "android.intent.action.SEARCH_LONG_PRESS".equals(a);
    }

    /**
     * Listens with the speech recogniser's API, so no Google window opens over this screen: the words land in the
     * search line as they are heard. Without the microphone permission or a recogniser the system's own dialog is the
     * fallback (the permission is asked once; on a TV it can also be given with `pm grant`).
     */
    private void listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (!askedMic) { askedMic = true; requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 53); return; }
            systemDialog();
            return;
        }
        // Recognition services installed on the TV (declared in <queries>); the default one goes first.
        List<ComponentName> services = new ArrayList<>();
        try {
            for (ResolveInfo ri : getPackageManager().queryIntentServices(new Intent("android.speech.RecognitionService"), 0)) {
                if (ri.serviceInfo != null) services.add(new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name));
            }
        } catch (RuntimeException ignored) { }
        if (services.isEmpty() && !SpeechRecognizer.isRecognitionAvailable(this)) { systemDialog(); return; }
        try {
            if (recognizer != null) { recognizer.destroy(); recognizer = null; }
            ComponentName pick = serviceIndex < services.size() ? services.get(serviceIndex) : null;
            recognizer = pick != null ? SpeechRecognizer.createSpeechRecognizer(this, pick) : SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle p) { setStatus(t("Слушаю…", "Listening…")); }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float r) { wave(r); }
                @Override public void onBufferReceived(byte[] b) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onEvent(int t, Bundle p) { }
                @Override public void onError(int error) {
                    // Another installed recogniser may work where this one does not (no network, no language).
                    if ((error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || error == SpeechRecognizer.ERROR_SERVER
                            || error == SpeechRecognizer.ERROR_CLIENT) && serviceIndex + 1 < services.size()) {
                        serviceIndex++;
                        listen();
                        return;
                    }
                    String name = pick == null ? "?" : pick.getPackageName();
                    showMessage(t("Не расслышал (код " + error + ", " + name + "). Введите слова или попробуйте ещё раз",
                            "Didn't catch that (code " + error + ", " + name + "). Type the words or try again"));
                }
                @Override public void onPartialResults(Bundle p) { heard(p, false); }
                @Override public void onResults(Bundle p) { heard(p, true); }
            });
            recognizer.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true));
        } catch (RuntimeException e) {
            systemDialog();
        }
    }

    private int serviceIndex;

    private boolean askedMic;

    private void setStatus(String s) { if (status != null) status.setText(s); }

    /** Moves the bars with the loudness the recogniser reports (about -2..10). */
    private void wave(float rms) {
        float v = Math.max(0f, Math.min(1f, (rms + 2f) / 12f));
        System.arraycopy(level, 1, level, 0, level.length - 1);
        level[level.length - 1] = v;
        for (int i = 0; i < bars.length; i++) {
            ViewGroup.LayoutParams lp = bars[i].getLayoutParams();
            lp.height = dp(4) + Math.round(level[i] * dp(34));
            bars[i].setLayoutParams(lp);
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request != 53) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) listen(); else systemDialog();
    }

    private void heard(Bundle p, boolean last) {
        List<String> words = p == null ? null : p.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (words == null || words.isEmpty()) return;
        input.setText(words.get(0));
        input.setSelection(input.length());
        setStatus(last ? t("Ищу…", "Searching…") : t("Слушаю…", "Listening…"));
    }

    /** The TV's speech recogniser in its own window, its words land in this screen's search line. */
    private void systemDialog() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, t("Что найти?", "What to find?"));
        try {
            startActivityForResult(i, VOICE_REQUEST);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, t("Голосовой ввод недоступен на этом ТВ", "Voice input is not available on this TV"), Toast.LENGTH_LONG).show();
            showMessage(t("Введите слова с клавиатуры", "Type the words instead"));
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != VOICE_REQUEST) return;
        List<String> spoken = result == RESULT_OK && data != null ? data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) : null;
        if (spoken == null || spoken.isEmpty()) { showMessage(t("Не расслышал. Введите слова или попробуйте ещё раз", "Didn't catch that. Type the words or try again")); return; }
        input.setText(spoken.get(0));
        input.setSelection(input.length());
    }

    @Override
    protected void onDestroy() {
        generation++;
        pool.shutdownNow();
        if (recognizer != null) recognizer.destroy();
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private final Runnable[] pending = new Runnable[1];

    private void schedule(String q) {
        if (pending[0] != null) ui.removeCallbacks(pending[0]);
        final String query = q.trim();
        pending[0] = () -> search(query);
        ui.postDelayed(pending[0], 350);
    }

    private void showMessage(String s) {
        results.removeAllViews();
        TextView m = new TextView(this);
        m.setText(s);
        m.setTextColor(pal.sub);
        m.setTextSize(15);
        m.setPadding(dp(8), dp(24), dp(8), 0);
        results.addView(m);
    }

    private void header(String name) {
        TextView h = new TextView(this);
        h.setText(name);
        h.setTextColor(pal.accent);
        h.setTextSize(13);
        h.setAllCaps(true);
        h.setPadding(dp(8), dp(18), dp(8), dp(6));
        results.addView(h);
    }

    private void row(String title, String sub, final Intent intent) { row(title, sub, intent, null); }

    private void row(String title, String sub, final Intent intent, Drawable icon) { row(title, sub, intent, icon, false, null); }

    private void row(String title, String sub, final Intent intent, Drawable icon, boolean tall, String coverUrl) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(tall ? dp(46) : dp(40), tall ? dp(64) : dp(40));
            if (tall) iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            ip.rightMargin = dp(14);
            r.addView(iv, ip);
            if (coverUrl != null) loadCover(coverUrl, iv);
        }
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.setFocusable(true);
        r.setClickable(true);
        r.setPadding(dp(16), dp(10), dp(16), dp(10));
        r.setBackground(AuraStyle.card(pal, dp(12), false));
        r.setOnFocusChangeListener((v, f) -> v.setBackground(AuraStyle.card(pal, dp(12), f)));
        TextView a = new TextView(this);
        a.setText(title);
        a.setTextColor(pal.text);
        a.setTextSize(16);
        a.setSingleLine(true);
        texts.addView(a);
        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextColor(pal.sub);
            s.setTextSize(12);
            s.setSingleLine(true);
            texts.addView(s);
        }
        r.setOnClickListener(v -> open(intent));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        results.addView(r, lp);
    }

    /** An app that has no results to list: its shortcut on the left and one card that opens the app's own search. */
    private void searchLine(String pkg, String label, Intent search, String q, String why) {
        Source s = new Source(label, pkg, null);
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(appShortcut(s), new LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setPadding(dp(10), dp(6), dp(10), dp(6));
        Hit h = new Hit(t("Искать «", "Search \"") + q + t("»", "\""), why == null || why.isEmpty() ? t("в приложении", "in the app") : why, search);
        strip.addView(resultCard(s, h), cardParams());
        line.addView(strip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        results.addView(line, lp);
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(132), ViewGroup.LayoutParams.WRAP_CONTENT);
        p.rightMargin = dp(10);
        return p;
    }

    /** The app's own shortcut: its icon and name; OK opens the app. */
    private View appShortcut(final Source s) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(8), dp(12), dp(8), dp(12));
        c.setFocusable(true);
        c.setClickable(true);
        c.setBackground(AuraStyle.card(pal, dp(14), false));
        c.setOnFocusChangeListener((v, f) -> v.setBackground(AuraStyle.card(pal, dp(14), f)));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon(s.pkg));
        c.addView(iv, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView name = new TextView(this);
        name.setText(s.label);
        name.setTextColor(pal.text);
        name.setTextSize(13);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setPadding(0, dp(6), 0, 0);
        c.addView(name);
        c.setOnClickListener(v -> {
            Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(s.pkg);
            if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(s.pkg);
            if (launch != null) open(launch);
        });
        return c;
    }

    /** One result: the cover on top (the app's icon when it names none), then the title and the details. */
    private View resultCard(Source s, Hit h) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(6), dp(6), dp(6), dp(10));
        c.setFocusable(true);
        c.setClickable(true);
        c.setBackground(AuraStyle.card(pal, dp(12), false));
        c.setOnFocusChangeListener((v, f) -> v.setBackground(AuraStyle.card(pal, dp(12), f)));
        ImageView iv = new ImageView(this);
        boolean cover = h.poster != null || h.posterUrl != null;
        iv.setImageDrawable(h.poster != null ? h.poster : icon(s.pkg));
        iv.setScaleType(cover ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.CENTER_INSIDE);
        c.addView(iv, new LinearLayout.LayoutParams(dp(120), dp(cover ? 140 : 90)));
        if (h.posterUrl != null) loadCover(h.posterUrl, iv);
        TextView a = new TextView(this);
        a.setText(h.title);
        a.setTextColor(pal.text);
        a.setTextSize(14);
        a.setMaxLines(1);
        a.setEllipsize(android.text.TextUtils.TruncateAt.END);
        a.setPadding(dp(2), dp(6), dp(2), 0);
        c.addView(a);
        String sub = h.sub;
        if (sub != null) {
            int cut = sub.indexOf(" · " + t("обложка: ", "cover: "));
            if (cut >= 0) sub = sub.substring(0, cut);
            if (sub.startsWith(t("обложка: ", "cover: "))) sub = "";
        }
        if (sub != null && !sub.isEmpty()) {
            TextView b = new TextView(this);
            b.setText(sub);
            b.setTextColor(pal.sub);
            b.setTextSize(11);
            b.setMaxLines(1);
            b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            b.setPadding(dp(2), dp(2), dp(2), 0);
            c.addView(b);
        }
        final Intent intent = h.intent;
        c.setOnClickListener(v -> open(intent));
        return c;
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final String colsSeen = i.getStringExtra("aura_cols");
        i.removeExtra("aura_cols");
        String why;
        try {
            startActivity(i);
            return;
        } catch (ActivityNotFoundException | SecurityException e) {
            why = e.getClass().getSimpleName();
        }
        // The address may not match the app's own filter (host, path, type): ask which of its screens takes this action
        // and open that one by name, which skips the filter.
        if (i.getPackage() != null && i.getAction() != null) {
            try {
                Intent probe = new Intent(i.getAction()).setPackage(i.getPackage());
                java.util.List<ResolveInfo> named = getPackageManager().queryIntentActivities(probe, 0);
                if (!named.isEmpty()) {
                    Intent direct = new Intent(i).setPackage(null);
                    direct.setComponent(new ComponentName(i.getPackage(), named.get(0).activityInfo.name));
                    startActivity(direct);
                    return;
                }
            } catch (ActivityNotFoundException | SecurityException again) {
                why = why + "/" + again.getClass().getSimpleName();
            }
        }
        // The app may not take it with its own name pinned: let the system choose who opens the address.
        if (i.getPackage() != null && i.getData() != null) {
            try {
                startActivity(new Intent(i).setPackage(null));
                return;
            } catch (ActivityNotFoundException | SecurityException again) {
                why = again.getClass().getSimpleName();
            }
        }
        // No address to open: the app's own search for the exact title still gets the person there.
        String title = i.getStringExtra(SearchManager.QUERY);
        if (i.getData() == null && i.getPackage() != null && title != null) {
            try {
                startActivity(new Intent(Intent.ACTION_SEARCH).setPackage(i.getPackage()).putExtra(SearchManager.QUERY, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException again) {
                why = why + "/" + again.getClass().getSimpleName();
            }
        }
        // Last resorts: the same address as a plain view, then at least the app itself.
        if (i.getData() != null) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, i.getData()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException again) {
                why = why + "/VIEW";
            }
        }
        if (i.getPackage() != null) {
            Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(i.getPackage());
            if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(i.getPackage());
            if (launch != null) {
                try {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launch);
                    Toast.makeText(this, t("Открыл приложение: этот результат оно по ссылке не принимает", "Opened the app: it does not accept this result's link"), Toast.LENGTH_LONG).show();
                    return;
                } catch (ActivityNotFoundException | SecurityException again) {
                    why = why + "/APP";
                }
            }
        }
        String cols = colsSeen;
        Toast.makeText(this, t("Не удалось открыть: ", "Cannot open: ") + why + " " + i.getAction() + (cols == null ? "" : " [" + cols + "]")
                + (i.getData() == null ? t(" (нет адреса)", " (no address)") : " " + i.getData()), Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ search

    private void search(final String q) {
        final int mine = ++generation;
        results.removeAllViews();
        if (q.length() >= 2) setStatus("«" + q + "»");
        if (q.length() < 2) {
            showMessage(t("Введите хотя бы две буквы", "Type at least two letters"));
            return;
        }
        final PackageManager pm = getPackageManager();
        final String low = q.toLowerCase(Locale.getDefault());

        // 1. Installed apps whose name matches.
        List<ResolveInfo> launchers = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        launchers.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        List<String> seen = new ArrayList<>();
        boolean header = false;
        for (ResolveInfo ri : launchers) {
            String pkg = ri.activityInfo.packageName;
            String label = String.valueOf(ri.loadLabel(pm));
            if (seen.contains(pkg) || !label.toLowerCase(Locale.getDefault()).contains(low)) continue;
            Intent go = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (go == null) go = pm.getLaunchIntentForPackage(pkg);
            if (go == null) continue;
            seen.add(pkg);
            if (!header) { header(t("Приложения", "Apps")); header = true; }
            row(label, t("Открыть приложение", "Open the app"), go, ri.loadIcon(pm));
        }

        // 2. Every app that publishes search suggestions.
        final List<Source> sources = new ArrayList<>();
        final SearchManager sm = (SearchManager) getSystemService(SEARCH_SERVICE);
        List<String> done = new ArrayList<>();
        List<ResolveInfo> searchers = pm.queryIntentActivities(new Intent(Intent.ACTION_SEARCH), 0);
        for (ResolveInfo ri : searchers) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || done.contains(pkg)) continue;
            if (pm.getLeanbackLaunchIntentForPackage(pkg) == null && pm.getLaunchIntentForPackage(pkg) == null) continue; // system plumbing, not an app
            done.add(pkg);
            sources.add(new Source(String.valueOf(ri.activityInfo.applicationInfo.loadLabel(pm)), pkg, new ComponentName(pkg, ri.activityInfo.name)));
        }
        // 3. Apps that don't publish suggestions but play or search from words: the documented media-search
        // action, and YouTube's search link.
        boolean other = false;
        final String enc = Uri.encode(q);
        String[] youtube = {"com.google.android.youtube.tv", "com.google.android.youtube"};
        for (String yt : youtube) {
            Intent v = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + enc)).setPackage(yt);
            if (done.contains(yt) || pm.resolveActivity(v, 0) == null) continue;
            done.add(yt);
            other = true;
            searchLine(yt, yt.endsWith(".tv") ? "YouTube" : "YouTube", v, q, "");
        }
        Intent play = new Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH").putExtra(SearchManager.QUERY, q);
        for (ResolveInfo ri : pm.queryIntentActivities(play, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || done.contains(pkg)) continue;
            done.add(pkg);
            other = true;
            searchLine(pkg, String.valueOf(ri.loadLabel(pm)), new Intent(play).setPackage(pkg), q, "");
        }
        if (sources.isEmpty() && !header && !other) showMessage(t("Ничего не найдено", "Nothing found"));
        final String query = q;
        for (final Source s : sources) {
            Future<List<Hit>> f = pool.submit(() -> {
                List<Hit> first = ask(sm, s, query);
                String plain = query.replace('ё', 'е').replace('Ё', 'Е');
                if (plain.equals(query)) return first;
                List<Hit> second = ask(sm, s, plain);
                if (first == null) return second;
                if (second != null) for (Hit h : second) {
                    boolean dup = false;
                    for (Hit x : first) if (x.title.equals(h.title)) { dup = true; break; }
                    if (!dup && first.size() < PER_APP) first.add(h);
                }
                return first;
            });
            pool.execute(() -> {
                List<Hit> hits;
                try { hits = f.get(WAIT_MS, TimeUnit.MILLISECONDS); } catch (Exception e) { f.cancel(true); hits = null; }
                final List<Hit> out = hits;
                ui.post(() -> { if (mine == generation) addSource(s, out, query); });
            });
        }
    }

    private void addSource(Source s, List<Hit> hits, String q) {
        if (hits != null && !hits.isEmpty()) {
            // One line per app: the app's shortcut on the left, what it found in a row to the right.
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.addView(appShortcut(s), new LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT));
            android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(this);
            scroller.setHorizontalScrollBarEnabled(false);
            scroller.setClipToPadding(false);
            LinearLayout strip = new LinearLayout(this);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setPadding(dp(10), dp(8), dp(10), dp(12));
            strip.setGravity(Gravity.TOP);
            for (Hit h : hits) strip.addView(resultCard(s, h), cardParams());
            scroller.addView(strip);
            line.addView(scroller, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            results.addView(line, lp);
        }
        Intent search = new Intent(Intent.ACTION_SEARCH).setPackage(s.pkg).putExtra(SearchManager.QUERY, q);
        if (hits == null || hits.isEmpty()) {
            if (getPackageManager().resolveActivity(search, 0) != null) {
                searchLine(s.pkg, s.label, search, q, s.why);
            }
        }
    }

    /**
     * The picture a suggestion names in its icon column: a resource number of the app, or a content or resource URI
     * the app serves. Web addresses are not fetched: the launcher has no network permission.
     */
    private Drawable poster(String ref, String pkg) {
        if (ref == null || ref.isEmpty()) return null;
        try {
            if (ref.matches("\\d+")) return getPackageManager().getResourcesForApplication(pkg).getDrawable(Integer.parseInt(ref), null);
            Uri u = Uri.parse(ref);
            String scheme = u.getScheme();
            if (!"content".equals(scheme) && !"android.resource".equals(scheme)) return null;
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = 2;
            java.io.InputStream in = getContentResolver().openInputStream(u);
            if (in == null) return null;
            try {
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in, null, o);
                return bmp == null ? null : new android.graphics.drawable.BitmapDrawable(getResources(), bmp);
            } finally {
                in.close();
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static final android.util.LruCache<String, android.graphics.Bitmap> COVERS =
            new android.util.LruCache<String, android.graphics.Bitmap>(6 * 1024 * 1024) {
                @Override protected int sizeOf(String k, android.graphics.Bitmap b) { return b.getByteCount(); }
            };

    /**
     * Fetches a cover named by an https link in a suggestion, for a result that is on screen: the only network use of
     * the launcher. Short timeouts, a size cap, a small memory cache, no disk and no cookies.
     */
    private void loadCover(final String url, final ImageView into) {
        android.graphics.Bitmap cached = COVERS.get(url);
        if (cached != null) { into.setImageBitmap(cached); return; }
        try {
            pool.execute(() -> {
                java.net.HttpURLConnection c = null;
                try {
                    c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(4000);
                    c.setInstanceFollowRedirects(true);
                    if (c.getResponseCode() != 200 || c.getContentLength() > 2 * 1024 * 1024) return;
                    java.io.InputStream in = c.getInputStream();
                    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    int n, total = 0;
                    while ((n = in.read(chunk)) > 0) { total += n; if (total > 2 * 1024 * 1024) return; buf.write(chunk, 0, n); }
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inSampleSize = 2;
                    byte[] data = buf.toByteArray();
                    final android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
                    if (bmp == null) return;
                    COVERS.put(url, bmp);
                    ui.post(() -> into.setImageBitmap(bmp));
                } catch (Exception | OutOfMemoryError ignored) {
                    // no cover: the app's icon stays
                } finally {
                    if (c != null) c.disconnect();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // the screen is gone
        }
    }

    private Drawable icon(String pkg) {
        try { return getPackageManager().getApplicationIcon(pkg); } catch (PackageManager.NameNotFoundException e) { return null; }
    }

    /** One app's suggestions for the words, through the content provider its searchable config names. Null: not shared. */
    /** Some providers (NUM) answer only to a lower-case query: if the typed one finds nothing, try it in lower case. */
    private List<Hit> ask(SearchManager sm, Source s, String q) {
        List<Hit> r = askOnce(sm, s, q);
        String low = q.toLowerCase(Locale.getDefault());
        if ((r == null || r.isEmpty()) && !low.equals(q)) {
            List<Hit> r2 = askOnce(sm, s, low);
            if (r2 != null && !r2.isEmpty()) return r2;
        }
        return r;
    }

    private List<Hit> askOnce(SearchManager sm, Source s, String q) {
        try {
            SearchableInfo info = s.info != null ? s.info : sm.getSearchableInfo(s.searchable);
            if (info == null || info.getSuggestAuthority() == null) { s.why = t("подсказки не публикуются", "no suggestions published"); return null; }
            Uri.Builder ub = new Uri.Builder().scheme("content").authority(info.getSuggestAuthority());
            if (info.getSuggestPath() != null) ub.appendEncodedPath(info.getSuggestPath());
            ub.appendPath(SearchManager.SUGGEST_URI_PATH_QUERY);
            String selection = info.getSuggestSelection();
            String[] args = null;
            if (selection != null) args = new String[]{q};
            else ub.appendPath(q);
            Cursor c = getContentResolver().query(ub.build(), null, selection, args, null);
            if (c == null) { s.why = t("подсказок нет", "no suggestions"); return null; }
            if (c.getCount() == 0) s.why = t("ничего не нашлось", "nothing found");
            List<Hit> out = new ArrayList<>();
            try {
                int t1 = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_1), t2 = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_2);
                int act = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_ACTION), data = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_DATA);
                int extra = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA), qcol = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_QUERY);
                int card = c.getColumnIndex("suggest_result_card_image");
                int ic1 = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_ICON_1), ic2 = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_ICON_2);
                while (c.moveToNext() && out.size() < PER_APP) {
                    String title = t1 >= 0 ? c.getString(t1) : null;
                    if (title == null || title.isEmpty()) continue;
                    String action = act >= 0 ? c.getString(act) : null;
                    if (action == null) action = info.getSuggestIntentAction();
                    if (action == null) action = Intent.ACTION_VIEW;
                    Intent i = new Intent(action).setPackage(s.pkg);
                    String d = data >= 0 ? c.getString(data) : null;
                    if (d == null) {
                        d = info.getSuggestIntentData();
                        int idCol = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID);
                        String id = idCol >= 0 ? c.getString(idCol) : null;
                        if (d != null && id != null) d = d + "/" + Uri.encode(id);
                    }
                    if (d == null) {
                        // Some apps keep the deep link in a column of their own: take a value that looks like scheme://...
                        for (int col = 0; col < c.getColumnCount() && d == null; col++) {
                            String v;
                            try { v = c.getString(col); } catch (RuntimeException e) { v = null; }
                            if (v != null && v.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://\\S+$")) d = v;
                        }
                    }
                    if (d != null) i.setData(Uri.parse(d));
                    // NUM takes a result as a plain view of its themoviedb.org page (its own public link filter), not as the
                    // address its suggestion columns name. Handing NUM that link is only an address: nothing is fetched from it.
                    if ("ru.yourok.num".equals(s.pkg)) {
                        int idc = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID);
                        String id = idc >= 0 ? c.getString(idc) : null;
                        String kind = extra >= 0 ? c.getString(extra) : null;
                        if (id != null && id.matches("\\d+") && ("movie".equals(kind) || "tv".equals(kind))) {
                            i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.themoviedb.org/" + kind + "/" + id)).setPackage(s.pkg);
                        }
                    }
                    i.putExtra("aura_cols", String.join(",", c.getColumnNames()));
                    if (extra >= 0 && c.getString(extra) != null) i.putExtra(SearchManager.EXTRA_DATA_KEY, c.getString(extra));
                    i.putExtra(SearchManager.USER_QUERY, q);
                    i.putExtra(SearchManager.QUERY, qcol >= 0 && c.getString(qcol) != null ? c.getString(qcol) : title);
                    Hit hit = new Hit(title, t2 >= 0 ? c.getString(t2) : null, i);
                    String ref = card >= 0 ? c.getString(card) : null;
                    if ((ref == null || ref.isEmpty()) && ic1 >= 0) ref = c.getString(ic1);
                    if ((ref == null || ref.isEmpty()) && ic2 >= 0) ref = c.getString(ic2);
                    hit.poster = poster(ref, s.pkg);
                    if (hit.poster == null && ref != null && ref.startsWith("https://")) hit.posterUrl = ref;
                    if (hit.poster == null && hit.posterUrl == null) {
                        String kind = ref == null || ref.isEmpty() ? t("нет", "none") : ref.startsWith("http") ? t("ссылка", "web link") : t("не открылась", "could not open");
                        hit = new Hit(hit.title, (hit.sub == null ? "" : hit.sub + " · ") + t("обложка: ", "cover: ") + kind, hit.intent);
                    }
                    out.add(hit);
                }
            } finally {
                c.close();
            }
            return out;
        } catch (SecurityException e) {
            s.why = t("нет доступа к подсказкам", "no access to its suggestions");
            return null;
        } catch (RuntimeException e) {
            s.why = t("ошибка: ", "error: ") + e.getClass().getSimpleName();
            return null;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SEARCH) { input.requestFocus(); return true; }
        return super.onKeyDown(keyCode, event);
    }
}
