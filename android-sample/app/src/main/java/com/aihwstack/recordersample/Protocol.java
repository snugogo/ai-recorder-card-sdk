package com.aihwstack.recordersample;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * CB08 recorder BLE protocol (service 0xAE20; write AE21; notify AE22 / AE23).
 * Frame: [0x5A][SEQ][CRC16 LE][LEN LE][DATA], DATA = [TYPE][CMD][params...].
 * CRC-16/XMODEM (poly 0x1021, init 0) over the two LEN bytes + DATA.
 */
public final class Protocol {
    private Protocol() { }

    // TYPE 0 control
    public static final int T_CTRL = 0, C_SYNC_TIME = 0, C_GET_CAP = 1, C_CAP = 2, C_GET_BAT = 3, C_BAT = 4,
            C_GET_FW = 10, C_FW = 11, C_GET_ID = 12, C_ID = 13;
    // TYPE 2 files
    public static final int T_FILE = 2, C_LIST = 0, C_LIST_DATA = 1, C_IMPORT = 2, C_IMPORT_BEGIN = 3,
            C_DATA = 4, C_IMPORT_END = 5, C_ABORT = 7, C_DELETE = 8, C_ABORT_ACK = 11, C_DELETE_ACK = 13, C_LIST_END = 18;

    public static int crc16(byte[] b, int off, int len) {
        int c = 0;
        for (int i = off; i < off + len; i++) {
            c ^= (b[i] & 0xFF) << 8;
            for (int k = 0; k < 8; k++) c = (c & 0x8000) != 0 ? ((c << 1) ^ 0x1021) & 0xFFFF : (c << 1) & 0xFFFF;
        }
        return c & 0xFFFF;
    }

    /** Builds frames with a rolling sequence number. */
    public static final class Framer {
        private int seq;
        public synchronized byte[] cmd(int type, int cmd, byte[] p) {
            if (p == null) p = new byte[0];
            int n = 2 + p.length;
            byte[] f = new byte[6 + n];
            seq = (seq + 1) & 0xFF;
            f[0] = 0x5A; f[1] = (byte) seq; f[4] = (byte) n; f[5] = (byte) (n >> 8);
            f[6] = (byte) type; f[7] = (byte) cmd;
            System.arraycopy(p, 0, f, 8, p.length);
            int crc = crc16(f, 4, 2 + n);
            f[2] = (byte) crc; f[3] = (byte) (crc >> 8);
            return f;
        }
    }

    public interface PacketSink { void onPacket(int type, int cmd, byte[] body); }

    /** Stream parser: handles split and merged notifications; one instance per notify characteristic. */
    public static final class Parser {
        private byte[] buf = new byte[8192];
        private int n;
        private final PacketSink sink;
        public int crcFail;
        public Parser(PacketSink sink) { this.sink = sink; }
        public synchronized void reset() { n = 0; }
        public synchronized void feed(byte[] v) {
            if (n + v.length > buf.length) buf = Arrays.copyOf(buf, (n + v.length) * 2);
            System.arraycopy(v, 0, buf, n, v.length); n += v.length;
            while (true) {
                int i = 0; while (i < n && buf[i] != 0x5A) i++;
                if (i == n) { n = 0; return; }
                if (i > 0) shift(i);
                if (n < 6) return;
                int ln = (buf[4] & 0xFF) | ((buf[5] & 0xFF) << 8);
                if (ln > 8192) { shift(1); continue; }
                int tot = 6 + ln;
                if (n < tot) return;
                if (((buf[2] & 0xFF) | ((buf[3] & 0xFF) << 8)) != crc16(buf, 4, 2 + ln)) { crcFail++; shift(1); continue; }
                byte[] data = Arrays.copyOfRange(buf, 6, tot);
                shift(tot);
                if (data.length >= 2) sink.onPacket(data[0] & 0xFF, data[1] & 0xFF, Arrays.copyOfRange(data, 2, data.length));
            }
        }
        private void shift(int k) { System.arraycopy(buf, k, buf, 0, n - k); n -= k; }
    }

    /** One entry of the device file list (2-1). */
    public static final class DeviceFile {
        public int durationSec, size;
        public String raw;     // name as listed, truncated to 20 bytes, e.g. "note20261007-142733."
        public String base;    // "note20261007-142733"
        public byte[] entry;   // the original 28-byte entry
        public String fileName() { return base + ".opus"; }
        public boolean isCall() { return base.startsWith("call"); }
        /** "2026-10-07 14:27" parsed from the file name (the device sends no timestamp). */
        public String dateText() {
            String d = base.replaceAll("[^0-9-]", "");
            if (d.length() >= 15) return d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8) + "  " + d.substring(9, 11) + ":" + d.substring(11, 13);
            return base;
        }
        public String durationText() { return fmtDuration(durationSec); }
    }

    public static String fmtDuration(int s) {
        if (s >= 3600) return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    public static String fmtSize(long b) {
        if (b >= 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%d KB", Math.max(1, b / 1024));
    }

    static int be32(byte[] d, int o) { return ((d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16) | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF); }
    static int le32(byte[] d, int o) { return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8) | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 24); }
    static byte[] le32(int v) { return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)}; }

    /** 2-1 body: count 4B BE + N x 28B (duration 4B BE, size 4B BE, name 20B NUL padded). */
    public static List<DeviceFile> parseList(byte[] b) {
        List<DeviceFile> out = new ArrayList<>();
        if (b.length < 4) return out;
        int n = Math.min(be32(b, 0), (b.length - 4) / 28);
        for (int i = 0; i < n; i++) {
            int o = 4 + i * 28;
            int z = 0; while (z < 20 && b[o + 8 + z] != 0) z++;
            DeviceFile f = new DeviceFile();
            f.durationSec = be32(b, o); f.size = be32(b, o + 4);
            f.raw = new String(b, o + 8, z, StandardCharsets.ISO_8859_1).trim();
            f.base = f.raw.replaceAll("\\.+$", "");
            f.entry = Arrays.copyOfRange(b, o, o + 28);
            out.add(f);
        }
        return out;
    }

    static byte[] name24(String name) {
        byte[] nb = new byte[24]; byte[] s = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(s, 0, nb, 0, Math.min(24, s.length)); return nb;
    }
    static byte[] cat(byte[]... a) {
        int n = 0; for (byte[] x : a) n += x.length;
        byte[] r = new byte[n]; int o = 0;
        for (byte[] x : a) { System.arraycopy(x, 0, r, o, x.length); o += x.length; }
        return r;
    }
    /** 2-2 download: offset 4B LE + file name 24B. The 36-byte frame must go in ONE GATT write. */
    public static byte[] importParams(String fileName, int offset) { return cat(le32(offset), name24(fileName)); }
    /** 2-8 delete as accepted by firmware V1.0.0: same addressing as 2-2 (offset 0 + full name). */
    public static byte[] deleteParams(String fileName) { return cat(le32(0), name24(fileName)); }
    /** 0-0 sync time: year 2B LE, month, day, hour, minute, second. */
    public static byte[] timeParams(java.util.Calendar c) {
        int y = c.get(java.util.Calendar.YEAR);
        return new byte[]{(byte) y, (byte) (y >> 8), (byte) (c.get(java.util.Calendar.MONTH) + 1), (byte) c.get(java.util.Calendar.DAY_OF_MONTH),
                (byte) c.get(java.util.Calendar.HOUR_OF_DAY), (byte) c.get(java.util.Calendar.MINUTE), (byte) c.get(java.util.Calendar.SECOND)};
    }
}
