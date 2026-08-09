package jp.masatolab.kanon;

import android.util.Base64;

import org.json.JSONObject;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Low-latency transcription for captured Android playback audio.
 *
 * Uses the current Realtime transcription session shape with gpt-live-transcribe.
 * Android AudioPlaybackCapture supplies 48 kHz PCM16 mono; this class downsamples
 * to the 24 kHz PCM16 format required by the Realtime API.
 *
 * KANON disables server VAD and explicitly commits short turns so final captions and
 * Japanese translation can be grouped cleanly. Partial English captions arrive before
 * commit through transcript delta events, so commits do not gate live English display.
 */
public final class RealtimeTranscriber {
    public interface Listener {
        void onReady();
        void onStatus(String message);
        void onPartial(String itemId, String cumulativeText);
        void onFinal(String itemId, String transcript);
        void onError(String message);
        void onClosed();
    }

    private static final String WS_URL = "wss://api.openai.com/v1/realtime?intent=transcription";
    private static final long MAX_OUTGOING_QUEUE_BYTES = 192L * 1024L;
    private static final int TARGET_RATE = 24000;

    private static final int MIN_COMMIT_SAMPLES = (int) (TARGET_RATE * 0.65);
    private static final int MAX_COMMIT_SAMPLES = (int) (TARGET_RATE * 2.20);
    private static final int SILENCE_COMMIT_SAMPLES = (int) (TARGET_RATE * 0.16);
    private static final double SILENCE_DB = -48.0;

    private final Listener listener;
    private final OkHttpClient client;
    private final Map<String, StringBuilder> partialByItem = new ConcurrentHashMap<>();
    private final Map<String, Long> sequenceByItem = new ConcurrentHashMap<>();
    private final TreeMap<Long, FinalResult> completedInOrder = new TreeMap<>();

    private volatile WebSocket webSocket;
    private volatile boolean started;
    private volatile boolean ready;
    private volatile boolean firstAudioSent;
    private volatile boolean fatalConfigurationError;
    private int samplesSinceCommit;
    private int silentSamples;
    private long nextAckSequence = 1L;
    private long nextFinalSequence = 1L;

    private static final class FinalResult {
        final String itemId;
        final String text;
        FinalResult(String itemId, String text) {
            this.itemId = itemId;
            this.text = text;
        }
    }

