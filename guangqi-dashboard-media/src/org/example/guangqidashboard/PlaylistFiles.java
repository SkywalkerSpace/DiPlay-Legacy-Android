package org.example.guangqidashboard;

import java.io.File;
import java.util.Set;

final class PlaylistFiles {
    static void removeOrphans(File directory, Set<String> kept) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith("media-") && file.getName().endsWith(".bin")
                    && !kept.contains(file.getName())) file.delete();
        }
    }
}
