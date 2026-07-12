# Predictive-back opt-in manager research

## Goal

Replace the manifest-only `android:enableOnBackInvokedCallback` decision with a
module-controlled per-application policy. The first experiment force-enables only
Android Settings (`com.android.settings`); no management UI is included.

## Android 16 / Xiaomi decision points

The canonical check is:

```text
android.window.WindowOnBackInvokedDispatcher
    .isOnBackInvokedCallbackEnabled(
        ActivityInfo, ApplicationInfo, Supplier<Context>)
```

Its normal order is global feature flags, an Activity-level manifest override, the
Application-level manifest value, then an optional window-attribute fallback.

This check runs in two important places:

1. In the application process, callback registration uses it to accept or reject an
   ordinary `OnBackInvokedCallback`.
2. In `system_server`, `ActivityRecord` uses it during construction and caches the
   result in `mOptInOnBackInvoked`.

Therefore a reliable policy must be applied in both the target app process and
`system_server`. Changing only `ApplicationInfo` or only the client-side checker can
leave WM with a contradictory cached opt-in state.

## Initial experiment

- Add `com.android.settings` to static scope.
- Hook the three-argument canonical method in Settings and system_server.
- Return `true` only when `ActivityInfo.packageName` or
  `ApplicationInfo.packageName` equals `com.android.settings`.
- Preserve the original result for every other package.
- Log each forced decision to LSPosed module logs.

This proves control of the framework decision. It does not manufacture app-defined
animation callbacks: legacy screens may still expose only the framework compatibility
callback or cross-activity animation behavior.
