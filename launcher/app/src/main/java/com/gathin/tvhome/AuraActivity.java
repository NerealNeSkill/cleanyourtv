package com.gathin.tvhome;

import static com.gathin.tvhome.AuraStyle.t;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The AURA home screen: a side rail, a top bar with search, and three pages (Home, Apps, Settings) in a dark
 * "Orbit" look, with the colours, grid and shortcuts set from the Personalisation page. Built from plain views,
 * with no blur, no video, no service and no network. Home opens it when AURA is on (see AuraStyle.shouldRun);
 * the classic Home stays one switch away and carries the Inputs menu.
 */
public class AuraActivity extends Activity {
    private static final int HOME = 0, APPS = 1, SETTINGS = 2;
    private static final String[] CATEGORIES_RU = {"Оформление", "Главный экран", "Приложения", "Голос и поиск", "Пульт и кнопки", "О лаунчере"};
    private static final String[] CATEGORIES_EN = {"Appearance", "Home screen", "Apps", "Voice and search", "Remote and buttons", "About"};

    private SharedPreferences sp;
    private AuraStyle.Palette pal;
    private Config cfg;
    private float density;
    private FrameLayout root;
    private int page = HOME, category = 0;
    private String filter = "";
    private EditText search;
    private FrameLayout panelHolder;
    private boolean dirty, resumedOnce;
    private final List<App> apps = new ArrayList<>();

