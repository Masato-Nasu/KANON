package jp.masatolab.kanon;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class CaptureService extends Service {
    public static final String ACTION_START = "jp.masatolab.kanon.START";
    public static final String ACTION_STOP = "jp.masatolab.kanon.STOP";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final int NOTIFICATION_ID = 4041;
    private static final String CHANNEL_ID = "kanon_capture";
    private static final int SAMPLE_RATE = 48000;
    private static final String OVERLAY_PREFS = "kanon_overlay_prefs";
    private static final String PREF_X = "caption_x";
    private static final String PREF_Y = "caption_y";

    private MediaProjection mediaProjection;
    private AudioRecord audioRecord;
    private Thread captureThread;
    private volatile boolean running;
    private volatile boolean cleaningUp;

    private final Object liveTextLock = new Object();
    private ThreadPoolExecutor translationExecutor;
    private RealtimeTranscriber realtimeTranscriber;
    private volatile boolean realtimeReconnectPending = false;

    private static final class LiveSegment {
        final long id;
        final String english;
        volatile String japanese = "";
        volatile boolean translating = false;
        volatile boolean translationDone = false;

        LiveSegment(long id, String english) {
            this.id = id;
            this.english = english;
        }
    }

    private final List<LiveSegment> liveSegments = new ArrayList<>();
    private long nextLiveSegmentId = 1L;
    private volatile String livePartialText = "";

    private WindowManager windowManager;
    private View livePanel;
    private TextView liveText;
    private ScrollView liveScroll;
    private TextView dragHandle;
    private TextView statusText;
    private Button minButton;
    private Button stopButton;
    private WindowManager.LayoutParams livePanelLp;
    private volatile boolean collapsed = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        translationExecutor = new ThreadPoolExecutor(
                3, 3, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16),
                r -> new Thread(r, "kanon-live-translate"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopCapture(true);
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            startForeground(
                    NOTIFICATION_ID,
                    buildNotification("英語字幕 + 日本語訳を表示中"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = (Intent) intent.getParcelableExtra(EXTRA_RESULT_DATA);
            startCapture(resultCode, resultData);
        }
        return START_NOT_STICKY;
    }

    private void startCapture(int resultCode, Intent resultData) {
        cleaningUp = false;
        CaptureState.error = "";
        CaptureState.nonSilentFrames = 0;
        CaptureState.status = "CAPTURING";
        CaptureState.liveTranscribing = false;
        CaptureState.translating = false;
        CaptureState.liveStatus = "STARTING";

        synchronized (liveTextLock) {
            liveSegments.clear();
            nextLiveSegmentId = 1L;
            livePartialText = "";
            CaptureState.liveTranscript = "";
        }

        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mediaProjection = manager.getMediaProjection(resultCode, resultData);
            if (mediaProjection == null) throw new IllegalStateException("MediaProjection token is null");

            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    stopCapture(false);
                    stopSelf();
                }
            }, mainHandler);

            AudioPlaybackCaptureConfiguration config =
                    new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .excludeUid(getApplicationInfo().uid)
                            .build();

            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();

            int minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int bufferBytes = Math.max(minBuffer * 2, SAMPLE_RATE / 2);

            audioRecord = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .setAudioPlaybackCaptureConfig(config)
                    .build();

            audioRecord.startRecording();
            running = true;
            CaptureState.capturing = true;
            CaptureState.liveStatus = "Connecting to LIVE captions…";

            showLivePanelIfAllowed();
            startRealtimeTranscription();

            captureThread = new Thread(() -> readLoop(bufferBytes), "kanon-audio-capture");
            captureThread.start();
        } catch (Throwable t) {
            CaptureState.error = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            CaptureState.status = "FAILED";
            CaptureState.capturing = false;
            stopCapture(false);
            stopSelf();
        }
    }

    private void readLoop(int bufferBytes) {
        short[] buffer = new short[Math.max(1024, bufferBytes / 2)];
        while (running && audioRecord != null) {
            int n = audioRecord.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
            if (n > 0) {
                feedRealtimeLiveAudio(buffer, n);

                double sum = 0.0;
                for (int i = 0; i < n; i++) {
                    double v = buffer[i] / 32768.0;
                    sum += v * v;
                }
                double rms = Math.sqrt(sum / n);
                double db = 20.0 * Math.log10(Math.max(rms, 0.000001));
                float pct = (float) Math.max(0.0, Math.min(100.0, (db + 60.0) / 60.0 * 100.0));
                CaptureState.levelDb = db;
                CaptureState.levelPercent = pct;
                if (db > -48.0) CaptureState.nonSilentFrames++;
            } else if (n < 0) {
                CaptureState.error = "AudioRecord read error: " + n;
                break;
            }
        }
    }

    private void startRealtimeTranscription() {
        if (!CaptureState.capturing || !running) return;
        stopRealtimeTranscription();
        try {
            String apiKey = SecureKeyStore.load(this);
            if (apiKey.isEmpty()) throw new IllegalStateException("OpenAI API keyが未設定です");

            realtimeTranscriber = new RealtimeTranscriber(new RealtimeTranscriber.Listener() {
                @Override public void onReady() {
                    CaptureState.liveTranscribing = true;
                    CaptureState.error = "";
                    CaptureState.liveStatus = "LIVE · EN+JP";
                    mainHandler.post(() -> {
                        showLivePanelIfAllowed();
                        refreshLivePanelLayout();
                    });
                }

                @Override public void onStatus(String message) {
                    CaptureState.liveStatus = message == null ? "" : message;
                    mainHandler.post(() -> refreshLivePanelLayout());
                }

                @Override public void onPartial(String itemId, String cumulativeText) {
                    handleRealtimePartial(cumulativeText);
                }

                @Override public void onFinal(String itemId, String transcript) {
                    handleRealtimeFinal(transcript);
                }

                @Override public void onError(String message) {
                    CaptureState.error = message;
                    CaptureState.liveStatus = "ERROR";
                    mainHandler.post(() -> refreshLivePanelLayout());
                }

                @Override public void onClosed() {
                    CaptureState.liveTranscribing = false;
                    if (running && CaptureState.capturing) {
                        CaptureState.liveStatus = "Reconnecting…";
                        mainHandler.post(() -> refreshLivePanelLayout());
                        scheduleRealtimeReconnect();
                    }
                }
            });
            realtimeTranscriber.start(apiKey);
        } catch (Throwable t) {
            CaptureState.error = "REALTIME: " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            CaptureState.liveStatus = "ERROR";
            mainHandler.post(() -> refreshLivePanelLayout());
        }
    }

    private void stopRealtimeTranscription() {
        realtimeReconnectPending = false;
        CaptureState.liveTranscribing = false;
        RealtimeTranscriber rt = realtimeTranscriber;
        realtimeTranscriber = null;
        if (rt != null) {
            try { rt.stop(); } catch (Throwable ignored) {}
        }
    }

    private void scheduleRealtimeReconnect() {
        if (!running || !CaptureState.capturing || realtimeReconnectPending) return;
        realtimeReconnectPending = true;
        mainHandler.postDelayed(() -> {
            realtimeReconnectPending = false;
            if (running && CaptureState.capturing) startRealtimeTranscription();
        }, 1800);
    }

    private void feedRealtimeLiveAudio(short[] data, int n) {
        RealtimeTranscriber rt = realtimeTranscriber;
        if (rt != null) rt.appendPcm48k(data, n);
    }

    private void handleRealtimePartial(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return;
        synchronized (liveTextLock) {
            livePartialText = t;
        }
        refreshLiveTextFromSegments();
    }

    private void handleRealtimeFinal(String transcript) {
        String en = transcript == null ? "" : transcript.trim();
        if (en.isEmpty()) return;

        LiveSegment seg;
        synchronized (liveTextLock) {
            // Every completed transcription event is appended. We intentionally do not
            // deduplicate by item_id: some Realtime transports may reuse/miss that id,
            // which previously caused FULL history to stop after the first sentence.
            seg = new LiveSegment(nextLiveSegmentId++, en);
            liveSegments.add(seg);
            livePartialText = "";
        }

        CaptureState.liveStatus = "English ready · translating JP…";
        refreshLiveTextFromSegments();
        scheduleTranslation(seg);
    }

    private void scheduleTranslation(LiveSegment seg) {
        if (seg == null || translationExecutor == null) return;
        synchronized (seg) {
            if (seg.translating || seg.translationDone || !seg.japanese.isEmpty()) return;
            seg.translating = true;
        }
        refreshLiveTextFromSegments();

        try {
            translationExecutor.execute(() -> {
                CaptureState.translating = true;
                try {
                    String apiKey = SecureKeyStore.load(this);
                    if (apiKey.isEmpty()) throw new IllegalStateException("OpenAI API keyが未設定です");
                    String ja = translateJapanese(seg.english, apiKey);
                    synchronized (seg) {
                        seg.japanese = ja;
                        seg.translationDone = true;
                        seg.translating = false;
                    }
                    CaptureState.liveStatus = "LIVE · EN+JP";
                } catch (Throwable t) {
                    synchronized (seg) {
                        seg.japanese = "［日本語訳エラー］";
                        seg.translationDone = true;
                        seg.translating = false;
                    }
                    CaptureState.error = "JP: " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
                    CaptureState.liveStatus = "LIVE · EN OK · JP ERROR";
                } finally {
                    CaptureState.translating = false;
                    refreshLiveTextFromSegments();
                }
            });
        } catch (Throwable rejected) {
            synchronized (seg) {
                seg.translating = false;
                seg.translationDone = true;
                seg.japanese = "［日本語訳をスキップ］";
            }
            CaptureState.error = "JP queue: " + rejected.getClass().getSimpleName();
            refreshLiveTextFromSegments();
        }
    }

    private void refreshLiveTextFromSegments() {
        final String full;

        synchronized (liveTextLock) {
            StringBuilder builder = new StringBuilder();
            for (LiveSegment seg : liveSegments) {
                if (builder.length() > 0) builder.append("\n\n");
                builder.append(seg.english);

                String ja;
                boolean waiting;
                synchronized (seg) {
                    ja = seg.japanese == null ? "" : seg.japanese.trim();
                    waiting = seg.translating && ja.isEmpty();
                }
                if (!ja.isEmpty()) builder.append("\n").append(ja);
                else if (waiting) builder.append("\n（日本語訳中…）");
                else if (!seg.translationDone) builder.append("\n（日本語訳待ち…）");
            }

            String partial = livePartialText == null ? "" : livePartialText.trim();
            if (!partial.isEmpty()) {
                if (builder.length() > 0) builder.append("\n\n");
                builder.append(partial).append("\n（英語認識中…）");
            }

            full = builder.toString();
            CaptureState.liveTranscript = full;
        }

        mainHandler.post(() -> {
            showLivePanelIfAllowed();
            if (liveText != null) {
                if (!CaptureState.error.isEmpty() && full.isEmpty()) {
                    liveText.setText("LIVE ERROR\n" + CaptureState.error);
                } else {
                    liveText.setText(full.isEmpty() ? "Listening…" : full);
                }
            }
            if (!collapsed && liveScroll != null) {
                liveScroll.post(() -> liveScroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private String translateJapanese(String english, String apiKey) throws Exception {
        String input = english == null ? "" : english.trim();
        if (input.isEmpty()) return "";

        URL url = new URL("https://api.openai.com/v1/responses");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        JSONObject payload = new JSONObject();
        payload.put("model", "gpt-5-nano");
        payload.put("instructions", "Translate the user's English into natural concise Japanese. Preserve names, numbers, tone, and nuance. Return only the Japanese translation. Do not add labels, explanations, or quotation marks.");
        payload.put("input", input);
        payload.put("max_output_tokens", 500);
        payload.put("store", false);
        payload.put("reasoning", new JSONObject().put("effort", "minimal"));
        payload.put("text", new JSONObject().put("verbosity", "low"));

        byte[] requestBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(requestBytes.length);
        try (BufferedOutputStream out = new BufferedOutputStream(conn.getOutputStream())) {
            out.write(requestBytes);
            out.flush();
        }

        int code = conn.getResponseCode();
        InputStream response = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = readAll(response);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            String detail = body;
            try {
                JSONObject j = new JSONObject(body);
                if (j.optJSONObject("error") != null) {
                    detail = j.optJSONObject("error").optString("message", body);
                }
            } catch (Throwable ignored) {}
            throw new IllegalStateException("OpenAI translation " + code + ": " + detail);
        }

        JSONObject json = new JSONObject(body);
        JSONArray output = json.optJSONArray("output");
        if (output != null) {
            for (int i = 0; i < output.length(); i++) {
                JSONObject item = output.optJSONObject(i);
                if (item == null) continue;
                JSONArray content = item.optJSONArray("content");
                if (content == null) continue;
                for (int j = 0; j < content.length(); j++) {
                    JSONObject part = content.optJSONObject(j);
                    if (part == null) continue;
                    if ("output_text".equals(part.optString("type"))) {
                        String text = part.optString("text", "").trim();
                        if (!text.isEmpty()) return text;
                    }
                }
            }
        }
        throw new IllegalStateException("日本語訳の結果が空でした");
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream input = in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = input.read(buf)) >= 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        }
    }

    private void showLivePanelIfAllowed() {
        if (!Settings.canDrawOverlays(this) || livePanel != null) return;
        mainHandler.post(() -> {
            if (!Settings.canDrawOverlays(this) || livePanel != null) return;

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(10), dp(10), dp(10), dp(10));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.rgb(5, 5, 5));
            bg.setStroke(dp(1), Color.rgb(95, 95, 95));
            bg.setCornerRadius(dp(18));
            box.setBackground(bg);

            dragHandle = new TextView(this);
            dragHandle.setText("↕  KANON  ·  LIVE EN+JP");
            dragHandle.setTextColor(Color.WHITE);
            dragHandle.setTextSize(13);
            dragHandle.setGravity(Gravity.CENTER);
            GradientDrawable dragBg = new GradientDrawable();
            dragBg.setColor(Color.rgb(42, 42, 42));
            dragBg.setCornerRadius(dp(10));
            dragHandle.setBackground(dragBg);
            box.addView(dragHandle, new LinearLayout.LayoutParams(dp(316), dp(40)));

            statusText = new TextView(this);
            statusText.setTextColor(Color.rgb(180, 180, 180));
            statusText.setTextSize(10);
            statusText.setGravity(Gravity.CENTER_VERTICAL);
            statusText.setSingleLine(true);
            LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(dp(316), dp(26));
            statusLp.setMargins(dp(4), dp(2), 0, 0);
            box.addView(statusText, statusLp);

            liveScroll = new ScrollView(this);
            liveText = new TextView(this);
            liveText.setTextColor(Color.WHITE);
            liveText.setTextSize(15);
            liveText.setLineSpacing(0, 1.14f);
            liveText.setText("Listening…");
            liveScroll.addView(liveText, new ScrollView.LayoutParams(dp(316), WindowManager.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(dp(316), dp(270));
            scrollLp.setMargins(0, dp(4), 0, dp(4));
            box.addView(liveScroll, scrollLp);

            LinearLayout controls = new LinearLayout(this);
            controls.setOrientation(LinearLayout.HORIZONTAL);
            controls.setGravity(Gravity.CENTER_VERTICAL);

            minButton = smallButton("MIN");
            minButton.setOnClickListener(v -> {
                collapsed = !collapsed;
                refreshLivePanelLayout();
            });
            controls.addView(minButton, new LinearLayout.LayoutParams(0, dp(38), 1f));

            stopButton = smallButton("STOP");
            stopButton.setTextColor(Color.rgb(255, 105, 105));
            stopButton.setOnClickListener(v -> {
                stopCapture(true);
                stopSelf();
            });
            controls.addView(stopButton, new LinearLayout.LayoutParams(0, dp(38), 1f));

            LinearLayout.LayoutParams controlsLp = new LinearLayout.LayoutParams(dp(316), dp(38));
            controlsLp.setMargins(0, dp(2), 0, 0);
            box.addView(controls, controlsLp);

            livePanel = box;
            livePanelLp = overlayParams(dp(340), dp(390));
            livePanelLp.gravity = Gravity.TOP | Gravity.START;
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int defaultX = Math.max(dp(8), (screenW - dp(340)) / 2);
            livePanelLp.x = getSharedPreferences(OVERLAY_PREFS, MODE_PRIVATE).getInt(PREF_X, defaultX);
            livePanelLp.y = getSharedPreferences(OVERLAY_PREFS, MODE_PRIVATE).getInt(PREF_Y, dp(72));

            installDragBehavior();

            try {
                windowManager.addView(livePanel, livePanelLp);
                refreshLivePanelLayout();
            } catch (Throwable t) {
                livePanel = null;
                livePanelLp = null;
                CaptureState.error = "OVERLAY: " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            }
        });
    }

    private void installDragBehavior() {
        if (dragHandle == null) return;
        final int[] startX = new int[1];
        final int[] startY = new int[1];
        final float[] touchX = new float[1];
        final float[] touchY = new float[1];

        dragHandle.setOnTouchListener((v, e) -> {
            if (livePanelLp == null || livePanel == null) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX[0] = livePanelLp.x;
                    startY[0] = livePanelLp.y;
                    touchX[0] = e.getRawX();
                    touchY[0] = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int maxX = Math.max(0, getResources().getDisplayMetrics().widthPixels - dp(340));
                    int maxY = Math.max(dp(8), getResources().getDisplayMetrics().heightPixels - dp(90));
                    livePanelLp.x = Math.max(0, Math.min(maxX,
                            startX[0] + (int) (e.getRawX() - touchX[0])));
                    livePanelLp.y = Math.max(dp(8), Math.min(maxY,
                            startY[0] + (int) (e.getRawY() - touchY[0])));
                    try { windowManager.updateViewLayout(livePanel, livePanelLp); } catch (Throwable ignored) {}
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    getSharedPreferences(OVERLAY_PREFS, MODE_PRIVATE).edit()
                            .putInt(PREF_X, livePanelLp.x)
                            .putInt(PREF_Y, livePanelLp.y)
                            .apply();
                    return true;
                default:
                    return false;
            }
        });
    }

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private void refreshLivePanelLayout() {
        if (livePanel == null) return;

        if (dragHandle != null) {
            dragHandle.setText("↕  KANON  ·  LIVE EN+JP" + (collapsed ? "  ·  folded" : ""));
        }
        if (statusText != null) {
            String status = CaptureState.liveStatus == null ? "" : CaptureState.liveStatus;
            statusText.setText(status + String.format(java.util.Locale.US, "  ·  %.0f dB", CaptureState.levelDb));
            statusText.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        }
        if (minButton != null) minButton.setText(collapsed ? "OPEN" : "MIN");

        if (liveText != null && !collapsed) {
            String text = CaptureState.liveTranscript;
            if (!CaptureState.error.isEmpty() && text.isEmpty()) {
                liveText.setText("LIVE ERROR\n" + CaptureState.error);
            } else {
                liveText.setText(text.isEmpty() ? "Listening…" : text);
            }
        }

        if (liveScroll != null) {
            liveScroll.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        }

        if (livePanelLp != null) {
            livePanelLp.height = dp(collapsed ? 100 : 390);
            try { windowManager.updateViewLayout(livePanel, livePanelLp); } catch (Throwable ignored) {}
        }

        if (!collapsed && liveScroll != null) {
            liveScroll.post(() -> liveScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private WindowManager.LayoutParams overlayParams(int width, int height) {
        int type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        return new WindowManager.LayoutParams(
                width,
                height,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
    }

    private void removeLivePanel() {
        if (livePanel != null) {
            try { windowManager.removeView(livePanel); } catch (Throwable ignored) {}
        }
        livePanel = null;
        liveText = null;
        liveScroll = null;
        dragHandle = null;
        statusText = null;
        minButton = null;
        stopButton = null;
        livePanelLp = null;
        collapsed = false;
    }

    private synchronized void stopCapture(boolean stopProjection) {
        if (cleaningUp) return;
        cleaningUp = true;
        running = false;
        stopRealtimeTranscription();
        removeLivePanel();
        if (translationExecutor != null) translationExecutor.getQueue().clear();

        try {
            if (audioRecord != null) {
                try { audioRecord.stop(); } catch (Throwable ignored) {}
                audioRecord.release();
            }
        } finally {
            audioRecord = null;
        }

        if (stopProjection && mediaProjection != null) {
            try { mediaProjection.stop(); } catch (Throwable ignored) {}
        }
        mediaProjection = null;

        synchronized (liveTextLock) {
            liveSegments.clear();
            livePartialText = "";
            CaptureState.liveTranscript = "";
        }

        CaptureState.capturing = false;
        CaptureState.liveTranscribing = false;
        CaptureState.translating = false;
        CaptureState.liveStatus = "IDLE";
        CaptureState.levelPercent = 0f;
        CaptureState.levelDb = -120.0;
        if (CaptureState.error.isEmpty()) CaptureState.status = "停止中";
        stopForeground(STOP_FOREGROUND_REMOVE);
        cleaningUp = false;
        if (stopProjection) stopSelf();
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "KANON live captions",
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent stopIntent = new Intent(this, CaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 4042, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action stopAction = new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopPending).build();
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("KANON")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .addAction(stopAction)
                .build();
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopCapture(true);
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        stopCapture(true);
        if (translationExecutor != null) translationExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
