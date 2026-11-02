package org.example.guangqidashboard;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.AtomicFile;
import android.webkit.MimeTypeMap;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;

final class PlaylistStore {
    private final Context context;
    private final File directory;
    private final AtomicFile playlist;

    PlaylistStore(Context context) {
        this.context = context.getApplicationContext();
        directory = new File(context.getFilesDir(), "media");
        playlist = new AtomicFile(new File(context.getFilesDir(), "playlist.json"));
    }

    List<MediaItem> load() throws Exception {
        ArrayList<MediaItem> result = new ArrayList<MediaItem>();
        if (!playlist.getBaseFile().exists() && !new File(playlist.getBaseFile() + ".bak").exists()) {
            removeOrphans(result);
            return result;
        }
        try (FileInputStream input = playlist.openRead()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int size;
            while ((size = input.read(buffer)) != -1) {
                if (output.size() + size > 4 * 1024 * 1024) throw new IOException("播放列表过大");
                output.write(buffer, 0, size);
            }
            JSONArray array = new JSONArray(output.toString("UTF-8"));
            for (int i = 0; i < array.length(); i++) {
                MediaItem item = MediaItem.fromJson(array.getJSONObject(i), directory);
                if (item.file.isFile()) result.add(item);
            }
        }
        removeOrphans(result);
        return result;
    }

    // Only run before imports start, and only after the saved playlist was read successfully.
    private void removeOrphans(List<MediaItem> kept) {
        java.util.HashSet<String> names = new java.util.HashSet<String>();
        for (MediaItem item : kept) names.add(item.file.getName());
        PlaylistFiles.removeOrphans(directory, names);
    }

    void save(List<MediaItem> items) throws Exception {
        JSONArray array = new JSONArray();
        for (MediaItem item : items) array.put(item.toJson());
        byte[] bytes = array.toString().getBytes("UTF-8");
        FileOutputStream output = playlist.startWrite();
        try {
            output.write(bytes);
            playlist.finishWrite(output);
        } catch (Exception error) {
            playlist.failWrite(output);
            throw error;
        }
    }

    MediaItem copy(Uri uri) throws IOException {
        String scheme = uri.getScheme();
        if (!"content".equals(scheme) && !"file".equals(scheme)) {
            throw new IOException("只支持本地文件或 content URI");
        }
        String name = uri.getLastPathSegment();
        String mime = null;
        if ("content".equals(scheme)) {
            mime = context.getContentResolver().getType(uri);
            try (Cursor cursor = context.getContentResolver().query(uri,
                    new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
            } catch (RuntimeException ignored) { /* Some file managers do not expose metadata. */ }
        }
        if (name == null || name.length() == 0) name = "未命名媒体";
        if (name.length() > 240) name = name.substring(0, 240);
        String extension = name.lastIndexOf('.') >= 0
                ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.US) : "";
        if (mime == null || (!mime.startsWith("image/") && !mime.startsWith("video/"))) {
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        }
        if (mime == null || (!mime.startsWith("image/") && !mime.startsWith("video/"))) {
            throw new IOException("无法识别图片/视频类型：" + name);
        }
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法建立媒体目录");
        File target = File.createTempFile("media-", ".bin", directory);
        boolean complete = false;
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                    FileOutputStream output = new FileOutputStream(target)) {
                if (input == null) throw new IOException("文件管理器没有提供可读内容");
                byte[] buffer = new byte[64 * 1024];
                int size;
                long bytes = 0;
                while ((size = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("导入已取消");
                    if (directory.getUsableSpace() < size + 32L * 1024 * 1024) {
                        throw new IOException("内部存储空间不足（需保留 32 MiB）");
                    }
                    output.write(buffer, 0, size);
                    bytes += size;
                }
                if (bytes == 0) throw new IOException("文件内容为空");
            }
            complete = true;
            return new MediaItem(name, mime, target);
        } finally {
            if (!complete) target.delete();
        }
    }
}
