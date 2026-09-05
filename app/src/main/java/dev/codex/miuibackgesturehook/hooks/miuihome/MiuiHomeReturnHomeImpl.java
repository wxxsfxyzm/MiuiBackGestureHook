package dev.codex.miuibackgesturehook.hooks.miuihome;

import android.content.Context;
import android.os.IBinder;

public abstract class MiuiHomeReturnHomeImpl
        extends MiuiHomeReturnHomeLifecycleImpl {
    protected abstract void handleMiuiHomeReturnHomeBinderDeath(
            MiuiHomeReturnHomeController controller);
    protected abstract void finishDeferredMiuiHomeReturnHomeController(
            MiuiHomeReturnHomeController controller, String reason);

    protected volatile MiuiHomeReturnHomeController miuiHomeReturnHomeController;

    /** Public owner type retained for hook and hot-reload integration. */
    protected final class MiuiHomeReturnHomeController
        extends MiuiHomeReturnHomeLifecycleImpl.ReturnHomeLifecycleController {
        MiuiHomeReturnHomeController(IBinder shellBackAnimation,
                                    ClassLoader classLoader, Context context) {
            super(shellBackAnimation, classLoader, context);
        }

        @Override
        protected void dispatchShellBinderDeath() {
            handleMiuiHomeReturnHomeBinderDeath(this);
        }

        @Override
        protected void dispatchDeferredControllerFinish(String reason) {
            finishDeferredMiuiHomeReturnHomeController(this, reason);
        }
    }
}
