package org.example.guangqidashboard;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Movie on Android 6 slurps all GIF frames, so check uncompressed frame budgets first. */
final class GifSafety {
    private static final long MAX_FRAME_PIXELS = 16000000L;

    static void validate(File file) throws IOException {
        if (file.length() > 20L * 1024 * 1024) throw new IOException("GIF 超过 20 MiB");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] header = new byte[6];
            for (int i = 0; i < header.length; i++) header[i] = (byte) read(input);
            String version = new String(header, "US-ASCII");
            if (!"GIF87a".equals(version) && !"GIF89a".equals(version)) throw new IOException("无效 GIF 文件头");
            int width = word(input);
            int height = word(input);
            if (width < 1 || height < 1 || width > 2048 || height > 2048
                    || (long) width * height > 2000000) throw new IOException("GIF 尺寸超过安全限制");
            int packed = read(input);
            skip(input, 2);
            if ((packed & 128) != 0) skip(input, 3 * (1 << ((packed & 7) + 1)));
            int frames = 0;
            long pixels = 0;
            while (true) {
                int block = read(input);
                if (block == 0x3b) {
                    if (frames == 0) throw new IOException("GIF 不包含图像帧");
                    return;
                }
                if (block == 0x21) {
                    read(input); // Extension label, followed by length-prefixed sub-blocks.
                    subBlocks(input);
                } else if (block == 0x2c) {
                    int left = word(input);
                    int top = word(input);
                    int frameWidth = word(input);
                    int frameHeight = word(input);
                    if (frameWidth < 1 || frameHeight < 1 || left + frameWidth > width || top + frameHeight > height) {
                        throw new IOException("GIF 帧超出画布");
                    }
                    frames++;
                    pixels += (long) frameWidth * frameHeight;
                    if (frames > 500 || pixels > MAX_FRAME_PIXELS) {
                        throw new IOException("GIF 动画过大（最多 500 帧、累计 1600 万帧像素），请缩小或转为视频");
                    }
                    int flags = read(input);
                    if ((flags & 128) != 0) skip(input, 3 * (1 << ((flags & 7) + 1)));
                    int codeSize = read(input);
                    if (codeSize < 2 || codeSize > 8) throw new IOException("GIF LZW 参数无效");
                    subBlocks(input);
                } else {
                    throw new IOException("GIF 数据块无效");
                }
            }
        }
    }

    private static int read(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) throw new IOException("GIF 数据被截断");
        return value;
    }

    private static int word(InputStream input) throws IOException { return read(input) | (read(input) << 8); }

    private static void skip(InputStream input, int bytes) throws IOException {
        for (int i = 0; i < bytes; i++) read(input);
    }

    private static void subBlocks(InputStream input) throws IOException {
        int bytes;
        while ((bytes = read(input)) != 0) skip(input, bytes);
    }
}
