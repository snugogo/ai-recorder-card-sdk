package com.aihwstack.recordersample;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Sample app for the WB-RC100 / CB08 recorder card.
 *
 * Scan and connect, read battery / firmware / storage / device ID, list recordings, then per recording:
 * download (resumes from the last byte if the link dropped), play, transcribe through any
 * OpenAI-compatible speech-to-text API, delete from the card.
 *
 * One Activity, no third-party libraries. Protocol: docs/ble-protocol.md.
 */
@SuppressLint("MissingPermission")
public class MainActivity extends Activity {
    static final UUID SVC = uuid16(0xAE20), WRITE = uuid16(0xAE21), N1 = uuid16(0xAE22), N2 = uuid16(0xAE23);
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final long STALL_MS = 12000;   // no data for 12 s during a download = stalled

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Protocol.Framer framer = new Protocol.Framer();
    private final ArrayDeque<BluetoothGattDescriptor> pendingCccd = new ArrayDeque<>();
    private final List<Protocol.DeviceFile> files = new ArrayList<>();

    private TextView log;
    private LinearLayout listView;
    private Button btnScan, btnList;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private Protocol.Parser p1, p2;
    private Transcriber asr;
    private MediaPlayer player;

    // current download
    private Protocol.DeviceFile dlFile;
    private FileOutputStream out;
    private long received;
    private long lastDataAt;
    private final Runnable listDone = this::onListComplete;
    private final Runnable stallCheck = new Runnable() {
        @Override public void run() {
            if (dlFile == null) return;
            if (System.currentTimeMillis() - lastDataAt > STALL_MS) {
                closeOut();
                log("Download stalled at " + received + " bytes. Tap Download again to resume from there.");
                dlFile = null;
                return;
            }
            ui.postDelayed(this, 1000);
        }
    };

