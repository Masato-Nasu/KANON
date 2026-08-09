package jp.masatolab.kanon;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 1001;
    private static final int REQ_PROJECTION = 1002;

    private MediaProjectionManager projectionManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView statusText;
    private TextView dbText;
    private TextView resultText;
    private TextView keyStatus;
    private TextView overlayStatus;
    private TextView liveStatus;
    private ProgressBar meter;
    private Button startButton;
    private Button stopButton;
    private EditText apiKeyEdit;

    private final Runnable uiTicker = new Runnable() {
        @Override public void run() {
            renderState();
            handler.postDelayed(this, 150);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        setContentView(buildUi());
        handler.post(uiTicker);
    }

    private View buildUi() {
        int pad = dp(22);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, dp(40));
        root.setBackgroundColor(Color.rgb(15, 15, 15));
        scroll.addView(root);

        TextView title = text("KANON", 34, Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = text("観音  ·  v0.1.0  ·  BYOK  ·  LIVE EN + JP", 13, Color.rgb(160,160,160));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.setMargins(0, dp(2), 0, dp(22));
        root.addView(subtitle, subLp);

        TextView concept = text("音を、観る。", 23, Color.WHITE);
        concept.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-1, -2);
        cLp.setMargins(0, 0, 0, dp(24));
        root.addView(concept, cLp);

        TextView desc = text(
                "Androidで再生中の英語音声を、対応アプリから取得してリアルタイムに英語字幕化。\n" +
                "確定した英文の下に日本語訳を重ねて表示します。",
                15, Color.rgb(210,210,210));
        desc.setLineSpacing(0, 1.12f);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(-1, -2);
        dLp.setMargins(0, 0, 0, dp(28));
        root.addView(desc, dLp);

        root.addView(text("OPENAI API KEY", 13, Color.rgb(170,170,170)));
        apiKeyEdit = new EditText(this);
        apiKeyEdit.setHint("sk-…");
        apiKeyEdit.setHintTextColor(Color.rgb(100,100,100));
        apiKeyEdit.setTextColor(Color.WHITE);
        apiKeyEdit.setSingleLine(true);
        apiKeyEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(apiKeyEdit, new LinearLayout.LayoutParams(-1, dp(54)));

        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        Button saveKey = button("SAVE KEY");
        saveKey.setOnClickListener(v -> saveKey());
        Button clearKey = button("CLEAR KEY");
        clearKey.setOnClickListener(v -> {
            SecureKeyStore.clear(this);
            apiKeyEdit.setText("");
            Toast.makeText(this, "API keyを削除しました", Toast.LENGTH_SHORT).show();
            renderState();
        });
        keyRow.addView(saveKey, new LinearLayout.LayoutParams(0, dp(48), 1f));
        keyRow.addView(clearKey, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(keyRow);

        keyStatus = text("", 13, Color.rgb(160,160,160));
        LinearLayout.LayoutParams ksLp = new LinearLayout.LayoutParams(-1, -2);
        ksLp.setMargins(0, dp(6), 0, dp(24));
        root.addView(keyStatus, ksLp);

        root.addView(text("OVERLAY", 13, Color.rgb(170,170,170)));
        overlayStatus = text("", 14, Color.WHITE);
        root.addView(overlayStatus);

        TextView overlayHelp = text(
                "字幕を他のアプリの上に表示するため、重ねて表示の許可が必要です。\n" +
                "開発APKでスイッチが灰色の場合は、端末側の制限解除またはADB許可が必要な場合があります。",
                13, Color.rgb(185,185,185));
        overlayHelp.setLineSpacing(0, 1.12f);
        LinearLayout.LayoutParams ohLp = new LinearLayout.LayoutParams(-1, -2);
        ohLp.setMargins(0, dp(8), 0, dp(8));
        root.addView(overlayHelp, ohLp);

        Button appInfoButton = button("アプリ情報を開く");
        appInfoButton.setOnClickListener(v -> openAppInfo());
        root.addView(appInfoButton, new LinearLayout.LayoutParams(-1, dp(50)));

        Button overlayButton = button("重ねて表示を許可");
        overlayButton.setOnClickListener(v -> requestOverlayPermission());
        LinearLayout.LayoutParams ovLp = new LinearLayout.LayoutParams(-1, dp(50));
        ovLp.setMargins(0, dp(8), 0, dp(24));
        root.addView(overlayButton, ovLp);

        liveStatus = text("", 13, Color.rgb(170,170,170));
        LinearLayout.LayoutParams lsLp = new LinearLayout.LayoutParams(-1, -2);
        lsLp.setMargins(0, 0, 0, dp(10));
        root.addView(liveStatus, lsLp);

        statusText = text("停止中", 20, Color.WHITE);
        root.addView(statusText);
        dbText = text("— dBFS", 13, Color.rgb(160,160,160));
        root.addView(dbText);
        meter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        meter.setMax(1000);
        LinearLayout.LayoutParams meterLp = new LinearLayout.LayoutParams(-1, dp(14));
        meterLp.setMargins(0, dp(8), 0, dp(12));
        root.addView(meter, meterLp);
        resultText = text("", 14, Color.rgb(180,180,180));
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
        rLp.setMargins(0, 0, 0, dp(16));
        root.addView(resultText, rLp);

        startButton = button("START KANON");
        startButton.setOnClickListener(v -> ensurePermissionsAndStart());
        root.addView(startButton, new LinearLayout.LayoutParams(-1, dp(56)));
        stopButton = button("STOP");
        stopButton.setOnClickListener(v -> {
            Intent i = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP);
            startService(i);
        });
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(-1, dp(50));
        stopLp.setMargins(0, dp(8), 0, dp(28));
        root.addView(stopButton, stopLp);

        TextView guide = text(
                "使い方\n\n" +
                "1  OpenAI API keyを保存\n" +
                "2  重ねて表示を許可\n" +
                "3  START KANON → Androidの画面共有を許可\n" +
                "4  英語音声を再生するアプリへ移動\n" +
                "5  KANONの字幕窓に英文と日本語訳が蓄積されます\n\n" +
                "字幕窓は上部をドラッグして移動できます。本文はセッション開始後の全文を保持し、下へ自動スクロールします。MINで折りたたみ、OPENで復帰、STOPで音声取得・字幕・翻訳をすべて終了します。",
                15, Color.rgb(205,205,205));
        guide.setLineSpacing(0, 1.12f);
        root.addView(guide);

        TextView note = text(
                "BYOK / PRIVACY\nAPI keyはAPKに埋め込まず、Android Keystoreでこの端末内に暗号化保存します。取得した音声は保存・書き出しせず、文字起こしのためRealtime APIへ送信します。AndroidのAudioPlaybackCaptureを許可するアプリの音声のみ取得できます。",
                12, Color.rgb(145,145,145));
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.setMargins(0, dp(24), 0, 0);
        root.addView(note, noteLp);

        return scroll;
    }

    private void saveKey() {
        String key = apiKeyEdit.getText().toString().trim();
        if (key.isEmpty()) {
            Toast.makeText(this, "API keyを入力してください", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            SecureKeyStore.save(this, key);
            apiKeyEdit.setText("");
            Toast.makeText(this, "API keyを暗号化保存しました", Toast.LENGTH_SHORT).show();
            renderState();
        } catch (Throwable t) {
            Toast.makeText(this, "保存失敗: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openAppInfo() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "重ねて表示は許可済みです", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                i.setData(Uri.parse("package:" + getPackageName()));
            }
            startActivity(i);
            Toast.makeText(this, "一覧が出た場合は KANON を選んでONにしてください", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            openAppInfo();
        }
    }

    private void ensurePermissionsAndStart() {
        if (!SecureKeyStore.hasKey(this)) {
            Toast.makeText(this, "先にOpenAI API keyを保存してください", Toast.LENGTH_LONG).show();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "先に重ねて表示を許可してください", Toast.LENGTH_LONG).show();
            requestOverlayPermission();
            return;
        }
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), REQ_PERMISSIONS);
        else requestProjection();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) requestProjection();
            else CaptureState.error = "音声権限が必要です";
        }
    }

    private void requestProjection() {
        Intent captureIntent;
        if (Build.VERSION.SDK_INT >= 34) {
            captureIntent = projectionManager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay());
        } else {
            captureIntent = projectionManager.createScreenCaptureIntent();
        }
        startActivityForResult(captureIntent, REQ_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Intent serviceIntent = new Intent(this, CaptureService.class)
                        .setAction(CaptureService.ACTION_START)
                        .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                        .putExtra(CaptureService.EXTRA_RESULT_DATA, data);
                startForegroundService(serviceIntent);
            } else {
                CaptureState.error = "共有が許可されませんでした";
            }
        }
    }

    private void renderState() {
        boolean active = CaptureState.capturing;
        startButton.setEnabled(!active);
        stopButton.setEnabled(active);
        meter.setProgress((int) (CaptureState.levelPercent * 10f));
        if (CaptureState.translating) statusText.setText("TRANSLATING JP");
        else if (CaptureState.liveTranscribing) statusText.setText("LIVE TRANSCRIBING");
        else statusText.setText(CaptureState.status);

        dbText.setText(active ? String.format(java.util.Locale.US, "%.1f dBFS", CaptureState.levelDb) : "— dBFS");
        keyStatus.setText(SecureKeyStore.hasKey(this) ? "✓ API key saved on this device" : "API key not set");
        overlayStatus.setText(Settings.canDrawOverlays(this) ? "✓ overlay allowed" : "Overlay permission required");
        liveStatus.setText(active ? CaptureState.liveStatus : "English LIVE captions + Japanese translation");

        if (!CaptureState.error.isEmpty()) {
            resultText.setText(CaptureState.error);
            resultText.setTextColor(Color.rgb(255,140,140));
        } else if (active && CaptureState.nonSilentFrames > 6) {
            resultText.setText("✓ 音声取得中 — 字幕全文を蓄積中");
            resultText.setTextColor(Color.rgb(210,255,210));
        } else if (active) {
            resultText.setText("音声待ち");
            resultText.setTextColor(Color.rgb(180,180,180));
        } else {
            resultText.setText("停止中");
            resultText.setTextColor(Color.rgb(170,170,170));
        }
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setAllCaps(false);
        return b;
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(uiTicker);
        super.onDestroy();
    }
}
