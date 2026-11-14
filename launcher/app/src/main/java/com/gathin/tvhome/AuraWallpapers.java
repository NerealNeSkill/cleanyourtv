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

    /** Every picture found, the app folder first; at most 40, none over 10 MB. */
    static List<File> list(Context c) {
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