    private static final class App {
        String pkg, label;
        Drawable icon;
    }

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            dirty = true;
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        sp = AuraStyle.prefs(this);
        // Cleared in onResume once the first frame is up: a crash before that sends Home back to the classic screen.
        int fails = sp.getBoolean(AuraStyle.K_STARTED, false) && !sp.getBoolean(AuraStyle.K_OK, true)
                ? sp.getInt(AuraStyle.K_FAILS, 0) + 1 : 0;
        sp.edit().putBoolean(AuraStyle.K_STARTED, true).putBoolean(AuraStyle.K_OK, false)
                .putInt(AuraStyle.K_FAILS, fails).commit();
        density = getResources().getDisplayMetrics().density;
        cfg = Config.load(this);
        if (MainActivity.ACTION_LAUNCHER_SETTINGS.equals(getIntent().getAction())) page = SETTINGS;
        getWindow().setBackgroundDrawable(null);
        root = new FrameLayout(this);
        setContentView(root);
        loadApps();
        build(null);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        if (dirty) {
            dirty = false;
            loadApps();
            build(null);
        }
    }

    @Override
    protected void onStop() {
        try { unregisterReceiver(packages); } catch (IllegalArgumentException ignored) { }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!resumedOnce) {
            resumedOnce = true;
            root.post(() -> sp.edit().putBoolean(AuraStyle.K_OK, true).putInt(AuraStyle.K_FAILS, 0).apply());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (MainActivity.ACTION_LAUNCHER_SETTINGS.equals(intent.getAction())) page = SETTINGS;
        else page = HOME;
        filter = "";
        build(null);
    }

    @Override
    public void onBackPressed() {
        if (page != HOME) {
            page = HOME;
            build("nav0");
        }
    }

    // ---------------------------------------------------------------- data

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, App> found = new LinkedHashMap<>();
        List<String> categories = new ArrayList<>();
        categories.add(Intent.CATEGORY_LEANBACK_LAUNCHER);
        if (sp.getBoolean(AuraStyle.K_ALL, false)) categories.add(Intent.CATEGORY_LAUNCHER);
        for (String category : categories) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || cfg.hiddenApps.contains(pkg) || found.containsKey(pkg)) continue;
                App a = new App();
                a.pkg = pkg;
                String custom = cfg.appLabels.get(pkg);
                a.label = custom != null ? custom : String.valueOf(ri.loadLabel(pm));
                a.icon = ri.loadIcon(pm);
                found.put(pkg, a);
            }
        }
        apps.clear();
        apps.addAll(found.values());
    }

    private Set<String> stringSet(String key) {
        String raw = sp.getString(key, null);
        Set<String> out = new HashSet<>();
        if (raw != null) for (String s : raw.split(",")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    private List<String> stringList(String key) {
        String raw = sp.getString(key, null);
        List<String> out = new ArrayList<>();
        if (raw != null) for (String s : raw.split(",")) if (!s.isEmpty() && !out.contains(s)) out.add(s);
        return out;
    }

    private void saveList(String key, List<String> value) {
        sp.edit().putString(key, String.join(",", value)).apply();
    }

    private App find(String pkg) {
        for (App a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    /** Apps the owner has not hidden, in the chosen order, narrowed by the search text. */
    private List<App> visible() {
        Set<String> hidden = stringSet(AuraStyle.K_HIDDEN);
        List<App> out = new ArrayList<>();
        String q = filter.toLowerCase(Locale.getDefault());
        for (App a : apps) {
            if (hidden.contains(a.pkg)) continue;
            if (!q.isEmpty() && !a.label.toLowerCase(Locale.getDefault()).contains(q)) continue;
            out.add(a);
        }
        final int sort = sp.getInt(AuraStyle.K_SORT, 0);
        final SharedPreferences counts = getSharedPreferences("aura_counts", MODE_PRIVATE);
        Collections.sort(out, new Comparator<App>() {
            @Override
            public int compare(App x, App y) {
                if (sort == 0) {
                    int c = counts.getInt(y.pkg, 0) - counts.getInt(x.pkg, 0);
                    if (c != 0) return c;
                } else if (sort == 2) {
                    int c = Long.compare(LastOpened.get(AuraActivity.this, y.pkg), LastOpened.get(AuraActivity.this, x.pkg));
                    if (c != 0) return c;
                }
                return x.label.compareToIgnoreCase(y.label);
            }
        });
        return out;
    }

    private List<App> favourites() {
        List<String> saved = stringList(AuraStyle.K_FAV);
        List<App> out = new ArrayList<>();
        Set<String> hidden = stringSet(AuraStyle.K_HIDDEN);
        if (sp.contains(AuraStyle.K_FAV)) {
            for (String pkg : saved) { App a = find(pkg); if (a != null && !hidden.contains(pkg)) out.add(a); }
            return out;
        }
        for (String pkg : cfg.dockApps) { App a = find(pkg); if (a != null && !hidden.contains(pkg)) out.add(a); }
        if (out.isEmpty()) for (App a : visible()) { if (out.size() < 6) out.add(a); }
        return out;
    }

    private void launch(App a) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(a.pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(a.pkg);
        if (i == null) { Toast.makeText(this, t("Не удалось открыть", "Cannot open"), Toast.LENGTH_SHORT).show(); return; }
        try {
            startActivity(i);
            LastOpened.record(this, a.pkg);
            SharedPreferences counts = getSharedPreferences("aura_counts", MODE_PRIVATE);
            counts.edit().putInt(a.pkg, counts.getInt(a.pkg, 0) + 1).apply();
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, t("Не удалось открыть", "Cannot open"), Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        try { startActivity(i); }
        catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, t("Недоступно на этом ТВ", "Not available on this TV"), Toast.LENGTH_SHORT).show();
        }
    }

    private void openAndroidSettings() {
        open(SystemSettings.intent(this, Settings.ACTION_SETTINGS));
    }

    private void openInputs() {
        startActivity(new Intent(this, AuraInputsActivity.class));
    }

    // ---------------------------------------------------------------- view helpers

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    private float radius() {
        return dp(new float[]{8, 14, 22}[AuraStyle.clamp(sp.getInt(AuraStyle.K_RADIUS, 1), 3)]);
    }

    private TextView text(String s, float sp, int color, boolean medium) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        v.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return v;
    }

    private LinearLayout box(boolean vertical) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        return l;
    }

    private static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    /** A focusable rounded card around {@code content}. */
    private LinearLayout cardView(String tag, boolean selected, Runnable onClick) {
        LinearLayout c = box(true);
        c.setTag(tag);
        c.setFocusable(true);
        c.setClickable(true);
        c.setBackground(AuraStyle.card(pal, radius(), selected));
        if (onClick != null) c.setOnClickListener(v -> onClick.run());
        return c;
    }

    private ImageView glyph(int kind, int color, int sizeDp) {
        ImageView v = new ImageView(this);
        v.setImageDrawable(new AuraStyle.Glyph(kind, color));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    private ImageView icon(int res, int color, int sizeDp) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setColorFilter(color);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    // ---------------------------------------------------------------- frame

    private void build(String focusTag) {
        pal = AuraStyle.palette(sp);
        root.removeAllViews();
        root.setBackground(AuraStyle.background(pal));
        if (sp.getBoolean(AuraStyle.K_WALL, true)) addWallpaper();

        FrameLayout.LayoutParams rail = new FrameLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.MATCH_PARENT);
        rail.setMargins(dp(14), dp(14), 0, dp(14));
        root.addView(rail(), rail);

        LinearLayout main = box(true);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        mp.setMargins(dp(84), dp(14), dp(28), dp(8));
        main.addView(header(), lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(36), 0));
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        View content = page == HOME ? homePage() : page == APPS ? appsPage() : settingsPage();
        sv.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        main.addView(sv, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f, 0, 10, 0, 0));
        if (sp.getBoolean(AuraStyle.K_HINTS, true)) main.addView(hints(), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 4, 0, 0));
        root.addView(main, mp);
        if (sp.getBoolean(AuraStyle.K_ANIM, true)) {
            main.setAlpha(0f);
            main.animate().alpha(1f).setDuration(140).start();
        }
        View f = focusTag == null ? null : root.findViewWithTag(focusTag);
        if (f == null) f = content;
        f.requestFocus();
    }

    private void go(int target) {
        page = target;
        filter = "";
        build(null);
    }

    private void addWallpaper() {
        File file = AuraWallpapers.pick(this, sp.getInt(AuraStyle.K_OWN, 0));
        if (file == null) {
            File dir = Config.dir(this);
            file = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        }
        if (file == null || !file.isFile()) return;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), o);
            int w = getResources().getDisplayMetrics().widthPixels;
            o.inSampleSize = Math.max(1, o.outWidth / Math.max(1, w));
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeFile(file.getPath(), o);
            if (b == null) return;
            ImageView v = new ImageView(this);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setImageBitmap(b);
            v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View scrim = new View(this);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{AuraStyle.alpha(pal.bg, 245), AuraStyle.alpha(pal.bg, 190), AuraStyle.alpha(pal.bg, 90)});
            scrim.setBackground(g);
            root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (RuntimeException | OutOfMemoryError e) {
            // No wallpaper rather than no Home.
        }
    }

    private View rail() {
        LinearLayout r = box(true);
        r.setGravity(Gravity.CENTER_HORIZONTAL);
        r.setPadding(0, dp(10), 0, dp(10));
        r.setBackground(AuraStyle.shape(AuraStyle.alpha(pal.card, 235), pal.border, dp(20), 1));
        FrameLayout logo = new FrameLayout(this);
        logo.setBackground(AuraStyle.shape(pal.accent, 0, dp(18), 0));
        ImageView play = glyph(AuraStyle.G_PLAY, pal.onAccent, 22);
        logo.addView(play, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        r.addView(logo, lp(dp(36), dp(36), 0, 0, 0, 0, 14));
        r.addView(navItem("nav0", AuraStyle.G_HOME, page == HOME, () -> go(HOME)), lp(dp(40), dp(40), 0, 0, 3, 0, 3));
        r.addView(navItem("nav1", AuraStyle.G_GRID, page == APPS, () -> go(APPS)), lp(dp(40), dp(40), 0, 0, 3, 0, 3));
        if (sp.getBoolean(AuraStyle.K_VOICE, true)) r.addView(navItem("nav2", AuraStyle.G_MIC, false, this::voice), lp(dp(40), dp(40), 0, 0, 3, 0, 3));
        r.addView(new View(this), lp(1, 0, 1f));
        r.addView(navItem("nav3", AuraStyle.G_SLIDERS, page == SETTINGS, () -> go(SETTINGS)), lp(dp(40), dp(40), 0, 0, 0, 0, 0));
        return r;
    }

    private View navItem(String tag, int kind, boolean active, Runnable click) {
        FrameLayout f = new FrameLayout(this);
        f.setTag(tag);
        f.setFocusable(true);
        f.setClickable(true);
        float rad = dp(14);
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, AuraStyle.shape(AuraStyle.mix(pal.card, pal.accent, 0.25f), pal.accent, rad, 3));
        s.addState(new int[]{}, AuraStyle.shape(active ? pal.accent : 0, 0, rad, 0));
        f.setBackground(s);
        f.addView(glyph(kind, active ? pal.onAccent : pal.sub, 22), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        f.setOnClickListener(v -> click.run());
        return f;
    }

    private View header() {
        LinearLayout h = box(false);
        h.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand = box(false);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.addView(text("AURA", 19, pal.text, true));
        TextView tv = text("TV", 8, pal.sub, true);
        brand.addView(tv, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0, 6));
        h.addView(brand, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 24, 0));

        LinearLayout bar = box(false);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), 0, dp(6), 0);
        bar.setBackground(AuraStyle.shape(AuraStyle.alpha(pal.card, 230), pal.border, dp(12), 1));
        bar.addView(glyph(AuraStyle.G_MIC, pal.accent, 16));
        search = new EditText(this);
        search.setTag("search");
        search.setBackground(null);
        search.setSingleLine(true);
        search.setHint(t("Поиск приложений и команд", "Search apps and commands"));
        search.setHintTextColor(pal.sub);
        search.setTextColor(pal.text);
        search.setTextSize(12);
        search.setText(filter);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(dp(8), 0, dp(8), 0);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override
            public void afterTextChanged(Editable e) {
                String value = e.toString();
                if (value.equals(filter)) return;
                filter = value;
                if (page != APPS) { page = APPS; build("search"); if (search != null) search.setSelection(search.length()); }
                else refreshApps();
            }
        });
        search.setOnEditorActionListener((v, action, ev) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH || v.getText().toString().trim().length() < 2) return false;
            startActivity(new Intent(this, AuraSearchActivity.class).putExtra(AuraSearchActivity.EXTRA_QUERY, v.getText().toString().trim()));
            return true;
        });
        bar.addView(search, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        if (sp.getBoolean(AuraStyle.K_VOICE, true)) {
            TextView chip = text(t("Голос", "Voice"), 9, pal.sub, false);
            chip.setTag("voice");
            chip.setFocusable(true);
            chip.setClickable(true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(8), dp(4), dp(8), dp(4));
            chip.setBackground(AuraStyle.card(pal, dp(8), false));
            chip.setOnClickListener(v -> voice());
            bar.addView(chip);
        }
        h.addView(bar, lp(0, dp(32), 1f));

        h.addView(pill("pillL", R.drawable.ic_home_settings, t("Лаунчер", "Launcher"), () -> go(SETTINGS)), lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32), 0, 14, 0, 0, 0));
        h.addView(pill("pillA", R.drawable.ic_settings, "Android", this::openAndroidSettings), lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32), 0, 8, 0, 0, 0));
        ImageView wifi = icon(R.drawable.ic_wifi, pal.sub, 20);
        wifi.setTag("wifi");
        wifi.setFocusable(true);
        wifi.setClickable(true);
        wifi.setBackground(AuraStyle.card(pal, dp(10), false));
        wifi.setPadding(dp(5), dp(5), dp(5), dp(5));
        wifi.setOnClickListener(v -> open(SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS)));
        h.addView(wifi, lp(dp(30), dp(30), 0, 12, 0, 0, 0));
        TextClock clock = new TextClock(this);
        applyClockFormat(clock);
        clock.setTextSize(17);
        clock.setTextColor(pal.text);
        clock.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        h.addView(clock, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0, 0));
        return h;
    }

    private void applyClockFormat(TextClock clock) {
        int mode = sp.getInt(AuraStyle.K_CLOCK, 0);
        if (mode == 1) { clock.setFormat24Hour("HH:mm"); clock.setFormat12Hour("HH:mm"); }
        else if (mode == 2) { clock.setFormat24Hour("h:mm a"); clock.setFormat12Hour("h:mm a"); }
    }

    private View pill(String tag, int iconRes, String label, Runnable click) {
        LinearLayout p = cardView(tag, false, click);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(dp(10), 0, dp(14), 0);
        p.setBackground(AuraStyle.card(pal, dp(12), false));
        p.addView(icon(iconRes, pal.accent, 16));
        p.addView(text(label, 12, pal.text, false), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 7, 0, 0, 0));
        return p;
    }

    private View hints() {
        TextView v = text("← ↑ ↓ →  " + t("Навигация", "Navigate") + "     OK  " + t("Выбрать", "Select") + "     ←  " + t("Назад", "Back"), 9, pal.sub, false);
        v.setGravity(Gravity.END);
        return v;
    }

    private String greeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g = h < 5 ? t("Доброй ночи", "Good night") : h < 12 ? t("Доброе утро", "Good morning")
                : h < 18 ? t("Добрый день", "Good afternoon") : t("Добрый вечер", "Good evening");
        return cfg.greetingName.isEmpty() ? g : g + ", " + cfg.greetingName;
    }

    private TextView title(String s, String sub, LinearLayout into) {
        into.addView(text(s, 26, pal.text, false));
        TextView st = text(sub, 11, pal.sub, false);
        into.addView(st, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 4, 0, 12));
        return st;
    }

    private TextView section(String s) {
        TextView v = text(s, 15, pal.text, true);
        v.setPadding(0, dp(14), 0, dp(8));
        return v;
    }

    // ---------------------------------------------------------------- Home

    private View homePage() {
        LinearLayout c = box(true);
        c.setPadding(0, dp(4), 0, dp(8));
        TextClock big = new TextClock(this);
        applyClockFormat(big);
        big.setTextSize(54);
        big.setTextColor(pal.text);
        big.setIncludeFontPadding(false);
        big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        c.addView(big);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, d MMMM");
        date.setTextSize(14);
        date.setTextColor(pal.sub);
        c.addView(date, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 2, 2, 0, 0));
        if (sp.getBoolean(AuraStyle.K_GREET, true)) {
            TextView g = text(greeting(), 14, pal.accent, true);
            c.addView(g, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 2, 6, 0, 0));
        }
        LinearLayout titleRow = box(false);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(section(t("Ваши приложения", "Your apps")), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(titleRow);
        LinearLayout row = box(false);
        List<App> fav = favourites();
        int shown = 0;
        for (App a : fav) {
            if (shown >= 6) break;
            row.addView(appTile(a, true), lp(0, dp(56), 1f, shown == 0 ? 0 : 8, 0, 0, 0));
            shown++;
        }
        row.addView(moreTile(), lp(0, dp(56), 1f, shown == 0 ? 0 : 8, 0, 0, 0));
        c.addView(row);
        if (sp.getBoolean(AuraStyle.K_QUICK, true)) addQuick(c);
        return c;
    }

    private View moreTile() {
        LinearLayout t = cardView("more", false, () -> go(APPS));
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(10), 0, dp(8), 0);
        t.addView(glyph(AuraStyle.G_GRID, pal.sub, 22));
        t.addView(text(t("Ещё", "More"), 12, pal.text, false), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0, 0));
        return t;
    }

    private View appTile(App a, boolean flat) {
        LinearLayout t = cardView("app:" + a.pkg, false, () -> launch(a));
        t.setPadding(dp(10), dp(flat ? 0 : 10), dp(8), dp(flat ? 0 : 8));
        if (flat) {
            t.setOrientation(LinearLayout.HORIZONTAL);
            t.setGravity(Gravity.CENTER_VERTICAL);
            ImageView ic = new ImageView(this);
            ic.setImageDrawable(a.icon);
            t.addView(ic, lp(dp(28), dp(28), 0));
            TextView l = text(a.label, 12, pal.text, false);
            l.setSingleLine(true);
            l.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.addView(l, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 8, 0, 0, 0));
        } else {
            ImageView ic = new ImageView(this);
            ic.setImageDrawable(a.icon);
            t.addView(ic, lp(dp(32), dp(32), 0));
            TextView l = text(a.label, 13, pal.text, true);
            l.setSingleLine(true);
            l.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.addView(l, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 8, 0, 0));
        }
        if (sp.getBoolean(AuraStyle.K_LONGOK, true)) {
            t.setLongClickable(true);
            t.setOnLongClickListener(v -> { appMenu(a); return true; });
        }
        return t;
    }

    private void addQuick(LinearLayout c) {
        c.addView(section(t("Быстрые действия", "Quick actions")));
        LinearLayout row = box(false);
        row.addView(quickCard("q1", AuraStyle.G_SLIDERS, t("Настройки лаунчера", "Launcher settings"),
                t("Темы, сетка, порядок приложений и пульт", "Themes, grid, app order and remote"),
                t("Настроить AURA", "Customise AURA"), pal.accent, () -> go(SETTINGS)), lp(0, dp(100), 1f));
        row.addView(quickCard("q2", -1, t("Настройки Android", "Android settings"),
                t("Wi-Fi, Bluetooth, экран, звук и система", "Wi-Fi, Bluetooth, display, sound, system"),
                t("Открыть системные", "Open system"), 0xFFA99BFF, this::openAndroidSettings), lp(0, dp(100), 1f, 10, 0, 0, 0));
        row.addView(quickCard("q3", AuraStyle.G_PLUS, t("Источники сигнала", "Inputs"),
                t("HDMI, AV и антенна", "HDMI, AV and tuner"),
                t("Выбрать источник", "Choose input"), 0xFF63B3FF, this::openInputs), lp(0, dp(100), 1f, 10, 0, 0, 0));
        c.addView(row);
    }

    private View quickCard(String tag, int kind, String title, String sub, String action, int tint, Runnable click) {
        LinearLayout q = cardView(tag, false, click);
        q.setPadding(dp(14), dp(12), dp(14), dp(10));
        q.setBackground(AuraStyle.card(pal, radius(), false));
        LinearLayout top = box(false);
        top.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(AuraStyle.shape(AuraStyle.alpha(tint, 45), 0, dp(10), 0));
        if (kind < 0) badge.addView(icon(R.drawable.ic_settings, tint, 18), new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        else badge.addView(glyph(kind, tint, 18), new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        top.addView(badge, lp(dp(32), dp(32), 0));
        LinearLayout texts = box(true);
        texts.addView(text(title, 14, pal.text, true));
        TextView s = text(sub, 10, pal.sub, false);
        s.setMaxLines(2);
        texts.addView(s, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 3, 0, 0));
        top.addView(texts, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 10, 0, 0, 0));
        q.addView(top);
        q.addView(new View(this), lp(1, 0, 1f));
        LinearLayout foot = box(false);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.addView(text(action, 11, tint, true), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        foot.addView(glyph(AuraStyle.G_ARROW, tint, 18));
        q.addView(foot);
        return q;
    }

    // ---------------------------------------------------------------- Apps

    private LinearLayout appGrid;

    private View appsPage() {
        LinearLayout c = box(true);
        LinearLayout head = box(false);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = box(true);
        title(t("Приложения", "Apps"), t("Всё необходимое — в одной удобной сетке", "Everything you need in one grid"), titles);
        head.addView(titles, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String[] sorts = {t("Сначала частые", "Most used first"), t("По алфавиту", "A to Z"), t("Недавние", "Recent first")};
        int sort = AuraStyle.clamp(sp.getInt(AuraStyle.K_SORT, 0), 3);
        LinearLayout chip = cardView("sort", false, null);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(12), 0, dp(14), 0);
        TextView label = text(sorts[sort], 11, pal.text, false);
        chip.addView(label);
        chip.setOnClickListener(v -> {
            int next = (AuraStyle.clamp(sp.getInt(AuraStyle.K_SORT, 0), 3) + 1) % 3;
            sp.edit().putInt(AuraStyle.K_SORT, next).apply();
            label.setText(sorts[next]);
            refreshApps();
        });
        head.addView(chip, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32), 0));
        c.addView(head);
        appGrid = box(true);
        c.addView(appGrid);
        fillGrid();
        if (sp.getBoolean(AuraStyle.K_QUICK, true) && filter.isEmpty()) addQuick(c);
        return c;
    }

    private void refreshApps() {
        if (appGrid != null && page == APPS) fillGrid();
    }

    private void fillGrid() {
        appGrid.removeAllViews();
        List<App> list = visible();
        int cols = Math.max(3, Math.min(8, sp.getInt(AuraStyle.K_COLS, 5)));
        if (list.isEmpty()) {
            appGrid.addView(text(filter.isEmpty() ? t("Нет приложений", "No apps") : t("Ничего не найдено", "Nothing found"), 13, pal.sub, false));
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % cols == 0) {
                row = box(false);
                appGrid.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(78), 0, 0, i == 0 ? 0 : 8, 0, 0));
            }
            row.addView(appTile(list.get(i), false), lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f, i % cols == 0 ? 0 : 8, 0, 0, 0));
        }
        int pad = (cols - list.size() % cols) % cols;
        for (int i = 0; i < pad; i++) row.addView(new View(this), lp(0, 1, 1f, 8, 0, 0, 0));
    }

    private void appMenu(App a) {
        List<String> favs = stringList(AuraStyle.K_FAV);
        boolean isFav = favourites().contains(a);
        String[] items = {t("Открыть", "Open"), isFav ? t("Убрать с главного экрана", "Remove from Home") : t("Добавить на главный экран", "Add to Home"),
                t("Скрыть из списка", "Hide from list"), t("О приложении", "App info")};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(a.label).setItems(items, (d, which) -> {
            if (which == 0) launch(a);
            else if (which == 1) {
                List<String> list = sp.contains(AuraStyle.K_FAV) ? favs : pkgs(favourites());
                if (isFav) list.remove(a.pkg); else if (!list.contains(a.pkg)) list.add(a.pkg);
                saveList(AuraStyle.K_FAV, list);
                build("app:" + a.pkg);
            } else if (which == 2) {
                List<String> hidden = new ArrayList<>(stringSet(AuraStyle.K_HIDDEN));
                hidden.add(a.pkg);
                saveList(AuraStyle.K_HIDDEN, hidden);
                build(null);
            } else {
                Intent info = SystemSettings.appInfo(this, a.pkg);
                if (info != null) open(info);
            }
        }).show();
    }

    private static List<String> pkgs(List<App> list) {
        List<String> out = new ArrayList<>();
        for (App a : list) out.add(a.pkg);
        return out;
    }

    // ---------------------------------------------------------------- Settings

    private View settingsPage() {
        LinearLayout c = box(true);
        title(t("Настройки", "Settings"), t("Отдельно управляйте AURA и системой Android TV", "Manage AURA and Android TV separately"), c);
        LinearLayout cards = box(false);
        cards.addView(bigCard("big1", AuraStyle.G_SLIDERS, -1, t("Настройки лаунчера", "Launcher settings"),
                t("Внешний вид, главный экран, приложения, голосовой поиск и кнопки пульта", "Look, Home, apps, voice search and remote buttons"),
                t("Открыть настройки", "Open settings"), pal.accent, "AURA", () -> {
                    View f = root.findViewWithTag("cat0");
                    if (f != null) f.requestFocus();
                }), lp(0, dp(104), 1f));
        cards.addView(bigCard("big2", -1, R.drawable.ic_settings, t("Настройки Android", "Android settings"),
                t("Wi-Fi, Bluetooth, экран, звук, аккаунты и параметры системы", "Wi-Fi, Bluetooth, display, sound, accounts and system"),
                t("Открыть настройки", "Open settings"), 0xFFA99BFF, t("СИСТЕМА", "SYSTEM"), this::openAndroidSettings), lp(0, dp(104), 1f, 12, 0, 0, 0));
        c.addView(cards);
        c.addView(section(t("Персонализация AURA", "AURA personalisation")));

        LinearLayout split = box(false);
        LinearLayout list = box(true);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        list.setBackground(AuraStyle.shape(AuraStyle.alpha(pal.card, 200), pal.border, dp(20), 1));
        String[] names = "ru".equals(Locale.getDefault().getLanguage()) ? CATEGORIES_RU : CATEGORIES_EN;
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            LinearLayout item = cardView("cat" + i, i == category, () -> { category = index; showPanel(); markCategories(list); View f = panelHolder.getChildAt(0); if (f != null) f.requestFocus(); });
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(12), 0, dp(10), 0);
            item.addView(text(names[i], 12, pal.text, i == category), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            item.addView(glyph(AuraStyle.G_ARROW, pal.sub, 16));
            item.setOnFocusChangeListener((v, focused) -> { if (focused && category != index) { category = index; showPanel(); markCategories(list); } });
            // Right from a section goes to the first setting of the panel, not to the one level with the section.
            item.setOnKeyListener((v, keyCode, ev) -> {
                if (keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                    View f = panelHolder.getChildAt(0) instanceof ViewGroup ? firstFocusable((ViewGroup) panelHolder.getChildAt(0)) : null;
                    if (f != null) f.requestFocus();
                }
                return true;
            });
            list.addView(item, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(38), 0, 0, i == 0 ? 0 : 3, 0, 0));
        }
        split.addView(list, lp(dp(210), ViewGroup.LayoutParams.WRAP_CONTENT, 0));
        panelHolder = new FrameLayout(this) {
            // Down from the last row of the settings stays on it instead of jumping into the section list on the left.
            @Override public View focusSearch(View focused, int direction) {
                View r = super.focusSearch(focused, direction);
                if ((direction == View.FOCUS_DOWN || direction == View.FOCUS_LEFT) && r != null && r != focused) {
                    View p = r;
                    while (p != null && p != this) p = p.getParent() instanceof View ? (View) p.getParent() : null;
                    if (p == null) {
                        if (direction == View.FOCUS_DOWN) return focused;
                        // Left out of the panel goes back to the section being shown, not to whichever row is level with the button.
                        View current = root.findViewWithTag("cat" + category);
                        return current != null ? current : r;
                    }
                }
                return r;
            }
        };
        split.addView(panelHolder, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 12, 0, 0, 0));
        c.addView(split);
        showPanel();
        return c;
    }

    private static View firstFocusable(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.isFocusable() && c.getVisibility() == View.VISIBLE) return c;
            if (c instanceof ViewGroup) {
                View f = firstFocusable((ViewGroup) c);
                if (f != null) return f;
            }
        }
        return null;
    }

    /** Repaints the section list so the highlighted row follows {@code category}. */
    private void markCategories(LinearLayout list) {
        for (int i = 0; i < list.getChildCount(); i++) {
            LinearLayout item = (LinearLayout) list.getChildAt(i);
            item.setBackground(AuraStyle.card(pal, radius(), i == category));
            View label = item.getChildAt(0);
            if (label instanceof TextView) ((TextView) label).setTypeface(null, i == category ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private View bigCard(String tag, int kind, int iconRes, String title, String sub, String action, int tint, String badge, Runnable click) {
        LinearLayout q = cardView(tag, false, click);
        q.setPadding(dp(14), dp(12), dp(14), dp(10));
        LinearLayout top = box(false);
        FrameLayout b = new FrameLayout(this);
        b.setBackground(AuraStyle.shape(AuraStyle.alpha(tint, 45), 0, dp(12), 0));
        if (kind >= 0) b.addView(glyph(kind, tint, 22), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        else b.addView(icon(iconRes, tint, 22), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        top.addView(b, lp(dp(38), dp(38), 0));
        LinearLayout texts = box(true);
        texts.addView(text(title, 17, pal.text, false));
        TextView s = text(sub, 10, pal.sub, false);
        s.setMaxLines(2);
        texts.addView(s, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 4, 0, 0));
        top.addView(texts, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, 12, 0, 8, 0));
        TextView tagView = text(badge, 8, tint, true);
        tagView.setPadding(dp(8), dp(3), dp(8), dp(3));
        tagView.setBackground(AuraStyle.shape(AuraStyle.alpha(tint, 40), 0, dp(6), 0));
        top.addView(tagView, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));
        q.addView(top);
        q.addView(new View(this), lp(1, 0, 1f));
        LinearLayout foot = box(false);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.addView(text(action, 11, tint, true), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        foot.addView(glyph(AuraStyle.G_ARROW, tint, 18));
        q.addView(foot);
        return q;
    }

    private void showPanel() {
        panelHolder.removeAllViews();
        LinearLayout p = box(true);
        p.setPadding(dp(16), dp(12), dp(16), dp(12));
        p.setBackground(AuraStyle.shape(AuraStyle.alpha(pal.card, 200), pal.border, dp(20), 1));
        String[] names = "ru".equals(Locale.getDefault().getLanguage()) ? CATEGORIES_RU : CATEGORIES_EN;
        p.addView(text(names[category], 18, pal.text, false), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0, 8));
        switch (category) {
            case 0: appearancePanel(p); break;
            case 1: homePanel(p); break;
            case 2: appsPanel(p); break;
            case 3: voicePanel(p); break;
            case 4: remotePanel(p); break;
            default: aboutPanel(p);
        }
        panelHolder.addView(p, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void appearancePanel(LinearLayout p) {
        p.addView(text(t("Тема интерфейса", "Interface theme"), 10, pal.sub, false));
        LinearLayout themes = box(false);
        String[] names = "ru".equals(Locale.getDefault().getLanguage()) ? AuraStyle.THEME_NAMES_RU : AuraStyle.THEME_NAMES_EN;
        int[] previewBg = {0xFF0C1322, 0xFF22262C, 0xFFE4E9F0};
        int[] previewBar = {0xFF7CF0C0, 0xFF7B828C, 0xFF1F2A33};
        int current = AuraStyle.clamp(sp.getInt(AuraStyle.K_THEME, 0), 3);
        for (int i = 0; i < 3; i++) {
            final int index = i;
            LinearLayout cell = box(true);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout thumb = new FrameLayout(this);
            thumb.setTag("theme" + i);
            thumb.setFocusable(true);
            thumb.setClickable(true);
            GradientDrawable normal = AuraStyle.shape(previewBg[i], i == current ? pal.accent : pal.border, dp(12), i == current ? 3 : 1);
            android.graphics.drawable.StateListDrawable sd = new android.graphics.drawable.StateListDrawable();
            sd.addState(new int[]{android.R.attr.state_focused}, AuraStyle.shape(previewBg[i], pal.text, dp(12), 3));
            sd.addState(new int[]{}, normal);
            thumb.setBackground(sd);
            View bar = new View(this);
            bar.setBackground(AuraStyle.shape(previewBar[i], 0, dp(5), 0));
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(54), dp(18), Gravity.START | Gravity.BOTTOM);
            bp.setMargins(dp(10), 0, 0, dp(10));
            thumb.addView(bar, bp);
            thumb.setOnClickListener(v -> { sp.edit().putInt(AuraStyle.K_THEME, index).apply(); build("theme" + index); });
            cell.addView(thumb, lp(dp(112), dp(58), 0));
            cell.addView(text(names[i], 11, i == current ? pal.text : pal.sub, i == current), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 5, 0, 0));
            themes.addView(cell, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, i == 0 ? 0 : 10, 0, 0, 0));
        }
        p.addView(themes, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 4, 0, 0));
        p.addView(text(t("Цвет фокуса", "Focus colour"), 10, pal.sub, false), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 4, 0, 4));
        LinearLayout colors = box(false);
        int currentFocus = AuraStyle.clamp(sp.getInt(AuraStyle.K_FOCUS, 0), AuraStyle.FOCUS_COLORS.length);
        for (int i = 0; i < AuraStyle.FOCUS_COLORS.length; i++) {
            final int index = i;
            View dot = new View(this);
            dot.setTag("focus" + i);
            dot.setFocusable(true);
            dot.setClickable(true);
            android.graphics.drawable.StateListDrawable sd = new android.graphics.drawable.StateListDrawable();
            GradientDrawable ring = AuraStyle.shape(AuraStyle.FOCUS_COLORS[i], pal.text, dp(15), 3);
            ring.setShape(GradientDrawable.OVAL);
            GradientDrawable chosen = AuraStyle.shape(AuraStyle.FOCUS_COLORS[i], pal.accent == AuraStyle.FOCUS_COLORS[i] ? pal.text : 0, dp(15), 2);
            chosen.setShape(GradientDrawable.OVAL);
            sd.addState(new int[]{android.R.attr.state_focused}, ring);
            sd.addState(new int[]{}, i == currentFocus ? chosen : dotPlain(AuraStyle.FOCUS_COLORS[i]));
            dot.setBackground(sd);
            dot.setOnClickListener(v -> { sp.edit().putInt(AuraStyle.K_FOCUS, index).apply(); build("focus" + index); });
            colors.addView(dot, lp(dp(26), dp(26), 0, i == 0 ? 0 : 10, 0, 0, 0));
        }
        p.addView(colors, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0, 8));
        toggle(p, AuraStyle.K_HINTS, true, t("Подсказки управления на экране", "On-screen control hints"), t("Показывать действия кнопок пульта", "Show what the remote buttons do"), true);
        choice(p, AuraStyle.K_RADIUS, 1, t("Скругление углов", "Corner roundness"), t("Форма карточек и плиток", "Shape of cards and tiles"),
                new String[]{t("Малое", "Small"), t("Среднее", "Medium"), t("Большое", "Large")}, true);
        List<File> own = AuraWallpapers.list(this);
        if (own.isEmpty()) {
            File folder = AuraWallpapers.appFolder(this);
            action(p, "ownwall", t("Свои обои", "Your wallpapers"),
                    t("Положите картинки в папку: ", "Put pictures in: ") + (folder == null ? "Android/data/<app>/files/Wallpapers" : folder.getPath().replace("/storage/emulated/0/", "")),
                    "", () -> { });
        } else {
            String[] ownNames = new String[Math.min(own.size(), 40) + 1];
            ownNames[0] = t("Из темы", "Theme's");
            for (int i = 1; i < ownNames.length; i++) ownNames[i] = own.get(i - 1).getName();
            choice(p, AuraStyle.K_OWN, 0, t("Свои обои", "Your wallpapers"), t("OK листает картинки из папки", "OK steps through the pictures of your folder"), ownNames, true);
        }
        toggle(p, AuraStyle.K_WALL, true, t("Показывать обои", "Show wallpaper"), t("Обои темы или ваша картинка из папки", "The theme's wallpaper or your own picture"), true);
        toggle(p, AuraStyle.K_ANIM, true, t("Анимации", "Animations"), t("Плавное появление страниц; выключите на слабых ТВ", "Soft page fades; turn off on slow TVs"), false);
    }

    private GradientDrawable dotPlain(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private void homePanel(LinearLayout p) {
        choice(p, AuraStyle.K_CLOCK, 0, t("Формат часов", "Clock format"), t("Для часов на главном экране и в шапке", "For the Home and top bar clocks"),
                new String[]{t("Системный", "System"), "24 " + t("ч", "h"), "12 " + t("ч", "h")}, true);
        toggle(p, AuraStyle.K_GREET, true, t("Приветствие", "Greeting"), t("«Добрый вечер» под часами", "A greeting under the clock"), true);
        toggle(p, AuraStyle.K_QUICK, true, t("Быстрые действия", "Quick actions"), t("Карточки настроек и источников внизу", "Cards for settings and inputs at the bottom"), true);
        action(p, "favs", t("Приложения на главном экране", "Apps on Home"), t("До шести плиток в ряду", "Up to six tiles in a row"),
                favourites().size() + t(" выбрано", " chosen"), () -> pickApps(t("Приложения на главном экране", "Apps on Home"), pkgs(favourites()),
                        chosen -> { saveList(AuraStyle.K_FAV, chosen); build("row:favs"); }));
    }

    private void appsPanel(LinearLayout p) {
        choice(p, AuraStyle.K_COLS, 5, t("Плотность сетки", "Grid density"), t("Сколько колонок в списке приложений", "Columns in the app list"),
                new String[]{"4", "5", "6", "7"}, false, 4);
        choice(p, AuraStyle.K_SORT, 0, t("Порядок приложений", "App order"), t("Как сортируется сетка", "How the grid is sorted"),
                new String[]{t("Сначала частые", "Most used"), t("По алфавиту", "A to Z"), t("Недавние", "Recent")}, false);
        action(p, "hidden", t("Скрытые приложения", "Hidden apps"), t("Не показывать в списке и на главном экране", "Keep out of the list and Home"),
                stringSet(AuraStyle.K_HIDDEN).size() + t(" скрыто", " hidden"), () -> pickApps(t("Скрыть приложения", "Hide apps"), new ArrayList<>(stringSet(AuraStyle.K_HIDDEN)),
                        chosen -> { saveList(AuraStyle.K_HIDDEN, chosen); build("row:hidden"); }));
        toggle(p, AuraStyle.K_ALL, false, t("Все приложения", "All apps"), t("Включая приложения не для ТВ", "Including apps not made for TV"), false, true);
        toggle(p, AuraStyle.K_LONGOK, true, t("Меню по долгому нажатию OK", "Long-press OK menu"), t("Открыть, на главный экран, скрыть, о приложении", "Open, add to Home, hide, app info"), false);
    }

    private void voicePanel(LinearLayout p) {
        toggle(p, AuraStyle.K_VOICE, true, t("Голосовой поиск", "Voice search"), t("Системный распознаватель речи; AURA ничего не записывает", "The system speech recogniser; AURA records nothing"), true);
        action(p, "voicenow", t("Попробовать", "Try it"), t("Скажите название приложения: «Открой YouTube»", "Say an app name: \"Open YouTube\""), "", this::voice);
    }

    private void remotePanel(LinearLayout p) {
        action(p, "inputs", t("Источники сигнала", "Inputs"), t("HDMI, AV и антенна", "HDMI, AV and tuner"), "", this::openInputs);
        action(p, "classicset", t("Кнопки пульта и расширенные настройки", "Remote buttons and advanced settings"),
                t("Классические настройки лаунчера", "The classic launcher settings"), "",
                () -> open(new Intent(this, LauncherSettingsActivity.class)));
    }

    private void aboutPanel(LinearLayout p) {
        String version = "";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (PackageManager.NameNotFoundException ignored) { }
        p.addView(text("AURA TV · " + version, 12, pal.sub, false), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0, 8));
        action(p, "stockhome", t("Родной экран ТВ", "The TV's own home screen"), t("Открыть главный экран, с которым пришёл ТВ", "Open the home screen the TV came with"), "", this::openStockHome);
        action(p, "reset", t("Сбросить настройки AURA", "Reset AURA settings"), t("Тема, цвета, сетка, избранное и скрытые приложения", "Theme, colours, grid, shortcuts and hidden apps"), "", () -> {
            SharedPreferences.Editor e = sp.edit();
            for (String key : sp.getAll().keySet()) if (key.startsWith("aura_") && !key.equals(AuraStyle.K_ENABLED)) e.remove(key);
            e.apply();
            build("row:reset");
        });
    }

    // ---------------------------------------------------------------- settings rows

    private LinearLayout row(LinearLayout panel, String tag, String title, String sub) {
        LinearLayout r = cardView("row:" + tag, false, null);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(6), dp(12), dp(6));
        r.setBackground(AuraStyle.card(pal, dp(12), false));
        LinearLayout texts = box(true);
        texts.addView(text(title, 12, pal.text, true));
        texts.addView(text(sub, 9, pal.sub, false), lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 2, 0, 0));
        r.addView(texts, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(r, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 5, 0, 0));
        return r;
    }

    private void toggle(LinearLayout p, String key, boolean def, String title, String sub, boolean rebuild) {
        toggle(p, key, def, title, sub, rebuild, false);
    }

    private void toggle(LinearLayout p, String key, boolean def, String title, String sub, boolean rebuild, boolean reload) {
        LinearLayout r = row(p, key, title, sub);
        FrameLayout track = new FrameLayout(this);
        View thumb = new View(this);
        thumb.setBackground(dotPlain(0xFF0B1210));
        track.addView(thumb, new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER_VERTICAL));
        r.addView(track, lp(dp(38), dp(22), 0));
        paintToggle(track, thumb, sp.getBoolean(key, def));
        r.setOnClickListener(v -> {
            boolean on = !sp.getBoolean(key, def);
            sp.edit().putBoolean(key, on).apply();
            if (reload) loadApps();
            if (rebuild) build("row:" + key);
            else paintToggle(track, thumb, on);
        });
    }

    private void paintToggle(FrameLayout track, View thumb, boolean on) {
        track.setBackground(AuraStyle.shape(on ? pal.accent : AuraStyle.mix(pal.card, pal.sub, 0.45f), 0, dp(11), 0));
        thumb.setBackground(dotPlain(on ? pal.onAccent : pal.card));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) thumb.getLayoutParams();
        lp.gravity = Gravity.CENTER_VERTICAL | (on ? Gravity.END : Gravity.START);
        lp.setMargins(dp(3), 0, dp(3), 0);
        thumb.setLayoutParams(lp);
    }

    private void choice(LinearLayout p, String key, int def, String title, String sub, String[] options, boolean rebuild) {
        choice(p, key, def, title, sub, options, rebuild, 0);
    }

    /** A row that steps through {@code options} on OK. {@code base} is the number the first option stands for (columns start at 4). */
    private void choice(LinearLayout p, String key, int def, String title, String sub, String[] options, boolean rebuild, int base) {
        LinearLayout r = row(p, key, title, sub);
        TextView value = text("", 12, pal.accent, true);
        r.addView(value);
        int cur = AuraStyle.clamp(sp.getInt(key, def) - base, options.length);
        value.setText(options[cur]);
        r.setOnClickListener(v -> {
            int next = (AuraStyle.clamp(sp.getInt(key, def) - base, options.length) + 1) % options.length;
            sp.edit().putInt(key, next + base).apply();
            if (rebuild) build("row:" + key);
            else value.setText(options[next]);
        });
    }

    private void action(LinearLayout p, String tag, String title, String sub, String value, Runnable click) {
        LinearLayout r = row(p, tag, title, sub);
        if (!value.isEmpty()) r.addView(text(value, 11, pal.accent, true));
        r.addView(glyph(AuraStyle.G_ARROW, pal.sub, 16), lp(dp(16), dp(16), 0, 8, 0, 0, 0));
        r.setOnClickListener(v -> click.run());
    }

    private interface Chosen { void done(List<String> chosen); }

    private void pickApps(String title, List<String> selected, Chosen result) {
        final List<App> all = new ArrayList<>(apps);
        Collections.sort(all, (x, y) -> x.label.compareToIgnoreCase(y.label));
        final String[] labels = new String[all.size()];
        final boolean[] on = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) { labels[i] = all.get(i).label; on[i] = selected.contains(all.get(i).pkg); }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(title)
                .setMultiChoiceItems(labels, on, (d, which, checked) -> on[which] = checked)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    List<String> out = new ArrayList<>();
                    // Keep the order the owner already had, then add the new ones.
                    for (String pkg : selected) { App a = find(pkg); if (a != null && on[all.indexOf(a)]) out.add(pkg); }
                    for (int i = 0; i < all.size(); i++) if (on[i] && !out.contains(all.get(i).pkg)) out.add(all.get(i).pkg);
                    result.done(out);
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    /** Opens the TV's own home screen once (Google TV Home and the like); AURA stays the default and nothing is changed. */
    private void openStockHome() {
        PackageManager pm = getPackageManager();
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || pkg.equals("android") || pkg.contains("fallbackhome") || ri.activityInfo.name.contains("FallbackHome")
                    || pkg.contains("setupwraith") || pkg.contains("provision")) continue;
            try {
                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setComponent(new ComponentName(pkg, ri.activityInfo.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        Toast.makeText(this, t("Родной экран на этом ТВ отключён", "The TV's own home screen is switched off on this TV"), Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- voice

    private void voice() {
        // AURA's own search screen with the voice input in it; the words are the TV's speech recogniser's.
        startActivity(new Intent(this, AuraSearchActivity.class).putExtra(AuraSearchActivity.EXTRA_VOICE, true));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SEARCH && search != null) { search.requestFocus(); return true; }
        return super.onKeyDown(keyCode, event);
    }
}