    static UUID uuid16(int v) { return UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v)); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        asr = new Transcriber(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        btnScan = button(root, "Scan and connect", v -> startScan());
        btnList = button(root, "List recordings", v -> requestList());
        button(root, "Import audio file (USB) and transcribe", v -> pickFile());
        button(root, "Speech-to-text settings", v -> showAsrSettings());
        btnList.setEnabled(false);
        listView = new LinearLayout(this);
        listView.setOrientation(LinearLayout.VERTICAL);
        root.addView(listView);
        log = new TextView(this);
        log.setTextSize(13);
        root.addView(log);
        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
        Protocol.PacketSink sink = this::onPacket;
        p1 = new Protocol.Parser(sink);
        p2 = new Protocol.Parser(sink);
        askPermissions();
    }

    private Button button(LinearLayout parent, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        parent.addView(b);
        return b;
    }

    private void log(String s) { ui.post(() -> log.append(s + "\n")); }

    // ------------------------------------------------------------------ permissions

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            need.add(Manifest.permission.BLUETOOTH_SCAN);
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            // Android 6-11: scanning needs Location permission AND Location switched on.
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        List<String> missing = new ArrayList<>();
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 1);
    }

    // ------------------------------------------------------------------ scan + connect

    private void startScan() {
        if (gatt != null) { log("Already connected."); return; }
        BluetoothAdapter a = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
        if (a == null || !a.isEnabled()) { log("Bluetooth is off."); return; }
        scanner = a.getBluetoothLeScanner();
        log("Scanning for CB08 ...");
        scanner.startScan(scanCb);
        ui.postDelayed(() -> { if (gatt == null) { scanner.stopScan(scanCb); log("Not found. Is the recorder switched on?"); } }, 15000);
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            String name = r.getDevice().getName();
            if (gatt != null || name == null || !(name.startsWith("CB08") || name.startsWith("QS668"))) return;
            scanner.stopScan(this);
            log("Found " + name + " " + r.getDevice().getAddress() + ", connecting ...");
            gatt = r.getDevice().connectGatt(MainActivity.this, false, gattCb, BluetoothDevice.TRANSPORT_LE);
        }
    };

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                log("Connected. Requesting MTU 247 ...");
                g.requestMtu(247);   // download request 2-2 is 36 bytes and must be ONE write
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                log("Disconnected." + (dlFile != null ? " Reconnect and tap Download to resume." : ""));
                closeOut();
                dlFile = null;
                g.close();
                gatt = null;
                ui.post(() -> btnList.setEnabled(false));
            }
        }

        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            log("MTU " + mtu);
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);   // about 13 % faster downloads
            g.discoverServices();
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService s = g.getService(SVC);
            if (s == null) { log("Service AE20 not found."); return; }
            writeChar = s.getCharacteristic(WRITE);
            // Subscribe to BOTH notify characteristics: some replies (battery 0-4) arrive on AE23.
            for (UUID u : new UUID[]{N1, N2}) {
                BluetoothGattCharacteristic c = s.getCharacteristic(u);
                if (c == null) continue;
                g.setCharacteristicNotification(c, true);
                BluetoothGattDescriptor d = c.getDescriptor(CCCD);
                if (d != null) { d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); pendingCccd.add(d); }
            }
            writeNextCccd(g);
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) { writeNextCccd(g); }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            // Separate reassembly buffer per characteristic.
            (N1.equals(c.getUuid()) ? p1 : p2).feed(c.getValue());
        }
    };

    private void writeNextCccd(BluetoothGatt g) {
        BluetoothGattDescriptor d = pendingCccd.poll();
        if (d != null) { g.writeDescriptor(d); return; }
        log("Ready.");
        send(Protocol.T_CTRL, Protocol.C_SYNC_TIME, Protocol.timeParams(Calendar.getInstance()));
        send(Protocol.T_CTRL, Protocol.C_GET_BAT, null);
        send(Protocol.T_CTRL, Protocol.C_GET_FW, null);
        send(Protocol.T_CTRL, Protocol.C_GET_CAP, null);
        send(Protocol.T_CTRL, Protocol.C_GET_ID, null);
        ui.post(() -> btnList.setEnabled(true));
    }

    // Writes are spaced by a short delay; a production app should wait for onCharacteristicWrite.
    private long nextWriteAt;
    private void send(int type, int cmd, byte[] params) {
        byte[] f = framer.cmd(type, cmd, params);
        long now = System.currentTimeMillis();
        nextWriteAt = Math.max(nextWriteAt, now) + 60;
        ui.postDelayed(() -> {
            if (gatt == null || writeChar == null) return;
            writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            writeChar.setValue(f);
            gatt.writeCharacteristic(writeChar);
        }, nextWriteAt - now);
    }

    // ------------------------------------------------------------------ list

    private void requestList() {
        files.clear();
        log("Listing ...");
        send(Protocol.T_FILE, Protocol.C_LIST, null);
    }

    private void onListComplete() {
        ui.removeCallbacks(listDone);
        ui.post(() -> {
            Collections.sort(files, (a, b) -> b.base.compareTo(a.base));   // newest first
            listView.removeAllViews();
            log(files.size() + " recording(s).");
            for (Protocol.DeviceFile f : files) addRow(f);
        });
    }

    private void addRow(Protocol.DeviceFile f) {
        TextView t = new TextView(this);
        t.setText(f.dateText() + "   " + f.durationText() + "   " + Protocol.fmtSize(f.size) + (f.isCall() ? "   (call)" : ""));
        t.setPadding(0, 24, 0, 0);
        listView.addView(t);
        LinearLayout row = new LinearLayout(this);
        listView.addView(row);
        button(row, "Download", v -> download(f));
        button(row, "Play", v -> play(f));
        button(row, "Transcribe", v -> transcribe(f));
        button(row, "Delete", v -> confirmDelete(f));
    }

    // ------------------------------------------------------------------ download with resume

    /** Finished downloads are bare Opus packets in "<name>-<size>.opus"; unfinished ones end in ".part". */
    private File doneFile(Protocol.DeviceFile f) { return new File(getExternalFilesDir(null), f.base + "-" + f.size + ".opus"); }
    private File partFile(Protocol.DeviceFile f) { return new File(getExternalFilesDir(null), f.base + "-" + f.size + ".opus.part"); }

    private void download(Protocol.DeviceFile f) {
        if (dlFile != null) { log("A download is already running."); return; }
        if (doneFile(f).length() == f.size) { log(f.fileName() + " is already downloaded."); return; }
        if (gatt == null) { log("Connect first."); return; }
        File part = partFile(f);
        // Resume from the bytes we already have, rounded down to a whole 40-byte Opus packet.
        long have = part.exists() ? part.length() : 0;
        long offset = have - have % OggOpus.FRAME_BYTES;
        try {
            if (offset != have) try (RandomAccessFile r = new RandomAccessFile(part, "rw")) { r.setLength(offset); }
            out = new FileOutputStream(part, offset > 0);
        } catch (IOException e) { log("Cannot open file: " + e); return; }
        dlFile = f;
        received = offset;
        lastDataAt = System.currentTimeMillis();
        log((offset > 0 ? "Resuming " + f.fileName() + " from byte " + offset : "Downloading " + f.fileName()) + " ...");
        send(Protocol.T_FILE, Protocol.C_IMPORT, Protocol.importParams(f.fileName(), (int) offset));
        ui.postDelayed(stallCheck, 1000);
    }

    private void closeOut() {
        try { if (out != null) out.close(); } catch (IOException ignored) { }
        out = null;
        ui.removeCallbacks(stallCheck);
    }

    private void onDownloadEnd(int code) {
        Protocol.DeviceFile f = dlFile;
        closeOut();
        dlFile = null;
        if (f == null) return;
        if (code == 0 && partFile(f).length() == f.size) {
            File done = doneFile(f);
            if (!partFile(f).renameTo(done)) { log("Rename failed."); return; }
            log("Done: " + done.getAbsolutePath());
        } else if (code == 0) {
            log("Download ended but size is " + partFile(f).length() + " of " + f.size + ". Tap Download to resume.");
        } else {
            log("Download ended with code " + code + " (1 = no such file, 2 = offset too large, 3 = stopped)");
        }
    }

    // ------------------------------------------------------------------ play

    private byte[] readDone(Protocol.DeviceFile f) {
        File d = doneFile(f);
        if (!d.exists()) { log("Download " + f.fileName() + " first."); return null; }
        try (FileInputStream in = new FileInputStream(d)) {
            byte[] b = new byte[(int) d.length()];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            return b;
        } catch (IOException e) { log("Read failed: " + e); return null; }
    }

    private void play(Protocol.DeviceFile f) {
        byte[] raw = readDone(f);
        if (raw == null) return;
        try {
            // Wrap the bare packets in Ogg Opus; OggOpus.repack makes them playable on Android 9's decoder too.
            File ogg = new File(getCacheDir(), "play.ogg");
            try (FileOutputStream o = new FileOutputStream(ogg)) { o.write(OggOpus.wrap(raw, 0, raw.length - raw.length % OggOpus.FRAME_BYTES)); }
            if (player != null) player.release();
            player = new MediaPlayer();
            // Set USAGE_MEDIA explicitly: some TV boxes and phones do not route the default usage to the speaker.
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            player.setDataSource(ogg.getAbsolutePath());
            player.prepare();
            player.start();
            log("Playing " + f.fileName() + " ...");
        } catch (IOException e) { log("Play failed: " + e); }
    }

    // ------------------------------------------------------------------ transcribe

    private void showAsrSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 16, 48, 0);
        EditText url = field(box, "Base URL, e.g. https://api.openai.com/v1", asr.url(), false);
        EditText key = field(box, "API key (stored only on this phone)", asr.key(), true);
        EditText model = field(box, "Model, e.g. whisper-1", asr.model(), false);
        EditText lang = field(box, "Language (optional), e.g. en", asr.language(), false);
        new AlertDialog.Builder(this).setTitle("Speech-to-text (any OpenAI-compatible API)").setView(box)
                .setPositiveButton("Save", (d, w) -> asr.save(url.getText().toString(), key.getText().toString(),
                        model.getText().toString(), lang.getText().toString()))
                .setNegativeButton("Cancel", null).show();
    }

    private EditText field(LinearLayout box, String hint, String value, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(e);
        return e;
    }

    private void transcribe(Protocol.DeviceFile f) {
        if (!asr.configured()) { showAsrSettings(); return; }
        byte[] raw = readDone(f);
        if (raw == null) return;
        log("Transcribing " + f.fileName() + " ...");
        new Thread(() -> {
            try {
                String text = asr.transcribe(raw, (done, total) -> { if (total > 1) log("  part " + done + " / " + total); });
                log("Transcript:\n" + text);
            } catch (IOException e) { log("Transcription failed: " + e.getMessage()); }
        }).start();
    }

    // ------------------------------------------------------------------ import from USB (or any file)

    /*
     * Connected with a data cable (USB 2.0), the card shows up as a USB drive. The system file picker opens the drive;
     * we read the chosen file and transcribe it. Copy speed depends on the phone and the cable and is not yet measured.
     */
    static final int REQ_PICK = 7;
    static final int MAX_SINGLE_UPLOAD = 25 * 1024 * 1024;   // typical limit of speech-to-text APIs for one request

    private void pickFile() {
        if (!asr.configured()) { showAsrSettings(); return; }
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_PICK);
    }

    @Override protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        String name = displayName(uri);
        log("Importing " + name + " ...");
        new Thread(() -> {
            try {
                byte[] b = readUri(uri);
                String text;
                if (b.length >= 4 && (startsWith(b, "OggS") || startsWith(b, "RIFF") || startsWith(b, "ID3") || name.toLowerCase().endsWith(".m4a"))) {
                    // Already a normal audio file.
                    if (b.length > MAX_SINGLE_UPLOAD) { log("File is " + Protocol.fmtSize(b.length) + "; most APIs accept up to 25 MB per request."); return; }
                    text = asr.transcribeFile(b, name);
                } else {
                    // The card's own recordings: bare 40-byte Opus packets. Wrapped into Ogg and sent in 10-minute pieces.
                    text = asr.transcribe(b, (done, total) -> { if (total > 1) log("  part " + done + " / " + total); });
                }
                log("Transcript of " + name + ":\n" + text);
            } catch (IOException e) { log("Import failed: " + e.getMessage()); }
        }).start();
    }

    private static boolean startsWith(byte[] b, String magic) {
        byte[] m = magic.getBytes(StandardCharsets.US_ASCII);
        if (b.length < m.length) return false;
        for (int i = 0; i < m.length; i++) if (b[i] != m[i]) return false;
        return true;
    }

    private String displayName(android.net.Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return "audio";
    }

    private byte[] readUri(android.net.Uri uri) throws IOException {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("cannot open file");
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
            return b.toByteArray();
        }
    }

    // ------------------------------------------------------------------ delete

    private void confirmDelete(Protocol.DeviceFile f) {
        if (gatt == null) { log("Connect first."); return; }
        new AlertDialog.Builder(this).setTitle("Delete from the recorder?")
                .setMessage(f.fileName() + "\n\nThis cannot be undone." + (doneFile(f).exists() ? "" : "\nIt has not been downloaded to this phone."))
                .setPositiveButton("Delete", (d, w) -> {
                    log("Deleting " + f.fileName() + " ...");
                    send(Protocol.T_FILE, Protocol.C_DELETE, Protocol.deleteParams(f.fileName()));
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ incoming packets

    private void onPacket(int type, int cmd, byte[] body) {
        if (type == Protocol.T_CTRL) {
            if (cmd == Protocol.C_BAT && body.length > 0) {
                int v = body[0] & 0xFF;
                log("Battery: " + (v == 110 ? "charging" : v + " %"));
            } else if (cmd == Protocol.C_FW) {
                log("Firmware: " + new String(body, StandardCharsets.US_ASCII).trim());
            } else if (cmd == Protocol.C_CAP && body.length >= 8) {
                log("Storage: " + (Protocol.le32(body, 0) / 1024) + " MB free of " + (Protocol.le32(body, 4) / 1024) + " MB");
            } else if (cmd == Protocol.C_ID) {
                log("Device ID: " + new String(body, StandardCharsets.US_ASCII).trim());
            }
        } else if (type == Protocol.T_FILE) {
            if (cmd == Protocol.C_LIST_DATA) {
                files.addAll(Protocol.parseList(body));
                // If 2-18 never arrives, finish about 1.2 s after the last 2-1.
                ui.removeCallbacks(listDone);
                ui.postDelayed(listDone, 1200);
            } else if (cmd == Protocol.C_LIST_END) {
                onListComplete();
            } else if (cmd == Protocol.C_DATA && out != null) {
                try { out.write(body); } catch (IOException e) { log("Write failed: " + e); }
                received += body.length;
                lastDataAt = System.currentTimeMillis();
                if (dlFile != null && received % (256 * 1024) < body.length)
                    log("  " + received * 100 / Math.max(1, dlFile.size) + " %");
            } else if (cmd == Protocol.C_IMPORT_END) {
                int code = body.length > 0 ? body[0] & 0xFF : -1;
                ui.post(() -> onDownloadEnd(code));
            } else if (cmd == Protocol.C_DELETE_ACK) {
                boolean ok = body.length > 0 && body[0] == 0;
                log(ok ? "Deleted." : "Delete failed.");
                if (ok) requestList();
            }
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        closeOut();
        if (player != null) player.release();
        if (gatt != null) { gatt.disconnect(); gatt.close(); }
    }
}
