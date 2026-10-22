package com.mihir.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The slideshow photos, shared by the home screen and the screensaver. */
final class Photos {
    /** The photo folder on the TV. Anything in it replaces the bundled nature photos. */
    static final File DIR = new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Wallpapers");

    private Photos() { }

    /** Photos in the TV's Pictures/Wallpapers folder, sorted by name; the bundled ones if it has none. */
    static String[] list(Context ctx) {
        List<String> found = new ArrayList<>();
        File[] files = DIR.listFiles();
        if (files != null) for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (f.isFile() && !n.startsWith(".") && (n.endsWith(".jpg") || n.endsWith(".jpeg")
                    || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".heic"))) found.add(f.getPath());
        }
        if (found.isEmpty()) {
            try {
                String[] bundled = ctx.getAssets().list("wallpapers");
                if (bundled != null) for (String n : bundled) found.add("asset:wallpapers/" + n);
            } catch (IOException ignored) { }
        }
        Collections.sort(found);
        return found.toArray(new String[0]);
    }

    private static InputStream open(Context ctx, String path) throws IOException {
        return path.startsWith("asset:") ? ctx.getAssets().open(path.substring(6)) : new FileInputStream(path);
    }

    /**
     * Decode a photo at roughly screen size (phone photos are 12+ MP; the TV has 1 GB), upright per its
     * EXIF, and screen-shaped: a portrait or odd-shaped photo is shown whole over a blurred copy of itself.
     */
    static Bitmap load(Context ctx, String path, int sw, int sh) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = open(ctx, path)) { BitmapFactory.decodeStream(in, null, o); }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int rotation = 0;
        try (InputStream in = open(ctx, path)) {
            switch (new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: rotation = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
                default: break;
            }
        } catch (Exception ignored) { } // no or unreadable EXIF: show as stored
        boolean sideways = rotation == 90 || rotation == 270;
        int w = sideways ? o.outHeight : o.outWidth, h = sideways ? o.outWidth : o.outHeight;
        int sample = 1;
        while (w / (sample * 2) >= sw && h / (sample * 2) >= sh) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bm;
        try (InputStream in = open(ctx, path)) { bm = BitmapFactory.decodeStream(in, null, d); }
        if (bm == null) return null;
        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            Bitmap r = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            if (r != bm) bm.recycle();
            bm = r;
        }
        float photo = (float) bm.getWidth() / bm.getHeight(), screen = (float) sw / sh;
        if (Math.abs(photo - screen) / screen < 0.2f) return bm; // close enough: CENTER_CROP fills the screen

        Bitmap out = Bitmap.createBitmap(sw, sh, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG); // dither: no bands in the 16-bit blur
        // Background: the same photo, cover-scaled, blurred by shrinking it to a few dozen pixels, darkened.
        float cover = Math.max((float) sw / bm.getWidth(), (float) sh / bm.getHeight());
        Bitmap blurred = blur(bm);
        float bw = bm.getWidth() * cover, bh = bm.getHeight() * cover;
        c.drawBitmap(blurred, null, new RectF((sw - bw) / 2, (sh - bh) / 2, (sw + bw) / 2, (sh + bh) / 2), p);
        blurred.recycle();
        c.drawColor(0x73000000);
        // Foreground: the whole photo, fitted.
        float fit = Math.min((float) sw / bm.getWidth(), (float) sh / bm.getHeight());
        float fw = bm.getWidth() * fit, fh = bm.getHeight() * fit;
        c.drawBitmap(bm, null, new RectF((sw - fw) / 2, (sh - fh) / 2, (sw + fw) / 2, (sh + fh) / 2), p);
        bm.recycle();
        return out;
    }

    /**
     * A soft blur without RenderScript: shrink to a few dozen pixels, box-blur those, then grow back in 2x
     * steps. Growing straight from the tiny image leaves visible blocks.
     */
    private static Bitmap blur(Bitmap src) {
        int tw = Math.max(2, src.getWidth() / 32), th = Math.max(2, src.getHeight() / 32);
        Bitmap small = Bitmap.createScaledBitmap(src, tw, th, true).copy(Bitmap.Config.ARGB_8888, true);
        int[] px = new int[tw * th], tmp = new int[tw * th];
        small.getPixels(px, 0, tw, 0, 0, tw, th);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, tmp, tw, th, 1, 0); // horizontal
            boxBlur(tmp, px, tw, th, 0, 1); // vertical
        }
        small.setPixels(px, 0, tw, 0, 0, tw, th);
        Bitmap cur = small;
        while (cur.getWidth() * 2 <= src.getWidth() / 2) {
            Bitmap up = Bitmap.createScaledBitmap(cur, cur.getWidth() * 2, cur.getHeight() * 2, true);
            cur.recycle();
            cur = up;
        }
        return cur;
    }

    /** One 3-tap box blur pass along (dx, dy), clamping at the edges. */
    private static void boxBlur(int[] in, int[] out, int w, int h, int dx, int dy) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = 0, g = 0, b = 0;
                for (int k = -1; k <= 1; k++) {
                    int sx = Math.min(w - 1, Math.max(0, x + k * dx)), sy = Math.min(h - 1, Math.max(0, y + k * dy));
                    int c = in[sy * w + sx];
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                }
                out[y * w + x] = 0xFF000000 | (r / 3) << 16 | (g / 3) << 8 | (b / 3);
            }
        }
    }
}
