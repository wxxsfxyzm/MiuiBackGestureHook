# Runtime contracts

This document describes behavior that cross-process fixes must preserve. Read the section
relevant to a change and inspect its current implementation/tests. Specific call sites,
instruction shapes and old experiment recipes are not permanent design or approval limits.
Repository-wide safety and authorization boundaries are in [AGENTS.md](../AGENTS.md).

## Platform and process roles

The AOSP reference baseline is `android-16.0.0_r1` (commit
`99b01a65cc4c104933788b3143285ab6bae65827`). Confirm against that revision when changing
framework contracts; checked-in reference snippets are not automatically the exact baseline.

| Process | Android 16 | Android 17+ |
| --- | --- | --- |
| SystemUI | Gesture admission, BackPanel, Shell integration and CTS platform caller | Same side-gesture ownership; no Android 16 CTS handle receiver |
| MiuiHome | Java launcher hooks and standard Shell runner | APK native entry via HYOS; no launcher Java hooks |
| POCO Launcher | Selected-package Java compatibility | No Java hooks or native support |
| system_server | Existing compatibility and selected-Activity opt-in | Same narrowly scoped responsibilities |
| Google App | Optional Live Translate and Lensient/Ask Screen | Preserve supported feature/version gates; do not broaden them |

Launcher package selection binds resources, targets and package/UID authentication for an
entire process generation. Home classification uses the resolved HOME/SECONDARY_HOME
component, so launcher-owned settings do not become idle Home by package-name inference.

## Side input and system bars

- The native physical edge window retains DOWN targeting, touch regions and redirect/exclusion
  decisions. SystemUI's monitor remains a spy until its pending DOWN exactly matches the
  launcher's accepted event identity, display, edge and arbiter generation.
- On Android 17 the Side trigger area reaches MiuiHome only as system gesture exclusion:
  system_server adds the main-display side-frame rows outside the configured band to both the
  restricted and unrestricted regions after the native calculation, outside the per-window exclusion limit.
  MiuiHome keeps its edge windows and performs the redirect itself; SystemUI applies the same
  band with matching rounding. Immersive side swipes outside the band follow the same exclusion,
  so they reach the application without requesting transient bars. Default preferences leave the
  native region untouched. SystemUI and WMS share integer band boundaries and read height/position
  from one preference snapshot. Remote preference connection/retries run outside the WMS global
  lock; unreadable state restores native exclusion. Hot reload retires the listener and queued
  refreshes, replaces the calculation hook, and refreshes the current local WMS owner immediately.
  Optional strips are prepared on copies and published only when both resulting regions fit
  MiuiHome's 64-rectangle callback limit. Rejected preparation leaves this traversal's native
  output intact; never truncate app exclusions or reuse a previous module region. An originally
  oversized native region retains Xiaomi's own behavior and is not repaired by this extension.
  Pass-through changes delivery only; it does not override application gesture feature policies.
- Existing admission uses an 8dp outward threshold with non-terminal direction qualification:
  outward motion at least half the absolute vertical motion. A vertical-leading sample can
  still qualify later. Callback progress uses display width, not the old fixed 220dp distance.
- BackPanel owns thresholds, release commit/cancel and default haptics. Shell receives ordered
  tracker updates after panel callbacks; a rejected/cancelled stream cannot be revived after
  Shell cleanup. Ordinary BACK fallback is restricted to the existing proven native-commit
  or legacy-null-navigation cases; callback-only probes and uncertain/null targets cancel.
- Default admission follows the matching display's native bar-visibility state and transient-bar
  policy. A verified module-owned headless lease can cover intentionally absent NavigationBar
  and Taskbar owners; lifecycle uncertainty does not manufacture a bar window or Taskbar state.
- Explicit fullscreen direct-back mode bypasses only hidden-bar admission and transient yield,
  with its preference and readable display state captured at DOWN. Authentication, exclusion,
  ownership and commit policy still apply. Native mouse-hover/top/bottom/other-display requests
  remain outside the exact side-gesture transient-bar correction.
- IME sensitivity/insets must preserve the native edge owner's accepted-DOWN boundary and
  exclusion behavior. Preserve window-requested bar appearance; use the native auto-hide
  lifecycle rather than module timers or forced Insets visibility.

## Home, Recents and overlays

- Idle Home remains unclaimed. Overview, drawer, Home children/editing and assistant overlays
  probe Shell once at DOWN and accept only a standard TYPE_CALLBACK; otherwise leave input
  unpilfered. NotificationShade/Control Center over Home takes precedence over launcher state.
- Broadcast state belongs to an authenticated sender, arbiter generation and live native/Dart
  owner. Old/destroyed launcher instances cannot overwrite replacements. A new generation
  needs fresh publication; Android 17 ignores legacy Java editing state.
- Native Home-child state follows the complete publication outcome, including false and early
  ineligibility. Do not infer completion from timeouts or clear multilevel UI after one BACK.
  A completed native Home publication can also end that owner's stale Overview observation.
- State publication caches what was actually sent, including its owner/generation. An A -> B -> A
  update during a send still needs corrective A after B; do not mark unsent live state delivered.
- NavigationBar/Taskbar lifecycle changes reconcile real and module-owned headless listeners;
  never forge lifecycle booleans or introduce another touchable shield.

## OPEN interruption and predictive transitions

- Keep Xiaomi's reversible in-app OPEN interruption beside the ordinary AOSP predictive path.
  Capture transitions on their owner and verify animators on the animation executor. Native
  animators finish/reverse themselves; input code consumes immutable identity-bound snapshots.
