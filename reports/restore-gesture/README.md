# AOSP gesture restoration research

This directory archives the completed and ongoing research that was previously kept
inline in `AGENTS.md`.

## Stable baseline

- Primary ownership is SystemUI with a module-created native `InputMonitor`.
- The app-callback (`TYPE_CALLBACK`) path is usable. App-generated progress is
  disabled before Shell handles `BackNavigationInfo`, because the module pilfers the
  app touch stream.
- Progress uses display width; commit remains the stable 48 dp threshold.
- Cross-activity and cross-task runners are restored from Xiaomi Shell objects when
  present. Shell implementation classes remain reflection-only because they belong
  to the SystemUI class loader.
- Remote release follows the Android 16 QPR0 tracker/runner/post-commit ordering.
- The server compatibility path avoids Xiaomi TaskFragment promotion and the
  conflicting unified transition path where required.

## Investigation history

The cross-activity visual investigation established that Shell did not swap targets
or transforms. Server diagnostics showed the transition path reparenting activity
surfaces out of predictive-back leashes. The current compatibility hooks preserve
the remote-animation-only path and its cleanup semantics.

The authoritative reference is `D:/code/aosp-windowmanager/base` at
`android-16.0.0_r1` (`99b01a65cc4c104933788b3143285ab6bae65827`). Checked-in
reference snippets under `refs/aosp_back/` are useful but are not exact QPR0 copies.

Do not revive MiuiHome adapter injection, hand-written surface animations, or old
visual leash patches without an explicit new request. Detailed version-by-version
findings remain available in repository history for `AGENTS.md`.
