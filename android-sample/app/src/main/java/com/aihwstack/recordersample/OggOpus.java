package com.aihwstack.recordersample;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * The recorder stores bare Opus packets: 40 bytes each, 20 ms, mono, ~16 kbps, no container.
 * This wraps them into a standard Ogg Opus file that Android MediaPlayer, ffmpeg and speech APIs accept.
 */
public final class OggOpus {
    private OggOpus() { }
    public static final int FRAME_BYTES = 40;
    public static final int FRAMES_PER_SECOND = 50;

    private static final int[] T = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int k = 0; k < 8; k++) r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04C11DB7 : r << 1;
            T[i] = r;
        }
    }
    private static int crc(byte[] b) {
        int c = 0;
        for (byte x : b) c = (c << 8) ^ T[((c >>> 24) ^ (x & 0xFF)) & 0xFF];
        return c;
    }

    private static void page(ByteArrayOutputStream out, int serial, int seq, int type, long granule, byte[][] packets, int from, int count) {
        ByteArrayOutputStream segs = new ByteArrayOutputStream(), body = new ByteArrayOutputStream();
        for (int i = from; i < from + count; i++) {
            int n = packets[i].length;
            while (n >= 255) { segs.write(255); n -= 255; }
            segs.write(n);
            body.write(packets[i], 0, packets[i].length);
        }
        byte[] sg = segs.toByteArray(), bd = body.toByteArray();
        byte[] p = new byte[27 + sg.length + bd.length];
        p[0] = 'O'; p[1] = 'g'; p[2] = 'g'; p[3] = 'S'; p[4] = 0; p[5] = (byte) type;
        for (int i = 0; i < 8; i++) p[6 + i] = (byte) (granule >>> (8 * i));
        for (int i = 0; i < 4; i++) { p[14 + i] = (byte) (serial >>> (8 * i)); p[18 + i] = (byte) (seq >>> (8 * i)); }
        p[26] = (byte) sg.length;
        System.arraycopy(sg, 0, p, 27, sg.length);
        System.arraycopy(bd, 0, p, 27 + sg.length, bd.length);
        int c = crc(p);
        for (int i = 0; i < 4; i++) p[22 + i] = (byte) (c >>> (8 * i));
        out.write(p, 0, p.length);
    }

    /**
     * The recorder pads most packets to 40 bytes with Opus "code 3" padding (TOC 0x4B, 1 frame, ~30 zero bytes).
     * That is valid Opus and ffmpeg decodes it, but Android 9's built-in decoder (OMX.google.opus) rejects it.
     * Rewriting such a packet as a plain code-0 packet (same TOC config, same frame bytes, no padding) is lossless:
     * the decoded PCM is bit-identical.
     */
    static byte[] repack(byte[] b, int off, int len) {
        int toc = b[off] & 0xFF;
        if ((toc & 3) != 3 || len < 2) return java.util.Arrays.copyOfRange(b, off, off + len);
        int fc = b[off + 1] & 0xFF;
        if ((fc & 0x3F) != 1 || (fc & 0x80) != 0) return java.util.Arrays.copyOfRange(b, off, off + len);
        int i = 2, pad = 0;
        if ((fc & 0x40) != 0) {
            while (i < len) { int v = b[off + i++] & 0xFF; pad += v == 255 ? 254 : v; if (v != 255) break; }
        }
        int dataLen = len - i - pad;
        if (dataLen < 0) return java.util.Arrays.copyOfRange(b, off, off + len);
        byte[] out = new byte[1 + dataLen];
        out[0] = (byte) (toc & 0xFC);
        System.arraycopy(b, off + i, out, 1, dataLen);
        return out;
    }

    /** Wraps raw[from, from+len) (multiple of 40 bytes) into an Ogg Opus file. */
    public static byte[] wrap(byte[] raw, int from, int len) {
        int serial = new Random().nextInt(), seq = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream(len + len / 20 + 512);
        byte[] head = new byte[19];
        System.arraycopy("OpusHead".getBytes(StandardCharsets.US_ASCII), 0, head, 0, 8);
        head[8] = 1; head[9] = 1; head[10] = (byte) 312; head[11] = (byte) (312 >> 8);   // version, 1 channel, pre-skip 312
        int rate = 16000; for (int i = 0; i < 4; i++) head[12 + i] = (byte) (rate >>> (8 * i));
        page(out, serial, seq++, 2, 0, new byte[][]{head}, 0, 1);
        byte[] vendor = "ai-recorder".getBytes(StandardCharsets.US_ASCII);
        byte[] tags = new byte[8 + 4 + vendor.length + 4];
        System.arraycopy("OpusTags".getBytes(StandardCharsets.US_ASCII), 0, tags, 0, 8);
        tags[8] = (byte) vendor.length;
        System.arraycopy(vendor, 0, tags, 12, vendor.length);
        page(out, serial, seq++, 0, 0, new byte[][]{tags}, 0, 1);
        int nf = len / FRAME_BYTES;
        byte[][] frames = new byte[nf][];
        for (int i = 0; i < nf; i++) frames[i] = repack(raw, from + i * FRAME_BYTES, FRAME_BYTES);
        final int PER = 50;
        for (int i = 0; i < nf; i += PER) {
            int c = Math.min(PER, nf - i);
            page(out, serial, seq++, i + PER >= nf ? 4 : 0, (long) (i + c) * 960, frames, i, c);
        }
        return out.toByteArray();
    }
}
