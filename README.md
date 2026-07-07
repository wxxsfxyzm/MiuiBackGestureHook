# MIUI SystemUI Back Gesture Hook

LSPosed module using modern Xposed API 102 for SystemUI-side MIUI back gesture research.

## Build

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## AOSP References

Checked-in AOSP reference snippets live under:

```text
refs/aosp_back/
```

The directory is split by component:

```text
refs/aosp_back/shell/
refs/aosp_back/systemui/
```

## Scope

The static scope is declared in:

```text
app/src/main/resources/META-INF/xposed/scope.list
```

Current scopes:

```text
com.android.systemui
system
```

## Hot Reload

API 102 hot reload is enabled through:

```text
autoHotReload=true
```

The module implements `onHotReloading(...)` and `onHotReloaded(...)`.

## Entry

The module entry is:

```text
dev.codex.miuibackgesturehook.MiuiBackGestureHook
```

Registered through:

```text
app/src/main/resources/META-INF/xposed/java_init.list
```

## License

Apache License 2.0. See [LICENSE](LICENSE).
