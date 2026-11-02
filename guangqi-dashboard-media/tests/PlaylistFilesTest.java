package org.example.guangqidashboard;

import java.io.File;
import java.util.Collections;

public final class PlaylistFilesTest {
    public static void main(String[] args) throws Exception {
        File directory = new File(args[0], "orphan-test");
        if (!directory.mkdir()) throw new AssertionError("create fixture directory");
        File kept = new File(directory, "media-kept.bin");
        File partial = new File(directory, "media-partial.bin");
        File imported = new File(directory, "media-imported.bin");
        File unrelated = new File(directory, "other-file.bin");
        File nested = new File(directory, "media-subdir.bin");
        kept.createNewFile();
        partial.createNewFile();
        imported.createNewFile();
        unrelated.createNewFile();
        nested.mkdir();
        PlaylistFiles.removeOrphans(directory, Collections.singleton(kept.getName()));
        if (!kept.exists()) throw new AssertionError("registered files must survive");
        if (partial.exists()) throw new AssertionError("interrupted copy must be removed");
        if (imported.exists()) throw new AssertionError("uncommitted batch must be removed");
        if (!unrelated.exists() || !nested.isDirectory()) throw new AssertionError("unrelated entries must survive");
        PlaylistFiles.removeOrphans(directory, Collections.<String>emptySet());
        if (kept.exists()) throw new AssertionError("empty playlist must recover orphan files");
        System.out.println("PASS: 5 interrupted-import cleanup checks");
    }
}
