package com.gathin.tvhome;

import static com.gathin.tvhome.AuraStyle.t;

import android.app.Activity;
import android.app.SearchManager;
import android.app.SearchableInfo;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.widget.EditText;
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
    private static final int PER_APP = 5;
    private static final long WAIT_MS = 1800;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private AuraStyle.Palette pal;
    private EditText input;
    private LinearLayout results;
    private int generation;

    private static final class Hit {
        final String title, sub;
        final Intent intent;
        Hit(String title, String sub, Intent intent) { this.title = title; this.sub = sub; this.intent = intent; }
    }

    private static final class Source {
        final String label;
        final String pkg;
        final ComponentName searchable;
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
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

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
        else showMessage(t("Начните вводить или нажмите кнопку микрофона на пульте", "Start typing, or press the mic button on the remote"));
    }

    @Override
    protected void onDestroy() {
        generation++;
        pool.shutdownNow();
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

    private void row(String title, String sub, final Intent intent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
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
        r.addView(a);
        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextColor(pal.sub);
            s.setTextSize(12);
            s.setSingleLine(true);
            r.addView(s);
        }
        r.setOnClickListener(v -> open(intent));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        results.addView(r, lp);
    }

    private void open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, t("Не удалось открыть", "Cannot open"), Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ search

    private void search(final String q) {
        final int mine = ++generation;
        results.removeAllViews();
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
            row(label, pkg, go);
        }

        // 2. Every app that publishes search suggestions.
        final List<Source> sources = new ArrayList<>();
        final SearchManager sm = (SearchManager) getSystemService(SEARCH_SERVICE);
        List<ResolveInfo> searchers = pm.queryIntentActivities(new Intent(Intent.ACTION_SEARCH), 0);
        List<String> done = new ArrayList<>();
        for (ResolveInfo ri : searchers) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || done.contains(pkg)) continue;
            done.add(pkg);
            sources.add(new Source(String.valueOf(ri.activityInfo.applicationInfo.loadLabel(pm)), pkg, new ComponentName(pkg, ri.activityInfo.name)));
        }
        if (sources.isEmpty() && !header) showMessage(t("Ничего не найдено", "Nothing found"));
        final String query = q;
        for (final Source s : sources) {
            Future<List<Hit>> f = pool.submit(() -> ask(sm, s, query));
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
            header(s.label);
            for (Hit h : hits) row(h.title, h.sub, h.intent);
        }
        Intent search = new Intent(Intent.ACTION_SEARCH).setPackage(s.pkg).putExtra(SearchManager.QUERY, q);
        if (hits == null || hits.isEmpty()) {
            if (getPackageManager().resolveActivity(search, 0) != null) {
                header(s.label);
                row(t("Искать «", "Search \"") + q + t("» здесь", "\" here"), s.pkg, search);
            }
        }
    }

    /** One app's suggestions for the words, through the content provider its searchable config names. Null: not shared. */
    private List<Hit> ask(SearchManager sm, Source s, String q) {
        try {
            SearchableInfo info = sm.getSearchableInfo(s.searchable);
            if (info == null || info.getSuggestAuthority() == null) return null;
            Uri.Builder ub = new Uri.Builder().scheme("content").authority(info.getSuggestAuthority());
            if (info.getSuggestPath() != null) ub.appendEncodedPath(info.getSuggestPath());
            ub.appendPath(SearchManager.SUGGEST_URI_PATH_QUERY);
            String selection = info.getSuggestSelection();
            String[] args = null;
            if (selection != null) args = new String[]{q};
            else ub.appendPath(q);
            Cursor c = getContentResolver().query(ub.build(), null, selection, args, null);
            if (c == null) return null;
            List<Hit> out = new ArrayList<>();
            try {
                int t1 = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_1), t2 = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_2);
                int act = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_ACTION), data = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_DATA);
                int extra = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA), qcol = c.getColumnIndex(SearchManager.SUGGEST_COLUMN_QUERY);
                while (c.moveToNext() && out.size() < PER_APP) {
                    String title = t1 >= 0 ? c.getString(t1) : null;
                    if (title == null || title.isEmpty()) continue;
                    String action = act >= 0 ? c.getString(act) : null;
                    if (action == null) action = info.getSuggestIntentAction();
                    if (action == null) action = Intent.ACTION_VIEW;
                    Intent i = new Intent(action).setPackage(s.pkg);
                    String d = data >= 0 ? c.getString(data) : null;
                    if (d == null) d = info.getSuggestIntentData();
                    if (d != null) i.setData(Uri.parse(d));
                    if (extra >= 0 && c.getString(extra) != null) i.putExtra(SearchManager.EXTRA_DATA_KEY, c.getString(extra));
                    i.putExtra(SearchManager.USER_QUERY, q);
                    i.putExtra(SearchManager.QUERY, qcol >= 0 && c.getString(qcol) != null ? c.getString(qcol) : title);
                    out.add(new Hit(title, t2 >= 0 ? c.getString(t2) : null, i));
                }
            } finally {
                c.close();
            }
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SEARCH) { input.requestFocus(); return true; }
        return super.onKeyDown(keyCode, event);
    }
}
