# MIUI SystemUI Back Gesture Hook

LSPosed module using modern Xposed API 102 for SystemUI-side MIUI back gesture research.

## Build

```powershell
.\gradlew.bat clean assembleRelease
```

The release APK is generated at:

```text
app/build/outputs/apk/release/app-release.apk
```

Follow `AGENTS.md` for signing configuration and release-build checks. Do not commit
keystores, signing credentials, APKs, or build outputs.

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
com.miui.home
system
```

## Compatibility

The MiuiHome integration is best matched with Xiaomi System Launcher
version `7.50.xx`.

## Hot Reload

API 102 hot reload is enabled through:

```text
autoHotReload=true
```

The module implements `onHotReloading(...)` and `onHotReloaded(...)`.

## Developer handoff

The implementation map, known limitations, debugging lessons, and project rules are documented in:

- [Feature map and implementation notes](docs/功能实现与代码地图.md)
- [Handoff guide, pitfalls, and development rules](docs/接手开发规范与踩坑记录.md)

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
