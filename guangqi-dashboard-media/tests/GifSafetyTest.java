package org.example.guangqidashboard;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public final class GifSafetyTest {
    private static int checks;

    private static void word(ByteArrayOutputStream output, int value) {
        output.write(value & 255);
        output.write((value >>> 8) & 255);
    }

    private static byte[] gif(int width, int height, int frames) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("GIF89a".getBytes("US-ASCII"));
        word(output, width);
        word(output, height);
        output.write(new byte[] {(byte) 128, 0, 0, 0, 0, 0, (byte) 255, (byte) 255, (byte) 255});
        for (int i = 0; i < frames; i++) {
            output.write(0x2c);
            word(output, 0);
            word(output, 0);
            word(output, width);
            word(output, height);
            // Minimal 1x1 image data. Larger synthetic descriptors test the pre-decode guard only.
            output.write(new byte[] {0, 2, 2, 0x44, 1, 0});
        }
        output.write(0x3b);
        return output.toByteArray();
    }

    private static void verify(byte[] bytes, boolean accepted, String label) throws Exception {
        File file = File.createTempFile("gif-safety-", ".gif");
        try {
            try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
            boolean passed = true;
            try { GifSafety.validate(file); }
            catch (IOException expected) { passed = false; }
            checks++;
            if (passed != accepted) throw new AssertionError(label);
        } finally { file.delete(); }
    }

    public static void main(String[] args) throws Exception {
        verify(gif(1, 1, 1), true, "valid minimal GIF");
        verify(gif(1, 1, 500), true, "frame count boundary");
        verify(gif(1, 1, 501), false, "too many frames");
        verify(gif(1000, 1000, 16), true, "cumulative pixel boundary");
        verify(gif(1000, 1000, 17), false, "small compressed GIF with excessive native memory");
        verify(gif(2049, 1, 1), false, "oversized side");
        verify(gif(1500, 1500, 1), false, "oversized canvas");
        verify(gif(0, 1, 1), false, "zero width");
        verify(gif(1, 1, 0), false, "no image frames");
        byte[] truncated = gif(1, 1, 1);
        verify(java.util.Arrays.copyOf(truncated, truncated.length - 2), false, "truncated sub-block");
        byte[] invalid = gif(1, 1, 1);
        invalid[0] = 'X';
        verify(invalid, false, "invalid header");
        byte[] outside = gif(1, 1, 1);
        outside[20] = 1; // Image left offset, beyond the logical canvas.
        verify(outside, false, "frame outside canvas");
        System.out.println("PASS: " + checks + " GIF pre-decode safety checks");
    }
}
