-keep class dev.codex.miuibackgesturehook.MiuiBackGestureHook {
    public <init>();
}

# AndroidX Window probes these optional OEM-provided APIs at runtime. They are absent from the
# application classpath by design and must not make release shrinking fail.
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**

-repackageclasses
-allowaccessmodification
-overloadaggressively
