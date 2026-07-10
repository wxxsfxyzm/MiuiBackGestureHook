package android.window;

public final class BackTouchTracker {
    public enum TouchTrackerState {
        INITIAL,
        ACTIVE,
        FINISHED
    }

    public void update(float touchX, float touchY) { throw new RuntimeException("Stub"); }
    public void setState(TouchTrackerState state) { throw new RuntimeException("Stub"); }
    public boolean isInInitialState() { throw new RuntimeException("Stub"); }
    public void reset() { throw new RuntimeException("Stub"); }
    public BackMotionEvent createProgressEvent() { throw new RuntimeException("Stub"); }
    public BackMotionEvent createProgressEvent(float progress) {
        throw new RuntimeException("Stub");
    }
    public void setProgressThresholds(float linearDistance, float maxDistance,
                                      float nonLinearFactor) {
        throw new RuntimeException("Stub");
    }
}
