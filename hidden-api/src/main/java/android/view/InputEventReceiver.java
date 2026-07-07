package android.view;

import android.os.Looper;

public abstract class InputEventReceiver {
    public InputEventReceiver(InputChannel inputChannel, Looper looper) {
        throw new RuntimeException("Stub");
    }

    public void dispose() {
        throw new RuntimeException("Stub");
    }

    protected void finishInputEvent(InputEvent event, boolean handled) {
        throw new RuntimeException("Stub");
    }

    public void onInputEvent(InputEvent event) {
        throw new RuntimeException("Stub");
    }
}
