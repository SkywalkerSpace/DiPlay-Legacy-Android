package org.example.guangqidashboard;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Point;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.VideoView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

final class MediaPresentation extends Presentation {
    interface Listener {
        void onPlaying(int index);
        void onError(int index, String message);
        void onFinished(String message);
    }

    private final List<MediaItem> items;
    private final PlaylistCursor cursor;
    private final int imageSeconds;
    private final Listener listener;
    private final Handler handler = new Handler();
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private FrameLayout frame;
    private MediaImageView image;
    private VideoView video;
    private boolean closed;
    private int generation;

    MediaPresentation(Context context, Display display, List<MediaItem> items,
            boolean loop, int imageSeconds, Listener listener) {
        super(context, display);
        if (display.getDisplayId() == Display.DEFAULT_DISPLAY) {
            throw new IllegalArgumentException("Refuse playback on primary display");
        }
        this.items = new ArrayList<MediaItem>(items);
        this.cursor = new PlaylistCursor(items.size(), loop);
        this.imageSeconds = imageSeconds;
        this.listener = listener;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        frame = new FrameLayout(getContext());
        frame.setBackgroundColor(Color.BLACK);
        frame.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        image = new MediaImageView(getContext());
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);
        getWindow().setLayout(-1, -1);
    }

    @Override protected void onStart() {
        super.onStart();
        handler.post(new Runnable() { @Override public void run() { playCurrent(); } });
    }

    private void clearMedia() {
        generation++;
        handler.removeCallbacksAndMessages(null);
        if (video != null) {
            video.setOnPreparedListener(null);
            video.setOnCompletionListener(null);
            video.setOnErrorListener(null);
            video.stopPlayback();
            frame.removeView(video);
            video = null;
        }
        if (image != null) image.clear();
    }

    private void playCurrent() {
        if (closed) return;
        clearMedia();
        final int token = generation;
        final MediaItem item = items.get(cursor.current());
        listener.onPlaying(cursor.current());
        if (item.isVideo()) {
            image.setVisibility(View.GONE);
            final VideoView current = new VideoView(getContext());
            video = current;
            frame.addView(current, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            final Runnable timeout = new Runnable() {
                @Override public void run() {
                    if (isCurrent(token)) failed("视频准备超时");
                }
            };
            handler.postDelayed(timeout, 30000);
            current.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer player) {
                    if (!isCurrent(token)) return;
                    handler.removeCallbacks(timeout);
                    player.setLooping(false);
                    current.start();
                }
            });
            current.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer player) {
                    if (isCurrent(token)) {
                        cursor.succeeded();
                        advance(false);
                    }
                }
            });
            current.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer player, int what, int extra) {
                    if (isCurrent(token)) failed("系统视频解码失败（" + what + "/" + extra + "）");
                    return true;
                }
            });
            try { current.setVideoURI(Uri.fromFile(item.file)); }
            catch (RuntimeException error) { failed("无法打开视频：" + error.getMessage()); }
        } else {
            image.setVisibility(View.VISIBLE);
            final Point size = new Point();
            getDisplay().getSize(size);
            try {
                decoder.execute(new Runnable() {
                    @Override public void run() {
                        MediaImageView.Image loaded = null;
                        String error = null;
                        try { loaded = MediaImageView.load(item.file, size.x, size.y); }
                        catch (Exception problem) { error = problem.getMessage(); }
                        catch (OutOfMemoryError problem) { error = "图片或 GIF 超出设备可用内存"; }
                        final MediaImageView.Image result = loaded;
                        final String message = error;
                        handler.post(new Runnable() {
                            @Override public void run() {
                                if (!isCurrent(token)) {
                                    if (result != null) result.release();
                                    return;
                                }
                                if (result == null) { failed(message); return; }
                                image.setImage(result);
                                cursor.succeeded();
                                handler.postDelayed(new Runnable() {
                                    @Override public void run() { if (isCurrent(token)) advance(false); }
                                }, imageSeconds * 1000L);
                            }
                        });
                    }
                });
            } catch (RejectedExecutionException error) {
                if (!closed) failed("图片加载器已停止");
            }
        }
    }

    private boolean isCurrent(int token) { return !closed && token == generation; }

    private void failed(String message) {
        listener.onError(cursor.current(), message == null ? "媒体读取失败" : message);
        advance(true);
    }

    private void advance(boolean failed) {
        if (closed) return;
        clearMedia();
        if (!cursor.advance(failed)) {
            listener.onFinished(failed ? "播放结束：没有后续可播放文件" : "列表播放完毕");
            dismiss();
            return;
        }
        // Defer transitions so broken files cannot recurse through the whole list.
        handler.postDelayed(new Runnable() { @Override public void run() { playCurrent(); } }, failed ? 500 : 0);
    }

    void next() { advance(false); }

    @Override public void dismiss() {
        release();
        super.dismiss();
    }

    private void release() {
        if (closed) return;
        closed = true;
        clearMedia();
        decoder.shutdownNow();
    }

    @Override protected void onStop() {
        release();
        super.onStop();
    }
}
