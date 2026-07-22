package android.window;

public final class BackProgressAnimator {
    public interface ProgressCallback {
        void onProgressUpdate(BackEvent event);
    }

    public BackProgressAnimator() {
        throw new RuntimeException("Stub");
    }

    public void onBackStarted(BackMotionEvent event, ProgressCallback callback) {
        throw new RuntimeException("Stub");
    }

    public void onBackProgressed(BackMotionEvent event) {
        throw new RuntimeException("Stub");
    }

    public void reset() {
        throw new RuntimeException("Stub");
    }
}
