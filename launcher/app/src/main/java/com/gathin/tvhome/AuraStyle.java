package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The AURA look: palettes (Orbit, Graphite, Light), focus colours, the choices stored in Launcher settings'
 * SharedPreferences, and the few drawables the AURA screens draw themselves. Plain Java, no assets and no
 * network, so it adds almost nothing to the APK and the memory it uses is a few small objects.
 */
final class AuraStyle {
    static final String K_ENABLED = "aura_enabled";
    static final String K_THEME = "aura_theme";
    static final String K_FOCUS = "aura_focus";
    static final String K_HINTS = "aura_hints";
    static final String K_COLS = "aura_cols";
    static final String K_SORT = "aura_sort";
    static final String K_ALL = "aura_all_apps";
    static final String K_RADIUS = "aura_radius";
    static final String K_ANIM = "aura_anim";
    static final String K_CLOCK = "aura_clock";
    static final String K_GREET = "aura_greeting";
    static final String K_QUICK = "aura_quick";
    static final String K_WALL = "aura_wallpaper";
    static final String K_VOICE = "aura_voice";
    static final String K_LONGOK = "aura_long_ok";
    static final String K_FAV = "aura_fav";
    static final String K_HIDDEN = "aura_hidden";
    static final String K_STARTED = "aura_started";
    static final String K_OK = "aura_ok";
    private static final String CONFIG_KEY = "aura_ui";

    private AuraStyle() { }