- If the exact accepted OPEN normally loses reversibility while input is down, an ordinary Shell
  handoff can use accumulated coordinates. Cancellation, replacement or stale input is not that
  handoff. An accepted native merge and an ordinary BACK must never both commit the attempt.
- Launcher OPEN uses a distinct native remote-animation owner. Availability is published only
  after its native state is current, and commands revalidate the same generation at commit.
  Missing/rejected commands receive only the existing one-BACK fallback; cancellation sends none.
- Preserve all four navigation families: callback, cross-Activity, cross-Task and return-to-Home.
  Restore backed registry/runner wiring before inventing animation code. Missing native objects
  or unreadable shape/state do not authorize guessed Surfaces or broad compatibility rules.
- Prepared-transition corrections require immutable Activity/Task/fragment identities, roles,
  flags, bounds, display, rotation and leash ownership/layers. Correct only the proven faulty role
  in the native transaction. Preserve embedded-child and fixed-rotation handling; a component
  name alone neither identifies an Activity instance nor makes two instances ambiguous.
- Targets arriving after prepare resume only the exact held original invocation on the Shell
  owner. Waiting runners stay finished until targets/animation or terminal cleanup arrive;
  unknown reflection results are not permission to finish early. Invoke/cancel reaches the runner
  through normal post-commit before navigation cleanup.
- Preserve native Activity/TaskFragment promotion and feature-flag decisions in system_server.
  Compatibility hooks change only proven shapes; other transitions retain platform behavior.

## Return to Home and appearance

- Return-to-Home uses the standard Shell launcher callback/runner. One native WindowElement /
  spring owns predictive preview and the matching Xiaomi CLOSE, including legitimate reuse
  and OPEN interruption. Do not replace a still-owned spring or finish callback prematurely.
- Match commit signals, runner targets and task/surface identity across prepare, commit and
  cleanup. Keep handoff transactions compositor-atomic; never expose a fullscreen restore
  before the native reparent/geometry transaction. A delayed command needs its immutable
  session identity, not the identity of whichever gesture is current later.
- Native closing providers own application pixels, floating-icon lifecycle, finish callbacks
  and Surface cleanup. Preview blur, wallpaper and shortcut-layer state stay on their owner
  Looper and transfer only to the exact successor; cancellation restores only unchanged owned
  state. Concurrent launcher OPEN must not be overwritten by old CLOSE cleanup.
- Preserve the existing status/navigation-bar follow behavior and each window's requested
  appearance across cancellation and target replacement. Experimental slide, indicator,
  haptic and material options keep their feature gates and native lifecycle; visual-only
  overlays are non-touchable and cannot select commit, change routing or force platform support.
- Status-bar follow retries a missed startup after the native Home binder completes binding.
  Recovery runs on the View owner, revalidates the exact current Home binder/injector/View,
  and respects preference/reload generations and existing App-window retirement. It never
  assumes readiness from elapsed time or replaces an active App window for a repeated bind.

## Circle to Search and Google features

- Native MarkOnly completion is not trigger permission. Observe physical cancellation before
  redirects hide later motion; do not confuse a cancelled window/monitor copy with a new native
  feature admission. Preserve the normal Fn's completion and captured callback ABI; do not
  substitute a consuming FnOnce shim or hand-written closure destruction.
- Applications use the existing native detector/manager and CTS helper. Home uses its own
  admitted UI long press: only the resolved action for exact NavLongPress may be replaced while
  CTS is enabled, retaining the original lookup/await and existing CTS branch. Keep the user's
  saved action intact. Neither route adds a recognizer or treats an upward swipe as a hold.
- Authenticated preference publication applies to the next gesture without replacing native
  hooks. CTS feature-query overrides cover only `android.software.contextualsearch` and
  `com.google.android.feature.CONTEXTUAL_SEARCH`; errors and unknown queries stay original.
- Platform caller identity is SystemUI on Android 16 and MiuiHome on Android 17+. The Google
  provider callback has separate exact-package authentication. Preserve Google's capture,
  context, consent, upload, translation and submission flows.
- Live Translate and Lensient/Ask Screen remain independently gated and default off. Live
  Translate also requires CTS; Ask Screen preserves non-OMNI entrypoints and Google's own
  model/hint decisions. Resolve semantic markers and ownership/call relationships rather than
  obfuscated names, argument indices or a Google version number. No Labs/Phenotype spoofing.

## Predictive opt-in

- Selection starts empty and uses API-102 remote preferences; selected application processes
  are never hooked. Only their current launch ActivityInfo receives opt-in flags, with no shared
  ApplicationInfo mutation. New selection applies after a full application restart.
- Application-level metadata opt-ins are omitted and stale selections are independently ignored
  by system_server. UI metadata-read failure leaves the entry visible; server uncertainty
  preserves the platform decision. Keep the warning about incompatible internal back stacks.

## Lifecycle and evidence

- Hook installation, successful replacement, presence and backfill share lifecycle IDs. Reload
  detaches owned resources and invalidates attempts before restoration; native entry activation
  remains separate from Java hot reload. Resolve the real process/ClassLoader at cold and hot load.
- Native/Dart resolution validates the current image and exact ABI/layout, never inferring a
  live object from a historical offset. Optional resolution failure leaves unrelated paths intact.
  Assembly observations bind to owner epochs; retirement/remap repair waits for active observers.
- Formal native side-handoff proof is one activation and one fresh formal gesture with matching
  accepted-DOWN, SystemUI acceptance/pilfer and Shell start in the same capture. An optional
  readiness warmup is a separate sample. Compilation, visuals and counters prove narrower facts.
- A tested fix records its artifact identity and behavioral limits. Deterministic guards belong
  in tests/scripts; captures, disassembly and experiment narratives belong in local `reports/`.