    public RealtimeTranscriber(Listener listener) {
        this.listener = listener;
        this.client = new OkHttpClient.Builder()
                .pingInterval(15, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    public synchronized void start(String apiKey) {
        stop();
        started = true;
        ready = false;
        firstAudioSent = false;
        fatalConfigurationError = false;
        samplesSinceCommit = 0;
        silentSamples = 0;
        nextAckSequence = 1L;
        nextFinalSequence = 1L;
        partialByItem.clear();
        sequenceByItem.clear();
        synchronized (completedInOrder) { completedInOrder.clear(); }

        Request request = new Request.Builder()
                .url(WS_URL)
                .header("Authorization", "Bearer " + apiKey)
                .build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                listener.onStatus("Realtime connected · configuring…");
                try {
                    if (!ws.send(buildSessionUpdate().toString())) {
                        listener.onError("Realtime setup: session.update was not queued");
                    }
                } catch (Throwable t) {
                    listener.onError("Realtime setup: " + safeMessage(t));
                }
            }

            @Override public void onMessage(WebSocket ws, String text) {
                handleServerMessage(text);
            }

            @Override public void onClosing(WebSocket ws, int code, String reason) {
                ready = false;
                ws.close(code, reason);
            }

            @Override public void onClosed(WebSocket ws, int code, String reason) {
                ready = false;
                if (started) listener.onClosed();
            }

            @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
                ready = false;
                String suffix = response == null ? "" : " (HTTP " + response.code() + ")";
                if (started) {
                    listener.onError("Realtime connection: " + safeMessage(t) + suffix);
                    listener.onClosed();
                }
            }
        });
    }

    private JSONObject buildSessionUpdate() throws Exception {
        JSONObject format = new JSONObject()
                .put("type", "audio/pcm")
                .put("rate", TARGET_RATE);

        org.json.JSONArray languages = new org.json.JSONArray().put("en");
        JSONObject transcription = new JSONObject()
                .put("model", "gpt-live-transcribe")
                .put("languages", languages)
                .put("delay", "minimal");

        JSONObject input = new JSONObject()
                .put("format", format)
                .put("noise_reduction", JSONObject.NULL)
                .put("transcription", transcription)
                .put("turn_detection", JSONObject.NULL);

        JSONObject audio = new JSONObject().put("input", input);
        JSONObject session = new JSONObject()
                .put("type", "transcription")
                .put("audio", audio);

        return new JSONObject()
                .put("type", "session.update")
                .put("session", session);
    }

    private void handleServerMessage(String raw) {
        try {
            JSONObject event = new JSONObject(raw);
            String type = event.optString("type", "");

            if ("session.created".equals(type) || "transcription_session.created".equals(type)) {
                listener.onStatus("Realtime session created…");
                return;
            }

            if ("session.updated".equals(type) || "transcription_session.updated".equals(type)) {
                ready = true;
                listener.onReady();
                return;
            }

            if ("input_audio_buffer.committed".equals(type)) {
                String itemId = event.optString("item_id", "");
                if (!itemId.isEmpty()) sequenceByItem.put(itemId, nextAckSequence++);
                listener.onStatus("LIVE · decoding current speech…");
                return;
            }

            if ("conversation.item.input_audio_transcription.delta".equals(type)) {
                String itemId = event.optString("item_id", "item");
                String delta = event.optString("delta", "");
                if (!delta.isEmpty()) {
                    StringBuilder sb = partialByItem.computeIfAbsent(itemId, k -> new StringBuilder());
                    synchronized (sb) {
                        sb.append(delta);
                        listener.onPartial(itemId, sb.toString());
                    }
                }
                return;
            }

            if ("conversation.item.input_audio_transcription.completed".equals(type)) {
                String itemId = event.optString("item_id", "item");
                String transcript = event.optString("transcript", "").trim();
                if (transcript.isEmpty()) {
                    StringBuilder sb = partialByItem.get(itemId);
                    if (sb != null) {
                        synchronized (sb) { transcript = sb.toString().trim(); }
                    }
                }
                partialByItem.remove(itemId);
                if (!transcript.isEmpty()) emitFinalInCommitOrder(itemId, transcript);
                return;
            }

            if ("conversation.item.input_audio_transcription.failed".equals(type)) {
                JSONObject err = event.optJSONObject("error");
                listener.onError("Transcription: " + (err == null ? raw : err.optString("message", raw)));
                return;
            }

            if ("error".equals(type)) {
                JSONObject err = event.optJSONObject("error");
                String message = err == null ? raw : err.optString("message", raw);
                String param = err == null ? "" : err.optString("param", "");
                String code = err == null ? "" : err.optString("code", "");
                if (!code.isEmpty()) message = code + ": " + message;
                if (!param.isEmpty()) message += " [" + param + "]";
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("invalid model") || lower.contains("invalid_model")
                        || lower.contains("cannot be used") || lower.contains("session.update")) {
                    fatalConfigurationError = true;
                }
                listener.onError("Realtime API: " + message);
                if (fatalConfigurationError) {
                    started = false;
                    try {
                        WebSocket socket = webSocket;
                        if (socket != null) socket.close(1000, "configuration error");
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            listener.onError("Realtime parse: " + safeMessage(t));
        }
    }

    private void emitFinalInCommitOrder(String itemId, String transcript) {
        Long sequence = sequenceByItem.remove(itemId);
        if (sequence == null) {
            listener.onFinal(itemId, transcript);
            listener.onStatus("LIVE · caption received");
            return;
        }
        synchronized (completedInOrder) {
            completedInOrder.put(sequence, new FinalResult(itemId, transcript));
            while (true) {
                FinalResult result = completedInOrder.remove(nextFinalSequence);
                if (result == null) break;
                nextFinalSequence++;
                listener.onFinal(result.itemId, result.text);
                listener.onStatus("LIVE · caption received");
            }
        }
    }

    public void appendPcm48k(short[] data, int n) {
        WebSocket ws = webSocket;
        if (!started || !ready || ws == null || data == null || n < 2) return;

        if (ws.queueSize() > MAX_OUTGOING_QUEUE_BYTES) {
            listener.onStatus("Network busy · dropping old audio to stay live");
            samplesSinceCommit = 0;
            silentSamples = 0;
            return;
        }

        int outSamples = n / 2;
        byte[] pcm24 = new byte[outSamples * 2];
        int p = 0;
        double sum = 0.0;
        for (int i = 0; i + 1 < n; i += 2) {
            int mixed = ((int) data[i] + (int) data[i + 1]) / 2;
            short s = (short) mixed;
            pcm24[p++] = (byte) (s & 0xff);
            pcm24[p++] = (byte) ((s >>> 8) & 0xff);
            double v = s / 32768.0;
            sum += v * v;
        }

        try {
            String b64 = Base64.encodeToString(pcm24, Base64.NO_WRAP);
            JSONObject event = new JSONObject()
                    .put("type", "input_audio_buffer.append")
                    .put("audio", b64);
            if (!ws.send(event.toString())) {
                listener.onStatus("Audio queue full · staying live");
                return;
            }

            if (!firstAudioSent) {
                firstAudioSent = true;
                listener.onStatus("Audio streaming · first caption incoming…");
            }

            samplesSinceCommit += outSamples;
            double rms = Math.sqrt(sum / Math.max(1, outSamples));
            double db = 20.0 * Math.log10(Math.max(rms, 0.000001));
            if (db < SILENCE_DB) silentSamples += outSamples;
            else silentSamples = 0;

            boolean silenceBoundary = samplesSinceCommit >= MIN_COMMIT_SAMPLES
                    && silentSamples >= SILENCE_COMMIT_SAMPLES;
            boolean latencyCap = samplesSinceCommit >= MAX_COMMIT_SAMPLES;
            if (silenceBoundary || latencyCap) commitCurrentBuffer(ws);
        } catch (Throwable t) {
            listener.onError("Realtime audio: " + safeMessage(t));
        }
    }

    private void commitCurrentBuffer(WebSocket ws) throws Exception {
        if (samplesSinceCommit < (TARGET_RATE / 10)) return;
        if (!ws.send(new JSONObject().put("type", "input_audio_buffer.commit").toString())) {
            listener.onStatus("Commit queue full · staying live");
            return;
        }
        samplesSinceCommit = 0;
        silentSamples = 0;
    }

    public synchronized void stop() {
        started = false;
        ready = false;
        firstAudioSent = false;
        samplesSinceCommit = 0;
        silentSamples = 0;
        partialByItem.clear();
        sequenceByItem.clear();
        synchronized (completedInOrder) { completedInOrder.clear(); }
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            try { ws.close(1000, "stop"); } catch (Throwable ignored) {}
        }
    }

    public boolean isReady() {
        return ready;
    }

    private static String safeMessage(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return (m == null || m.trim().isEmpty()) ? t.getClass().getSimpleName() : m;
    }
}
