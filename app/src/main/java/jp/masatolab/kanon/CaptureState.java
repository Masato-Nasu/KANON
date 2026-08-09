package jp.masatolab.kanon;

public final class CaptureState {
    private CaptureState() {}

    public static volatile boolean capturing = false;
    public static volatile float levelPercent = 0f;
    public static volatile double levelDb = -120.0;
    public static volatile long nonSilentFrames = 0;
    public static volatile String status = "停止中";
    public static volatile String error = "";
    public static volatile boolean liveTranscribing = false;
    public static volatile String liveTranscript = "";
    public static volatile boolean translating = false;
    public static volatile String liveStatus = "IDLE";

    public static void reset() {
        capturing = false;
        levelPercent = 0f;
        levelDb = -120.0;
        nonSilentFrames = 0;
        status = "停止中";
        error = "";
        liveTranscribing = false;
        liveTranscript = "";
        translating = false;
        liveStatus = "IDLE";
    }
}
