package com.gathin.tvhome;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The person's own wallpapers for AURA: pictures put in the app's own "Wallpapers" folder (no permission needed) or,
 * where the system lets the launcher read it, in Pictures/AURA. The launcher only reads them to show on screen.
 */
final class AuraWallpapers {
    private AuraWallpapers() { }

    /** The folder people copy pictures to over USB, a file manager or adb. */
    static File appFolder(Context c) {
        File base = c.getExternalFilesDir(null);
        return base == null ? null : new File(base, "Wallpapers");
    }

    static File picturesFolder() {
        try {
            return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AURA");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final String K_SEEDED = "aura_walls_seeded";

    /**
     * The first time, puts the launcher's own pictures (made for it, no one else's work) into the folder, so the folder
     * exists and "Your wallpapers" works at once. Pictures a person deletes are not brought back.
     */
    static void seed(Context c) {
        android.content.SharedPreferences sp = AuraStyle.prefs(c);
        if (sp.getBoolean(K_SEEDED, false)) return;
        File dir = appFolder(c);
        if (dir == null) return;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            String[] names = c.getAssets().list("aura_wallpapers");
            if (names != null) for (String n : names) {
                File out = new File(dir, n);
                if (out.exists()) continue;
                try (java.io.InputStream in = c.getAssets().open("aura_wallpapers/" + n);
                     java.io.FileOutputStream os = new java.io.FileOutputStream(out)) {
                    byte[] buf = new byte[16384];
                    int r;
                    while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
                }
            }
            sp.edit().putBoolean(K_SEEDED, true).apply();
        } catch (java.io.IOException | RuntimeException ignored) { }
    }

    /** Every picture found, the app folder first; at most 40, none over 10 MB. */
    static List<File> list(Context c) {
        seed(c);
        List<File> all = new ArrayList<>();
        for (File dir : new File[]{appFolder(c), picturesFolder()}) {
            for (File f : WallpaperRotation.images(dir)) {
                // A file that is not a picture (broken, half copied) is left out rather than shown as an empty wallpaper.
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
                if (o.outWidth <= 0 || o.outHeight <= 0) continue;
                all.add(f);
                if (all.size() >= 40) return all;
            }
        }
        return all;
    }

    /** The picture behind a choice (1 = the first one); null for 0 or one that is gone. */
    static File pick(Context c, int choice) {
        if (choice <= 0) return null;
        List<File> all = list(c);
        return choice <= all.size() ? all.get(choice - 1) : null;
    }
}
