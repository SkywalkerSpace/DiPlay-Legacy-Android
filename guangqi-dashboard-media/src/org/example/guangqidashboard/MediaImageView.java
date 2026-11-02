package org.example.guangqidashboard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** Bitmap sampling and legacy Movie GIF support work on API 23 without native dependencies. */
final class MediaImageView extends View {
    static final class Image {
        Bitmap bitmap;
        Movie movie;
        void release() {
            if (bitmap != null) bitmap.recycle();
            bitmap = null;
            movie = null;
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Image image;
    private long started;

    MediaImageView(Context context) {
        super(context);
        // Movie GIF drawing requires a software canvas on older Android releases.
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    static Image load(File file, int width, int height) throws IOException {
        byte[] signature = new byte[6];
        int count;
        try (FileInputStream stream = new FileInputStream(file)) { count = stream.read(signature); }
        boolean gif = count == 6 && signature[0] == 'G' && signature[1] == 'I'
                && signature[2] == 'F' && signature[3] == '8'
                && (signature[4] == '7' || signature[4] == '9') && signature[5] == 'a';
        Image result = new Image();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("不支持的图片格式");
        if (gif) {
            // Movie cannot subsample and stores all frames in native memory on API 23.
            GifSafety.validate(file);
            try (FileInputStream stream = new FileInputStream(file)) { result.movie = Movie.decodeStream(stream); }
            if (result.movie == null) throw new IOException("GIF 解码失败");
        } else {
            BitmapFactory.Options options = new BitmapFactory.Options();
            int targetWidth = Math.min(1920, Math.max(1, width));
            int targetHeight = Math.min(1080, Math.max(1, height));
            options.inSampleSize = 1;
            while (bounds.outWidth / options.inSampleSize > targetWidth * 2
                    || bounds.outHeight / options.inSampleSize > targetHeight * 2
                    || (long) (bounds.outWidth / options.inSampleSize)
                    * (bounds.outHeight / options.inSampleSize) > 2073600) {
                options.inSampleSize *= 2;
            }
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            result.bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (result.bitmap == null) throw new IOException("图片解码失败");
        }
        return result;
    }

    void setImage(Image value) {
        clear();
        image = value;
        started = SystemClock.uptimeMillis();
        invalidate();
    }

    void clear() {
        Image old = image;
        image = null;
        if (old != null) old.release();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        if (image == null) return;
        int width = image.movie != null ? image.movie.width() : image.bitmap.getWidth();
        int height = image.movie != null ? image.movie.height() : image.bitmap.getHeight();
        if (width <= 0 || height <= 0) return;
        float scale = Math.min(getWidth() / (float) width, getHeight() / (float) height);
        int save = canvas.save();
        canvas.translate((getWidth() - width * scale) / 2, (getHeight() - height * scale) / 2);
        canvas.scale(scale, scale);
        if (image.movie != null) {
            int duration = Math.max(1, image.movie.duration());
            image.movie.setTime((int) ((SystemClock.uptimeMillis() - started) % duration));
            image.movie.draw(canvas, 0, 0, paint);
            postInvalidateDelayed(33);
        } else {
            canvas.drawBitmap(image.bitmap, 0, 0, paint);
        }
        canvas.restoreToCount(save);
    }
}
