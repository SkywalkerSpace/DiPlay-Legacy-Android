package org.example.guangqidashboard;

/** Playback order without Android dependencies, including a broken-playlist circuit breaker. */
final class PlaylistCursor {
    private final int size;
    private final boolean loop;
    private int index = 0;
    private int failures = 0;

    PlaylistCursor(int size, boolean loop) {
        if (size < 1) throw new IllegalArgumentException("Empty playlist");
        this.size = size;
        this.loop = loop;
    }

    int current() { return index; }
    void succeeded() { failures = 0; }

    boolean advance(boolean failed) {
        if (failed && ++failures >= size) return false;
        if (index + 1 < size) {
            index++;
            return true;
        }
        if (!loop) return false;
        index = 0;
        return true;
    }
}
