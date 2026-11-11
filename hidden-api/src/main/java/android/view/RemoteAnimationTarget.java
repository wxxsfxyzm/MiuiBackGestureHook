package android.view;

import android.app.ActivityManager;
import android.app.WindowConfiguration;

public final class RemoteAnimationTarget {
    public int taskId;
    public int mode;
    public SurfaceControl leash;
    public WindowConfiguration windowConfiguration;
    public ActivityManager.RunningTaskInfo taskInfo;

    private RemoteAnimationTarget() {
        throw new RuntimeException("Stub");
    }
}
