package org.example.guangqidashboard;

public final class PlaylistCursorTest {
    private static int checks;

    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        PlaylistCursor once = new PlaylistCursor(3, false);
        check(once.current() == 0, "starts at first selection");
        check(once.advance(false) && once.current() == 1, "next in order");
        check(once.advance(false) && once.current() == 2, "last in order");
        check(!once.advance(false), "stop after last without loop");
        PlaylistCursor looping = new PlaylistCursor(2, true);
        check(looping.advance(false) && looping.current() == 1, "loop next");
        check(looping.advance(false) && looping.current() == 0, "loop wraps");
        PlaylistCursor broken = new PlaylistCursor(3, true);
        check(broken.advance(true) && broken.current() == 1, "skip first failure");
        check(broken.advance(true) && broken.current() == 2, "skip second failure");
        check(!broken.advance(true), "all broken cannot loop forever");
        PlaylistCursor mixed = new PlaylistCursor(3, true);
        check(mixed.advance(true), "mixed first failure");
        mixed.succeeded();
        check(mixed.advance(false), "mixed successful entry");
        check(mixed.advance(true) && mixed.current() == 0, "mixed wrap failure");
        check(mixed.advance(true), "success reset failure counter");
        mixed.succeeded();
        check(mixed.advance(false), "can keep playing valid entry");
        PlaylistCursor single = new PlaylistCursor(1, true);
        check(single.advance(false) && single.current() == 0, "single item loops");
        check(!single.advance(true), "single broken item stops");
        PlaylistCursor lastBroken = new PlaylistCursor(2, false);
        check(lastBroken.advance(false), "reach last entry");
        check(!lastBroken.advance(true), "broken last entry terminates without loop");
        boolean rejected = false;
        try { new PlaylistCursor(0, true); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "empty playlist rejected");
        System.out.println("PASS: " + checks + " playlist order and failure checks");
    }
}
