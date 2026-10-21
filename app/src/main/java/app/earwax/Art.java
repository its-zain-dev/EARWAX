package app.earwax;

import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.util.Size;
import java.io.InputStream;

/** Notification artwork: the album cover (or the EARWAX head when there is none) set into a chrome frame. */
final class Art {
    private static final int N = 320;

    /** Blocking (disk + decode): call off the main thread. */
    static Bitmap load(Context c, Track t) {
        Bitmap src = null;
        if (t != null && !t.stream()) {
            try {
                if (Build.VERSION.SDK_INT >= 29) src = c.getContentResolver().loadThumbnail(t.uri(), new Size(N, N), null);
                else if (t.albumId > 0) try (InputStream in = c.getContentResolver().openInputStream(t.artUri())) { src = BitmapFactory.decodeStream(in); }
            } catch (Exception ignored) { }
        }
        if (src == null) {
            try (InputStream in = c.getAssets().open("skins/mark.png")) { src = BitmapFactory.decodeStream(in); } catch (Exception ignored) { }
        }
        return frame(src);
    }

    static Bitmap frame(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        RectF all = new RectF(0, 0, N, N), in = new RectF(14, 14, N - 14, N - 14);
        p.setShader(new LinearGradient(0, 0, N, N, new int[]{0xfff4f8ff, 0xff8e9bb0, 0xffdfe7f2, 0xff5d6a80}, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(all, 44, 44, p);
        p.setShader(null); p.setColor(0xff0e1520); c.drawRoundRect(in, 32, 32, p);
        if (src != null) {
            Path clip = new Path(); clip.addRoundRect(in, 32, 32, Path.Direction.CW);
            c.save(); c.clipPath(clip);
            float k = Math.max(in.width() / src.getWidth(), in.height() / src.getHeight()), w = src.getWidth() * k, h = src.getHeight() * k;
            c.drawBitmap(src, null, new RectF(in.centerX() - w / 2, in.centerY() - h / 2, in.centerX() + w / 2, in.centerY() + h / 2), p);
            c.restore();
        }
        p.setShader(new LinearGradient(0, 14, 0, N / 2f, 0x44ffffff, 0x00ffffff, Shader.TileMode.CLAMP));
        c.drawRoundRect(in, 32, 32, p);
        return out;
    }
}
