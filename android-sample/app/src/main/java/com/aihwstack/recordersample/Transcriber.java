package com.aihwstack.recordersample;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Speech-to-text through any OpenAI-compatible endpoint: POST {baseUrl}/audio/transcriptions
 * (OpenAI, Groq, Azure-compatible gateways, self-hosted Whisper servers ...).
 * The user enters base URL, API key, model and optional language in Settings; the key stays on the phone.
 * Long recordings are sent in 10-minute pieces so that any model's length limit is respected.
 */
public class Transcriber {
    public static final String DEF_URL = "https://api.openai.com/v1", DEF_MODEL = "whisper-1";
    static final int CHUNK_SECONDS = 600;

    public interface Progress { void onProgress(int done, int total); }

    final SharedPreferences prefs;
    Transcriber(Context c) { prefs = c.getSharedPreferences("asr", Context.MODE_PRIVATE); }

    String url() { return prefs.getString("url", DEF_URL); }
    String key() { return prefs.getString("key", ""); }
    String model() { return prefs.getString("model", DEF_MODEL); }
    String language() { return prefs.getString("lang", ""); }
    boolean configured() { return key().length() > 0 && url().length() > 0; }
    void save(String url, String key, String model, String lang) {
        prefs.edit().putString("url", url.trim().replaceAll("/+$", "")).putString("key", key.trim())
                .putString("model", model.trim().length() == 0 ? DEF_MODEL : model.trim()).putString("lang", lang.trim()).apply();
    }

    /** Blocking; call from a background thread. */
    String transcribe(byte[] raw, Progress p) throws IOException {
        int frames = raw.length / OggOpus.FRAME_BYTES;
        int per = CHUNK_SECONDS * OggOpus.FRAMES_PER_SECOND;
        int chunks = Math.max(1, (frames + per - 1) / per);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks; i++) {
            if (p != null) p.onProgress(i, chunks);
            int f0 = i * per, nf = Math.min(per, frames - f0);
            if (nf <= 0) break;
            byte[] ogg = OggOpus.wrap(raw, f0 * OggOpus.FRAME_BYTES, nf * OggOpus.FRAME_BYTES);
            String t = post(ogg, "part" + (i + 1) + ".ogg").trim();
            if (t.length() > 0) { if (sb.length() > 0) sb.append(chunks > 1 ? "\n\n" : " "); sb.append(t); }
        }
        if (p != null) p.onProgress(chunks, chunks);
        return sb.toString();
    }

    /** Blocking; for a file that is already in a container (Ogg, WAV, MP3, M4A ...). Sent in one request. */
    String transcribeFile(byte[] audio, String name) throws IOException { return post(audio, name).trim(); }

    private String post(byte[] audio, String name) throws IOException {
        String boundary = "----airec" + System.nanoTime();
        HttpURLConnection c = (HttpURLConnection) new URL(url() + "/audio/transcriptions").openConnection();
        c.setConnectTimeout(20000); c.setReadTimeout(180000);
        c.setDoOutput(true); c.setRequestMethod("POST");
        c.setRequestProperty("Authorization", "Bearer " + key());
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        DataOutputStream o = new DataOutputStream(c.getOutputStream());
        field(o, boundary, "model", model());
        field(o, boundary, "response_format", "json");
        if (language().length() > 0) field(o, boundary, "language", language());
        o.writeBytes("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\nContent-Type: " + mime(name) + "\r\n\r\n");
        o.write(audio);
        o.writeBytes("\r\n--" + boundary + "--\r\n");
        o.flush(); o.close();
        int code = c.getResponseCode();
        String body = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        c.disconnect();
        if (code >= 400) {
            String m = body;
            try { m = new JSONObject(body).getJSONObject("error").getString("message"); } catch (Exception ignored) { }
            throw new IOException("HTTP " + code + ": " + (m.length() > 300 ? m.substring(0, 300) : m));
        }
        try { return new JSONObject(body).optString("text", body); } catch (Exception e) { return body; }
    }

    static String mime(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".m4a")) return "audio/mp4";
        return "audio/ogg";
    }

    private static void field(DataOutputStream o, String b, String k, String v) throws IOException {
        o.writeBytes("--" + b + "\r\nContent-Disposition: form-data; name=\"" + k + "\"\r\n\r\n");
        o.write(v.getBytes(StandardCharsets.UTF_8));
        o.writeBytes("\r\n");
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int r;
        while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
        in.close();
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }
}