    /** Russian where the TV speaks Russian, English otherwise. */
    static String t(String ru, String en) {
        return "ru".equals(Locale.getDefault().getLanguage()) ? ru : en;
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    /** Keys a pushed config.json can carry, with how each is stored in the preferences. */
    private static final String[] INT_KEYS = {K_THEME, K_FOCUS, K_COLS, K_SORT, K_RADIUS, K_CLOCK};
    private static final String[] BOOL_KEYS = {K_HINTS, K_ALL, K_ANIM, K_GREET, K_QUICK, K_WALL, K_VOICE, K_LONGOK};
    private static final String K_SIG = "aura_overlay_sig";

    /**
     * Applies the aura_* keys of config.json (pushed from the phone app or by a theme) to the preferences, once per
     * change of the file: the phone's latest push wins over what was chosen on the TV before it, and choices made on
     * the TV afterwards stay until the next push. aura_ui counts as AURA's on/off switch.
     */
    static void syncOverlay(Context c) {
        File dir = Config.dir(c);
        if (dir == null) return;
        File file = new File(dir, Config.FILE_NAME);
        SharedPreferences sp = prefs(c);
        String sig = file.isFile() ? file.length() + ":" + file.lastModified() : "";
        if (sig.equals(sp.getString(K_SIG, ""))) return;
        SharedPreferences.Editor e = sp.edit().putString(K_SIG, sig);
        if (!sig.isEmpty() && file.length() <= 256 * 1024) {
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] bytes = new byte[(int) file.length()];
                int n = 0, r;
                while (n < bytes.length && (r = in.read(bytes, n, bytes.length - n)) > 0) n += r;
                JSONObject json = new JSONObject(new String(bytes, 0, n, StandardCharsets.UTF_8));
                if (json.has(CONFIG_KEY)) {
                    Object v = json.opt(CONFIG_KEY);
                    if (v instanceof Boolean) e.putBoolean(K_ENABLED, (Boolean) v);
                }
                for (String key : INT_KEYS) {
                    Object v = json.opt(key);
                    if (v instanceof Integer) e.putInt(key, (Integer) v);
                }
                for (String key : BOOL_KEYS) {
                    Object v = json.opt(key);
                    if (v instanceof Boolean) e.putBoolean(key, (Boolean) v);
                }
                for (String key : new String[]{K_FAV, K_HIDDEN}) {
                    org.json.JSONArray list = json.optJSONArray(key);
                    if (list == null) continue;
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < list.length() && i < 128; i++) {
                        String pkg = list.optString(i, "");
                        if (LibraryApps.validPackage(pkg)) sb.append(sb.length() == 0 ? "" : ",").append(pkg);
                    }
                    e.putString(key, sb.toString());
                }
            } catch (IOException | org.json.JSONException | RuntimeException ex) {
                // An unreadable file changes nothing.
            }
        }
        e.apply();
    }

    /** AURA's on/off: the preferences, which hold the last config.json push and the TV's own switch, else aura_ui in config.xml. */
    static boolean enabled(Context c) {
        syncOverlay(c);
        SharedPreferences sp = prefs(c);
        return sp.contains(K_ENABLED) ? sp.getBoolean(K_ENABLED, false) : c.getResources().getBoolean(R.bool.aura_ui);
    }

    /**
     * Whether Home should open AURA. If the last start of AURA never reached its first resume (a crash), Home stays
     * on the classic screen until AURA is opened by hand, so a bug here can never leave the TV without a Home.
     */
    static boolean shouldRun(Context c) {
        if (!enabled(c)) return false;
        SharedPreferences sp = prefs(c);
        return !(sp.getBoolean(K_STARTED, false) && !sp.getBoolean(K_OK, true));
    }

    /** The colours of one theme. */
    static final class Palette {
        final int bg, glow, card, border, text, sub, accent, onAccent;
        final boolean light;

        Palette(int bg, int glow, int card, int border, int text, int sub, int accent, int onAccent, boolean light) {
            this.bg = bg; this.glow = glow; this.card = card; this.border = border;
            this.text = text; this.sub = sub; this.accent = accent; this.onAccent = onAccent; this.light = light;
        }
    }

    static final String[] THEME_NAMES_RU = {"Орбита", "Графит", "Светлая"};
    static final String[] THEME_NAMES_EN = {"Orbit", "Graphite", "Light"};
    static final int[] FOCUS_COLORS = {0xFF7CF0C0, 0xFF63B3FF, 0xFFA99BFF, 0xFFFFC857, 0xFFFF6B81};

    static Palette palette(SharedPreferences sp) {
        int accent = FOCUS_COLORS[clamp(sp.getInt(K_FOCUS, 0), FOCUS_COLORS.length)];
        switch (clamp(sp.getInt(K_THEME, 0), 3)) {
            case 1: return new Palette(0xFF0F1114, 0xFF1B2028, 0xFF1A1D22, 0xFF2A2E35, 0xFFECEFF3, 0xFF8A9099, accent, 0xFF0A1F18, false);
            case 2: return new Palette(0xFFE9EDF2, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFD3DAE3, 0xFF101820, 0xFF5B6675, accent, 0xFF0A1F18, true);
            default: return new Palette(0xFF070B14, 0xFF241A52, 0xFF121A24, 0xFF1E2A38, 0xFFF2F6FA, 0xFF8794A6, accent, 0xFF06201A, false);
        }
    }

    static int clamp(int v, int size) {
        return v < 0 || v >= size ? 0 : v;
    }

    static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (a << 24);
    }

    /** Screen background: the base colour with a soft glow in the top right, drawn once. */
    static Drawable background(Palette p) {
        GradientDrawable base = new GradientDrawable();
        base.setColor(p.bg);
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.92f, 0.05f);
        glow.setGradientRadius(900f);
        glow.setColors(new int[]{alpha(p.glow, p.light ? 255 : 220), alpha(p.glow, 0)});
        return new LayerDrawable(new Drawable[]{base, glow});
    }

    /** A rounded card that gains an accent outline and a lighter fill when focused. */
    static Drawable card(Palette p, float radius, boolean selected) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, shape(mix(p.card, p.accent, p.light ? 0.16f : 0.14f), p.accent, radius, 3));
        if (selected) s.addState(new int[]{}, shape(mix(p.card, p.accent, 0.10f), alpha(p.accent, 120), radius, 2));
        else s.addState(new int[]{}, shape(p.card, p.border, radius, 1));
        return s;
    }

    static GradientDrawable shape(int fill, int stroke, float radius, int strokeWidth) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (stroke != 0) g.setStroke(strokeWidth, stroke);
        return g;
    }

    static int mix(int a, int b, float f) {
        int r = (int) (((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = (int) (((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = (int) ((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    static final int G_HOME = 0, G_GRID = 1, G_MIC = 2, G_SLIDERS = 3, G_PLAY = 4, G_PLUS = 5, G_ARROW = 6;

    /** Simple line icons, drawn with a Paint in whatever size and colour they are given. */
    static final class Glyph extends Drawable {
        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Glyph(int kind, int color) {
            this.kind = kind;
            paint.setColor(color);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidateSelf();
        }

        @Override
        public void draw(Canvas c) {
            float w = getBounds().width(), h = getBounds().height();
            c.save();
            c.translate(getBounds().left, getBounds().top);
            float u = Math.min(w, h) / 24f;
            c.scale(u, u);
            c.translate((w / u - 24f) / 2f, (h / u - 24f) / 2f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            path.reset();
            switch (kind) {
                case G_HOME:
                    path.moveTo(3.5f, 11.5f); path.lineTo(12, 4); path.lineTo(20.5f, 11.5f);
                    path.moveTo(6, 10f); path.lineTo(6, 20); path.lineTo(10, 20); path.lineTo(10, 14.5f);
                    path.lineTo(14, 14.5f); path.lineTo(14, 20); path.lineTo(18, 20); path.lineTo(18, 10f);
                    c.drawPath(path, paint);
                    break;
                case G_GRID:
                    paint.setStyle(Paint.Style.FILL);
                    for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) c.drawCircle(5.5f + x * 6.5f, 5.5f + y * 6.5f, 1.9f, paint);
                    break;
                case G_MIC:
                    c.drawRoundRect(new RectF(9, 3, 15, 14), 3, 3, paint);
                    path.moveTo(5.5f, 11); path.quadTo(5.5f, 17.5f, 12, 17.5f); path.quadTo(18.5f, 17.5f, 18.5f, 11);
                    path.moveTo(12, 17.5f); path.lineTo(12, 21); path.moveTo(8.5f, 21); path.lineTo(15.5f, 21);
                    c.drawPath(path, paint);
                    break;
                case G_SLIDERS:
                    c.drawLine(4, 7, 20, 7, paint); c.drawLine(4, 12, 20, 12, paint); c.drawLine(4, 17, 20, 17, paint);
                    paint.setStyle(Paint.Style.FILL);
                    c.drawCircle(9, 7, 2.6f, paint); c.drawCircle(15, 12, 2.6f, paint); c.drawCircle(8, 17, 2.6f, paint);
                    break;
                case G_PLAY:
                    paint.setStyle(Paint.Style.FILL);
                    path.moveTo(9, 6.5f); path.lineTo(18, 12); path.lineTo(9, 17.5f); path.close();
                    c.drawPath(path, paint);
                    break;
                case G_PLUS:
                    c.drawLine(12, 5, 12, 19, paint); c.drawLine(5, 12, 19, 12, paint);
                    break;
                default:
                    c.drawLine(4, 12, 19, 12, paint);
                    path.moveTo(13, 6); path.lineTo(19, 12); path.lineTo(13, 18);
                    c.drawPath(path, paint);
            }
            c.restore();
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
