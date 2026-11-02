package org.example.guangqidashboard;

import java.io.File;
import org.json.JSONException;
import org.json.JSONObject;

final class MediaItem {
    final String name;
    final String mime;
    final File file;

    MediaItem(String name, String mime, File file) {
        this.name = name;
        this.mime = mime;
        this.file = file;
    }

    boolean isVideo() { return mime.startsWith("video/"); }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("name", name);
        json.put("mime", mime);
        json.put("file", file.getName());
        return json;
    }

    static MediaItem fromJson(JSONObject json, File directory) throws JSONException {
        String basename = json.getString("file");
        if (!basename.equals(new File(basename).getName()) || !basename.startsWith("media-")) {
            throw new JSONException("Invalid media path");
        }
        return new MediaItem(json.getString("name"), json.getString("mime"), new File(directory, basename));
    }
}
