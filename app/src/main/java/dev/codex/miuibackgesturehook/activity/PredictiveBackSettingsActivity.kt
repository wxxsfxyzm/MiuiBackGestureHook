// SPDX-License-Identifier: Apache-2.0
package dev.codex.miuibackgesturehook.activity

import android.annotation.SuppressLint
import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import dev.codex.miuibackgesturehook.BuildConfig
import dev.codex.miuibackgesturehook.ModuleApplication
import dev.codex.miuibackgesturehook.NativeHookStatusKind
import dev.codex.miuibackgesturehook.NativeHookStatusProtocol
import dev.codex.miuibackgesturehook.NativeHookStatusUiState
import dev.codex.miuibackgesturehook.PredictiveBackPreferences
import dev.codex.miuibackgesturehook.R
import dev.codex.miuibackgesturehook.util.rememberMiuixBlurBackdrop
import io.github.libxposed.service.XposedService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.foundation.layout.Arrangement
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

class PredictiveBackSettingsActivity :
    ComponentActivity(),
    ModuleApplication.ServiceStateListener {
    private var xposedService: XposedService? by mutableStateOf(null)
    private var serviceStateObserved by mutableStateOf(false)
    private var nativeHookStatus by mutableStateOf(
        NativeHookStatusUiState.checking(Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN),
    )
    private val statusHandler = Handler(Looper.getMainLooper())
    private var statusNonce = 0L
    private var systemUiResponseReceived = false
    private var systemUiReadyReported = false
    private var statusReceiverRegistered = false
    private val statusTimeout = Runnable {
        if (statusNonce != 0L) {
            statusNonce = 0L
            nativeHookStatus = if (!systemUiResponseReceived) {
                NativeHookStatusUiState.noResponse()
            } else if (!systemUiReadyReported) {
                NativeHookStatusUiState(NativeHookStatusKind.SystemUiNotReady)
            } else {
                NativeHookStatusUiState(
                    kind = NativeHookStatusKind.NativeNoResponse,
                    legacyMode = Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN,
                )
            }
        }
    }
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != NativeHookStatusProtocol.ACTION_REPLY) {
                return
            }
            val senderUid = sentFromUid
            val senderPackage = sentFromPackage
            if (senderUid == Process.INVALID_UID
                || senderPackage != NativeHookStatusProtocol.SYSTEM_UI_PACKAGE
                || !isUidOwner(senderUid, NativeHookStatusProtocol.SYSTEM_UI_PACKAGE)
            ) {
                return
            }
            val nonce = intent.getLongExtra(NativeHookStatusProtocol.EXTRA_NONCE, 0L)
            if (nonce <= 0L || nonce != statusNonce) {
                return
            }
            nativeHookStatus = NativeHookStatusUiState.fromReply(intent)
            val nativeResponse = intent.getBooleanExtra(
                NativeHookStatusProtocol.EXTRA_NATIVE_RESPONSE,
                false,
            )
            if (!nativeResponse) {
                systemUiResponseReceived = true
                systemUiReadyReported = intent.getBooleanExtra(
                    NativeHookStatusProtocol.EXTRA_SYSTEMUI_READY,
                    false,
                )
            } else {
                statusHandler.removeCallbacks(statusTimeout)
                statusNonce = 0L
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MiuixTheme(colors = colors) {
                PredictiveBackSettingsScreen(
                    service = xposedService,
                    serviceStateObserved = serviceStateObserved,
                    nativeHookStatus = nativeHookStatus,
                    onRefreshNativeHookStatus = ::requestNativeHookStatus,
                    onClose = { finish() },
                    onOpenGestureTriggerSettings = {
                        startActivity(
                            Intent(
                                this,
                                GestureTriggerSettingsActivity::class.java,
                            ),
                        )
                    },
                    onOpenAppList = {
                        startActivity(
                            Intent(
                                this,
                                PredictiveBackAppListActivity::class.java,
                            ),
                        )
                    },
                    onOpenContextualSearchSettings = {
                        startActivity(
                            Intent(
                                this,
                                ContextualSearchSettingsActivity::class.java,
                            ),
                        )
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ModuleApplication.addServiceStateListener(this, notifyImmediately = true)
        registerStatusReceiver()
        requestNativeHookStatus()
    }

    override fun onStop() {
        unregisterStatusReceiver()
        ModuleApplication.removeServiceStateListener(this)
        super.onStop()
    }

    override fun onServiceStateChanged(service: XposedService?) {
        xposedService = service
        serviceStateObserved = true
    }

    private fun registerStatusReceiver() {
        if (statusReceiverRegistered) {
            return
        }
        val filter = IntentFilter(NativeHookStatusProtocol.ACTION_REPLY)
        registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED)
        statusReceiverRegistered = true
    }

    private fun unregisterStatusReceiver() {
        if (!statusReceiverRegistered) {
            return
        }
        statusHandler.removeCallbacks(statusTimeout)
        statusNonce = 0L
        systemUiResponseReceived = false
        systemUiReadyReported = false
        unregisterReceiver(statusReceiver)
        statusReceiverRegistered = false
    }

    private fun requestNativeHookStatus() {
        if (!statusReceiverRegistered) {
            return
        }
        val nonce = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
        statusNonce = nonce
        systemUiResponseReceived = false
        systemUiReadyReported = false
        nativeHookStatus = NativeHookStatusUiState.checking(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN,
        )
        statusHandler.removeCallbacks(statusTimeout)
        statusHandler.postDelayed(statusTimeout, STATUS_TIMEOUT_MS)
        try {
            val query = Intent(NativeHookStatusProtocol.ACTION_QUERY)
                .setPackage(NativeHookStatusProtocol.SYSTEM_UI_PACKAGE)
                .putExtra(NativeHookStatusProtocol.EXTRA_NONCE, nonce)
                .putExtra("sender_uid", Process.myUid())
            val options = BroadcastOptions.makeBasic()
                .setShareIdentityEnabled(true)
                .toBundle()
            sendBroadcast(query, null, options)
        } catch (_: Throwable) {
            statusHandler.removeCallbacks(statusTimeout)
            statusNonce = 0L
            nativeHookStatus = NativeHookStatusUiState.noResponse()
        }
    }

    private fun isUidOwner(uid: Int, packageName: String) =
        try {
            packageManager.getPackagesForUid(uid)?.contains(packageName) == true
        } catch (_: Throwable) {
            false
        }

    private companion object {
        const val STATUS_TIMEOUT_MS = 2500L
    }
}

@Composable
private fun NativeHookRuntimeStatusCard(
    state: NativeHookStatusUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = state.kind == NativeHookStatusKind.Ready
    val warning = state.kind == NativeHookStatusKind.Checking
            || state.kind == NativeHookStatusKind.WaitingForNative
            || state.kind == NativeHookStatusKind.SystemUiNotReady
            || state.kind == NativeHookStatusKind.NativeNotReady
            || state.kind == NativeHookStatusKind.LegacyNotReady
    val title = when (state.kind) {
        NativeHookStatusKind.Checking -> stringResource(
            if (state.legacyMode) {
                R.string.native_hook_status_legacy_checking_title
            } else {
                R.string.native_hook_status_checking_title
            },
        )

        NativeHookStatusKind.WaitingForNative -> stringResource(
            if (state.legacyMode) {
                R.string.native_hook_status_legacy_waiting_title
            } else {
                R.string.native_hook_status_waiting_title
            },
        )

        NativeHookStatusKind.Ready -> stringResource(
            if (state.legacyMode) {
                R.string.native_hook_status_ready_legacy_title
            } else {
                R.string.native_hook_status_ready_title
            },
        )

        NativeHookStatusKind.SystemUiNotReady ->
            stringResource(R.string.native_hook_status_systemui_not_ready_title)

        NativeHookStatusKind.NativeNotReady ->
            stringResource(R.string.native_hook_status_native_not_ready_title)

        NativeHookStatusKind.LegacyNotReady ->
            stringResource(R.string.native_hook_status_legacy_not_ready_title)

        NativeHookStatusKind.NativeNoResponse ->
            stringResource(
                if (state.legacyMode) {
                    R.string.native_hook_status_legacy_no_response_title
                } else {
                    R.string.native_hook_status_native_no_response_title
                },
            )

        NativeHookStatusKind.ProfileRejected ->
            stringResource(R.string.native_hook_status_profile_rejected_title)

        NativeHookStatusKind.NoResponse -> stringResource(R.string.native_hook_status_no_response_title)
        NativeHookStatusKind.LsPosedUnavailable ->
            stringResource(R.string.native_hook_status_lsposed_unavailable_title)
    }
    val summary = when (state.kind) {
        NativeHookStatusKind.Checking -> stringResource(
            if (state.legacyMode) {
                R.string.native_hook_status_legacy_checking_summary
            } else {
                R.string.native_hook_status_checking_summary
            },
        )

        NativeHookStatusKind.WaitingForNative ->
            stringResource(
                if (state.legacyMode) {
                    R.string.native_hook_status_legacy_waiting_summary
                } else {
                    R.string.native_hook_status_waiting_summary
                },
            )

        NativeHookStatusKind.Ready -> stringResource(
            if (state.legacyMode) {
                R.string.native_hook_status_ready_legacy_summary
            } else if (state.profileDynamic) {
                R.string.native_hook_status_ready_runtime_summary
            } else {
                R.string.native_hook_status_ready_static_summary
            },
        )

        NativeHookStatusKind.SystemUiNotReady ->
            stringResource(R.string.native_hook_status_systemui_not_ready_summary)

        NativeHookStatusKind.NativeNotReady ->
            stringResource(R.string.native_hook_status_native_not_ready_summary)

        NativeHookStatusKind.LegacyNotReady ->
            stringResource(R.string.native_hook_status_legacy_not_ready_summary)

        NativeHookStatusKind.NativeNoResponse ->
            stringResource(
                if (state.legacyMode) {
                    R.string.native_hook_status_legacy_no_response_summary
                } else {
                    R.string.native_hook_status_native_no_response_summary
                },
            )

        NativeHookStatusKind.ProfileRejected ->
            stringResource(R.string.native_hook_status_profile_rejected_summary)

        NativeHookStatusKind.NoResponse -> stringResource(R.string.native_hook_status_no_response_summary)
        NativeHookStatusKind.LsPosedUnavailable ->
            stringResource(R.string.native_hook_status_lsposed_unavailable_summary)
    }
    val mode: String? = if (state.kind == NativeHookStatusKind.Ready) {
        when {
            state.legacyMode -> "LSPOSED"
            state.profileDynamic -> stringResource(R.string.native_hook_status_mode_runtime_profile)
            else -> stringResource(R.string.native_hook_status_mode_builtin_profile)
        }
    } else {
        null
    }
    val cardColor = when {
        ready && MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.secondaryContainer
        ready && isSystemInDarkTheme() -> Color(0xFF1A3825)
        ready -> Color(0xFFDFFAE4)
        warning && isSystemInDarkTheme() -> Color(0xFF3D3215)
        warning -> Color(0xFFFFF3CD)
        MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.secondaryContainer
        isSystemInDarkTheme() -> Color(0xFF3A1E22)
        else -> Color(0xFFFFE4E1)
    }
    val iconTint = when {
        ready && MiuixTheme.isDynamicColor ->
            MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)

        ready -> Color(0xFF36D167)
        warning && isSystemInDarkTheme() -> Color(0xFFFFC107)
        warning -> Color(0xFFFFB300)
        MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
        isSystemInDarkTheme() -> Color(0xFFFF8A80)
        else -> Color(0xFFD32F2F)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(color = cardColor),
            onClick = onRefresh,
            showIndication = true,
            pressFeedbackType = PressFeedbackType.Tilt,
        ) {
            Box {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(27.dp, 31.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Icon(
                        modifier = Modifier.size(110.dp),
                        imageVector = when {
                            ready -> Icons.Rounded.CheckCircleOutline
                            warning -> Icons.Rounded.WarningAmber
                            else -> Icons.Rounded.ErrorOutline
                        },
                        tint = iconTint,
                        contentDescription = null,
                    )
                }
                if (mode != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp, 10.dp),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        Text(
                            text = mode,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp, 14.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    Column {
                        Text(
                            text = title,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = summary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

private data class SettingsStatusCardMessage(
    val text: String,
    val severity: SettingsCardSeverity,
)

private enum class SettingsCardSeverity {
    Info,
    Error,
}

@Composable
@SuppressLint("ApplySharedPref")
private fun PredictiveBackSettingsScreen(
    service: XposedService?,
    serviceStateObserved: Boolean,
    nativeHookStatus: NativeHookStatusUiState,
    onRefreshNativeHookStatus: () -> Unit,
    onClose: () -> Unit,
    onOpenGestureTriggerSettings: () -> Unit,
    onOpenAppList: () -> Unit,
    onOpenContextualSearchSettings: () -> Unit,
) {
    val configurationErrorMessage = stringResource(R.string.predictive_back_config_error)
    val saveErrorMessage = stringResource(R.string.predictive_back_save_error)
    val serviceLoadingMessage = stringResource(R.string.predictive_back_service_loading)
    val serviceUnavailableMessage =
        stringResource(R.string.predictive_back_service_unavailable)
    val scope = rememberCoroutineScope()
    var preferences by remember { mutableStateOf<SharedPreferences?>(null) }
    var configurationLoading by remember { mutableStateOf(true) }
    var configurationError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var hyperOsIndicator by remember { mutableStateOf(false) }
    var confirmedHyperOsIndicator by remember { mutableStateOf(false) }
    var hyperOsIndicatorBlur by remember {
        mutableStateOf(PredictiveBackPreferences.DEFAULT_HYPEROS_INDICATOR_BLUR)
    }
    var confirmedHyperOsIndicatorBlur by remember {
        mutableStateOf(PredictiveBackPreferences.DEFAULT_HYPEROS_INDICATOR_BLUR)
    }
    var gestureHapticEffect by remember {
        mutableIntStateOf(PredictiveBackPreferences.GESTURE_HAPTIC_AOSP)
    }
    var confirmedGestureHapticEffect by remember {
        mutableIntStateOf(PredictiveBackPreferences.GESTURE_HAPTIC_AOSP)
    }
    var hyperOsHapticsEnhanced by remember { mutableStateOf(false) }
    var confirmedHyperOsHapticsEnhanced by remember { mutableStateOf(false) }
    var hyperOsSlideAnimation by remember { mutableStateOf(false) }
    var confirmedHyperOsSlideAnimation by remember { mutableStateOf(false) }
    var oneUiCrossTaskAnimation by remember { mutableStateOf(false) }
    var confirmedOneUiCrossTaskAnimation by remember { mutableStateOf(false) }
    var returnHomeIconPreview by remember {
        mutableStateOf(PredictiveBackPreferences.DEFAULT_RETURN_HOME_ICON_PREVIEW)
    }
    var confirmedReturnHomeIconPreview by remember {
        mutableStateOf(PredictiveBackPreferences.DEFAULT_RETURN_HOME_ICON_PREVIEW)
    }
    var javaHomeLauncher by remember {
        mutableStateOf(PredictiveBackPreferences.DEFAULT_JAVA_HOME_LAUNCHER)
    }
    var confirmedJavaHomeLauncher by remember {
        mutableStateOf(PredictiveBackPreferences.DEFAULT_JAVA_HOME_LAUNCHER)
    }
    var moduleLogging by remember { mutableStateOf(true) }
    var confirmedModuleLogging by remember { mutableStateOf(true) }
    var fullscreenDirectBack by remember {
        mutableStateOf(PredictiveBackPreferences.DEFAULT_FULLSCREEN_DIRECT_BACK)
    }
    var confirmedFullscreenDirectBack by remember {
        mutableStateOf(PredictiveBackPreferences.DEFAULT_FULLSCREEN_DIRECT_BACK)
    }
    var restoreStatusBarFollow by remember {
        mutableStateOf(PredictiveBackPreferences.DEFAULT_RESTORE_STATUS_BAR_FOLLOW)
    }
    var confirmedRestoreStatusBarFollow by remember {
        mutableStateOf(PredictiveBackPreferences.DEFAULT_RESTORE_STATUS_BAR_FOLLOW)
    }
    val writeMutex = remember(preferences) { Mutex() }
    val lazyListState = rememberLazyListState()
    val isContentScrolled by remember(lazyListState) {
        derivedStateOf { lazyListState.canScrollBackward }
    }
    val scrollBehavior = MiuixScrollBehavior()
    val layoutDirection = LocalLayoutDirection.current
    val horizontalSafeInsets = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Horizontal)
        .asPaddingValues()
    val topBarBackdrop = rememberMiuixBlurBackdrop()

    LaunchedEffect(service, serviceStateObserved, configurationErrorMessage) {
        preferences = null
        configurationError = null
        saveError = null
        hyperOsIndicator = false
        confirmedHyperOsIndicator = false
        hyperOsIndicatorBlur = PredictiveBackPreferences.DEFAULT_HYPEROS_INDICATOR_BLUR
        confirmedHyperOsIndicatorBlur = PredictiveBackPreferences.DEFAULT_HYPEROS_INDICATOR_BLUR
        gestureHapticEffect = PredictiveBackPreferences.GESTURE_HAPTIC_AOSP
        confirmedGestureHapticEffect = PredictiveBackPreferences.GESTURE_HAPTIC_AOSP
        hyperOsHapticsEnhanced = false
        confirmedHyperOsHapticsEnhanced = false
        hyperOsSlideAnimation = false
        confirmedHyperOsSlideAnimation = false
        oneUiCrossTaskAnimation = false
        confirmedOneUiCrossTaskAnimation = false
        returnHomeIconPreview = PredictiveBackPreferences.DEFAULT_RETURN_HOME_ICON_PREVIEW
        confirmedReturnHomeIconPreview =
            PredictiveBackPreferences.DEFAULT_RETURN_HOME_ICON_PREVIEW
        javaHomeLauncher = PredictiveBackPreferences.DEFAULT_JAVA_HOME_LAUNCHER
        confirmedJavaHomeLauncher = PredictiveBackPreferences.DEFAULT_JAVA_HOME_LAUNCHER
        moduleLogging = PredictiveBackPreferences.DEFAULT_MODULE_LOGGING
        confirmedModuleLogging = PredictiveBackPreferences.DEFAULT_MODULE_LOGGING
        fullscreenDirectBack = PredictiveBackPreferences.DEFAULT_FULLSCREEN_DIRECT_BACK
        confirmedFullscreenDirectBack = PredictiveBackPreferences.DEFAULT_FULLSCREEN_DIRECT_BACK
        restoreStatusBarFollow = PredictiveBackPreferences.DEFAULT_RESTORE_STATUS_BAR_FOLLOW
        confirmedRestoreStatusBarFollow =
            PredictiveBackPreferences.DEFAULT_RESTORE_STATUS_BAR_FOLLOW
        if (!serviceStateObserved) {
            configurationLoading = true
            return@LaunchedEffect
        }
        if (service == null) {
            configurationLoading = false
            return@LaunchedEffect
        }
        configurationLoading = true
        try {
            val loaded = withContext(Dispatchers.IO) {
                val remotePreferences =
                    service.getRemotePreferences(PredictiveBackPreferences.GROUP)
                val storedHyperOsHaptics = remotePreferences.getBoolean(
                    PredictiveBackPreferences.KEY_HYPEROS_HAPTICS,
                    PredictiveBackPreferences.DEFAULT_HYPEROS_HAPTICS,
                )
                val legacyAospHaptics = remotePreferences.getBoolean(
                    PredictiveBackPreferences.LEGACY_KEY_AOSP_HYPEROS_HAPTICS,
                    false,
                )
                val unifiedHyperOsHaptics = if (!legacyAospHaptics) {
                    storedHyperOsHaptics
                } else {
                    try {
                        var migrated = false
                        remotePreferences.edit(commit = true) {
                            putBoolean(PredictiveBackPreferences.KEY_HYPEROS_HAPTICS, true)
                            remove(PredictiveBackPreferences.LEGACY_KEY_AOSP_HYPEROS_HAPTICS)
                            migrated = commit()
                        }
                        migrated || storedHyperOsHaptics
                    } catch (_: Throwable) {
                        storedHyperOsHaptics
                    }
                }
                val hapticEffect =
                    if (remotePreferences.contains(PredictiveBackPreferences.KEY_GESTURE_HAPTIC_EFFECT)) {
                        PredictiveBackPreferences.readGestureHapticEffect(remotePreferences)
                    } else if (unifiedHyperOsHaptics) {
                        PredictiveBackPreferences.GESTURE_HAPTIC_HYPEROS
                    } else {
                        PredictiveBackPreferences.GESTURE_HAPTIC_AOSP
                    }
                val flags = booleanArrayOf(
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_HYPEROS_INDICATOR,
                        PredictiveBackPreferences.DEFAULT_HYPEROS_INDICATOR,
                    ),
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_HYPEROS_HAPTICS_ENHANCED,
                        PredictiveBackPreferences.DEFAULT_HYPEROS_HAPTICS_ENHANCED,
                    ),
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_HYPEROS_SLIDE_ANIMATION,
                        PredictiveBackPreferences.DEFAULT_HYPEROS_SLIDE_ANIMATION,
                    ),
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_ONEUI_CROSS_TASK_ANIMATION,
                        PredictiveBackPreferences.DEFAULT_ONEUI_CROSS_TASK_ANIMATION,
                    ),
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_MODULE_LOGGING,
                        PredictiveBackPreferences.DEFAULT_MODULE_LOGGING,
                    ),
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_FULLSCREEN_DIRECT_BACK,
                        PredictiveBackPreferences.DEFAULT_FULLSCREEN_DIRECT_BACK,
                    ),
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_HYPEROS_INDICATOR_BLUR,
                        PredictiveBackPreferences.DEFAULT_HYPEROS_INDICATOR_BLUR,
                    ),
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_RESTORE_STATUS_BAR_FOLLOW,
                        PredictiveBackPreferences.DEFAULT_RESTORE_STATUS_BAR_FOLLOW,
                    ),
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_RETURN_HOME_ICON_PREVIEW,
                        PredictiveBackPreferences.DEFAULT_RETURN_HOME_ICON_PREVIEW,
                    ),
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_JAVA_HOME_LAUNCHER,
                        PredictiveBackPreferences.DEFAULT_JAVA_HOME_LAUNCHER,
                    ),
                )
                Triple(remotePreferences, flags, hapticEffect)
            }
            preferences = loaded.first
            hyperOsIndicator = loaded.second[0]
            confirmedHyperOsIndicator = loaded.second[0]
            gestureHapticEffect = loaded.third
            confirmedGestureHapticEffect = loaded.third
            hyperOsHapticsEnhanced = loaded.second[1]
            confirmedHyperOsHapticsEnhanced = loaded.second[1]
            hyperOsSlideAnimation = loaded.second[2]
            confirmedHyperOsSlideAnimation = loaded.second[2]
            oneUiCrossTaskAnimation = loaded.second[3]
            confirmedOneUiCrossTaskAnimation = loaded.second[3]
            moduleLogging = loaded.second[4]
            confirmedModuleLogging = loaded.second[4]
            fullscreenDirectBack = loaded.second[5]
            confirmedFullscreenDirectBack = loaded.second[5]
            hyperOsIndicatorBlur = loaded.second[6]
            confirmedHyperOsIndicatorBlur = loaded.second[6]
            restoreStatusBarFollow = loaded.second[7]
            confirmedRestoreStatusBarFollow = loaded.second[7]
            returnHomeIconPreview = loaded.second[8]
            confirmedReturnHomeIconPreview = loaded.second[8]
            javaHomeLauncher = loaded.second[9]
            confirmedJavaHomeLauncher = loaded.second[9]
        } catch (_: Throwable) {
            configurationError = configurationErrorMessage
        } finally {
            configurationLoading = false
        }
    }

    val persistBooleanPreference: (
        String,
        Boolean,
        (Boolean) -> Unit,
        () -> Boolean,
        (Boolean) -> Unit,
    ) -> Unit = { key, requestedEnabled, setLocal, getConfirmed, setConfirmed ->
        val activePreferences = preferences
        if (activePreferences != null) {
            setLocal(requestedEnabled)
            saveError = null
            scope.launch {
                val saved = writeMutex.withLock {
                    val fallbackEnabled = getConfirmed()
                    val commitSucceeded = withContext(Dispatchers.IO) {
                        var succeeded = false
                        try {
                            activePreferences.edit(commit = true) {
                                putBoolean(key, requestedEnabled)
                                succeeded = commit()
                            }
                        } catch (_: Throwable) {
                            succeeded = false
                        }
                        if (!succeeded) {
                            try {
                                activePreferences.edit(commit = true) {
                                    putBoolean(key, fallbackEnabled)
                                    commit()
                                }
                            } catch (_: Throwable) {
                                // Restore the RemotePreferences cache where possible.
                            }
                        }
                        succeeded
                    }
                    if (preferences === activePreferences && commitSucceeded) {
                        setConfirmed(requestedEnabled)
                    }
                    commitSucceeded
                }
                if (preferences === activePreferences && !saved) {
                    setLocal(getConfirmed())
                    saveError = saveErrorMessage
                }
            }
        }
    }
    val persistHyperOsIndicator: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_HYPEROS_INDICATOR,
            requestedEnabled,
            { hyperOsIndicator = it },
            { confirmedHyperOsIndicator },
            { confirmedHyperOsIndicator = it },
        )
    }
    val persistGestureHapticEffect: (Int) -> Unit = { requestedEffect ->
        val activePreferences = preferences
        if (activePreferences != null) {
            gestureHapticEffect = requestedEffect
            saveError = null
            scope.launch {
                val saved = writeMutex.withLock {
                    val fallbackEffect = confirmedGestureHapticEffect
                    val commitSucceeded = withContext(Dispatchers.IO) {
                        var succeeded = false
                        try {
                            activePreferences.edit(commit = true) {
                                putInt(
                                    PredictiveBackPreferences.KEY_GESTURE_HAPTIC_EFFECT,
                                    requestedEffect
                                )
                                succeeded = commit()
                            }
                        } catch (_: Throwable) {
                            succeeded = false
                        }
                        if (!succeeded) {
                            try {
                                activePreferences.edit(commit = true) {
                                    putInt(
                                        PredictiveBackPreferences.KEY_GESTURE_HAPTIC_EFFECT,
                                        fallbackEffect
                                    )
                                    commit()
                                }
                            } catch (_: Throwable) {
                                // Restore the RemotePreferences cache where possible.
                            }
                        }
                        succeeded
                    }
                    if (preferences === activePreferences && commitSucceeded) {
                        confirmedGestureHapticEffect = requestedEffect
                    }
                    commitSucceeded
                }
                if (preferences === activePreferences && !saved) {
                    gestureHapticEffect = confirmedGestureHapticEffect
                    saveError = saveErrorMessage
                }
            }
        }
    }
    val persistHyperOsIndicatorBlur: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_HYPEROS_INDICATOR_BLUR,
            requestedEnabled,
            { hyperOsIndicatorBlur = it },
            { confirmedHyperOsIndicatorBlur },
            { confirmedHyperOsIndicatorBlur = it },
        )
    }
    val persistHyperOsHapticsEnhanced: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_HYPEROS_HAPTICS_ENHANCED,
            requestedEnabled,
            { hyperOsHapticsEnhanced = it },
            { confirmedHyperOsHapticsEnhanced },
            { confirmedHyperOsHapticsEnhanced = it },
        )
    }
    val persistHyperOsSlideAnimation: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_HYPEROS_SLIDE_ANIMATION,
            requestedEnabled,
            { hyperOsSlideAnimation = it },
            { confirmedHyperOsSlideAnimation },
            { confirmedHyperOsSlideAnimation = it },
        )
    }
    val persistReturnHomeIconPreview: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_RETURN_HOME_ICON_PREVIEW,
            requestedEnabled,
            { returnHomeIconPreview = it },
            { confirmedReturnHomeIconPreview },
            { confirmedReturnHomeIconPreview = it },
        )
    }
    val persistJavaHomeLauncher: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_JAVA_HOME_LAUNCHER,
            requestedEnabled,
            { javaHomeLauncher = it },
            { confirmedJavaHomeLauncher },
            { confirmedJavaHomeLauncher = it },
        )
    }
    val persistOneUiCrossTaskAnimation: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_ONEUI_CROSS_TASK_ANIMATION,
            requestedEnabled,
            { oneUiCrossTaskAnimation = it },
            { confirmedOneUiCrossTaskAnimation },
            { confirmedOneUiCrossTaskAnimation = it },
        )
    }
    val persistModuleLogging: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_MODULE_LOGGING,
            requestedEnabled,
            { moduleLogging = it },
            { confirmedModuleLogging },
            { confirmedModuleLogging = it },
        )
    }
    val persistFullscreenDirectBack: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_FULLSCREEN_DIRECT_BACK,
            requestedEnabled,
            { fullscreenDirectBack = it },
            { confirmedFullscreenDirectBack },
            { confirmedFullscreenDirectBack = it },
        )
    }
    val persistRestoreStatusBarFollow: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_RESTORE_STATUS_BAR_FOLLOW,
            requestedEnabled,
            { restoreStatusBarFollow = it },
            { confirmedRestoreStatusBarFollow },
            { confirmedRestoreStatusBarFollow = it },
        )
    }
    val statusMessage = when {
        configurationLoading -> SettingsStatusCardMessage(
            text = serviceLoadingMessage,
            severity = SettingsCardSeverity.Info,
        )

        configurationError != null -> SettingsStatusCardMessage(
            text = configurationError.orEmpty(),
            severity = SettingsCardSeverity.Error,
        )

        saveError != null -> SettingsStatusCardMessage(
            text = saveError.orEmpty(),
            severity = SettingsCardSeverity.Error,
        )

        serviceStateObserved && service == null -> SettingsStatusCardMessage(
            text = serviceUnavailableMessage,
            severity = SettingsCardSeverity.Error,
        )

        else -> null
    }
    val configurationEnabled = preferences != null

    Scaffold(
        topBar = {
            GlassTopAppBar(
                backdrop = topBarBackdrop,
                isContentScrolled = isContentScrolled,
                progressiveBlur = true,
                scrollBehavior = scrollBehavior,
                title = stringResource(R.string.predictive_back_title),
                subtitle = "v${BuildConfig.VERSION_NAME}",
                navigationIcon = {
                    GlassIconButton(onClick = onClose) {
                        Icon(
                            imageVector = MiuixIcons.Glass.Close,
                            modifier = Modifier.size(24.dp),
                            tint = MiuixTheme.colorScheme.onSurface,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(topBarBackdrop),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(paddingValues)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                state = lazyListState,
                contentPadding = PaddingValues(
                    start = horizontalSafeInsets.calculateLeftPadding(layoutDirection),
                    top = paddingValues.calculateTopPadding() + 8.dp,
                    end = horizontalSafeInsets.calculateRightPadding(layoutDirection),
                    bottom = paddingValues.calculateBottomPadding(),
                ),
                overscrollEffect = null,
            ) {
                item(key = "native_hook_runtime_status") {
                    NativeHookRuntimeStatusCard(
                        state = if (serviceStateObserved && service == null) {
                            NativeHookStatusUiState(NativeHookStatusKind.LsPosedUnavailable)
                        } else {
                            nativeHookStatus
                        },
                        onRefresh = onRefreshNativeHookStatus,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 8.dp),
                    )
                }
                item(key = "side_gesture_title") {
                    SmallTitle(text = stringResource(R.string.side_gesture_group_title))
                }
                item(key = "side_gesture") {
                    SideGestureGroupCard(
                        hyperOsIndicator = hyperOsIndicator,
                        hyperOsIndicatorBlur = hyperOsIndicatorBlur,
                        gestureHapticEffect = gestureHapticEffect,
                        hyperOsHapticsEnhanced = hyperOsHapticsEnhanced,
                        hyperOsSlideAnimation = hyperOsSlideAnimation,
                        oneUiCrossTaskAnimation = oneUiCrossTaskAnimation,
                        fullscreenDirectBack = fullscreenDirectBack,
                        returnHomeIconPreview = returnHomeIconPreview,
                        javaHomeLauncher = javaHomeLauncher,
                        configurationEnabled = configurationEnabled,
                        onHyperOsIndicatorToggle = persistHyperOsIndicator,
                        onHyperOsIndicatorBlurToggle = persistHyperOsIndicatorBlur,
                        onGestureHapticEffectChange = persistGestureHapticEffect,
                        onHyperOsHapticsEnhancedToggle = persistHyperOsHapticsEnhanced,
                        onHyperOsSlideAnimationToggle = persistHyperOsSlideAnimation,
                        onOneUiCrossTaskAnimationToggle = persistOneUiCrossTaskAnimation,
                        onFullscreenDirectBackToggle = persistFullscreenDirectBack,
                        onReturnHomeIconPreviewToggle = persistReturnHomeIconPreview,
                        onJavaHomeLauncherToggle = persistJavaHomeLauncher,
                        onOpenGestureTriggerSettings = onOpenGestureTriggerSettings,
                        backdrop = topBarBackdrop,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 8.dp)
                            .fillMaxWidth(),
                    )
                }
                item(key = "gesture_handle_title") {
                    SmallTitle(text = stringResource(R.string.gesture_handle_group_title))
                }
                item(key = "contextual_search") {
                    ContextualSearchNavigationCard(
                        onClick = onOpenContextualSearchSettings,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 8.dp),
                    )
                }
                if (Build.VERSION.SDK_INT == 37) {
                    item(key = "status_bar_title") {
                        SmallTitle(text = stringResource(R.string.status_bar_group_title))
                    }
                    item(key = "status_bar") {
                        StatusBarGroupCard(
                            enabled = restoreStatusBarFollow,
                            configurationEnabled = configurationEnabled,
                            onToggle = persistRestoreStatusBarFollow,
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                        )
                    }
                }
                item(key = "app_list_navigation") {
                    AppListNavigationCard(
                        onClick = onOpenAppList,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 8.dp),
                    )
                }
                item(key = "module_logging") {
                    ModuleLoggingCard(
                        moduleLogging = moduleLogging,
                        configurationEnabled = configurationEnabled,
                        onModuleLoggingToggle = persistModuleLogging,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 8.dp),
                    )
                }
                if (statusMessage != null) {
                    item(key = "configuration_status") {
                        StatusCard(
                            message = statusMessage.text,
                            severity = statusMessage.severity,
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    message: String,
    severity: SettingsCardSeverity,
    modifier: Modifier = Modifier,
) {
    val accentColor = cardAccentColor(severity)
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
        colors = CardDefaults.defaultColors(
            color = accentColor.copy(alpha = 0.2f),
            contentColor = accentColor,
        ),
    ) {
        Text(
            text = message,
            style = MiuixTheme.textStyles.body2,
        )
    }
}

@Composable
private fun cardAccentColor(severity: SettingsCardSeverity): Color = when (severity) {
    SettingsCardSeverity.Info -> MiuixTheme.colorScheme.primary
    SettingsCardSeverity.Error -> MiuixTheme.colorScheme.error
}

@Composable
private fun SideGestureGroupCard(
    hyperOsIndicator: Boolean,
    hyperOsIndicatorBlur: Boolean,
    gestureHapticEffect: Int,
    hyperOsHapticsEnhanced: Boolean,
    hyperOsSlideAnimation: Boolean,
    oneUiCrossTaskAnimation: Boolean,
    fullscreenDirectBack: Boolean,
    returnHomeIconPreview: Boolean,
    javaHomeLauncher: Boolean,
    configurationEnabled: Boolean,
    onHyperOsIndicatorToggle: (Boolean) -> Unit,
    onHyperOsIndicatorBlurToggle: (Boolean) -> Unit,
    onGestureHapticEffectChange: (Int) -> Unit,
    onHyperOsHapticsEnhancedToggle: (Boolean) -> Unit,
    onHyperOsSlideAnimationToggle: (Boolean) -> Unit,
    onOneUiCrossTaskAnimationToggle: (Boolean) -> Unit,
    onFullscreenDirectBackToggle: (Boolean) -> Unit,
    onReturnHomeIconPreviewToggle: (Boolean) -> Unit,
    onJavaHomeLauncherToggle: (Boolean) -> Unit,
    onOpenGestureTriggerSettings: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val hyperOsHaptics = gestureHapticEffect == PredictiveBackPreferences.GESTURE_HAPTIC_HYPEROS
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(0.dp),
    ) {
        GlassDropdownPreference(
            backdrop = backdrop,
            items = listOf(
                stringResource(R.string.back_indicator_style_aosp),
                stringResource(R.string.back_indicator_style_hyperos),
            ),
            selectedIndex = if (hyperOsIndicator) 1 else 0,
            title = stringResource(R.string.back_indicator_style_title),
            summary = stringResource(
                if (hyperOsIndicator) {
                    R.string.back_indicator_style_hyperos_summary
                } else {
                    R.string.back_indicator_style_aosp_summary
                },
            ),
            enabled = configurationEnabled,
            onSelectedIndexChange = { selectedIndex ->
                onHyperOsIndicatorToggle(selectedIndex == 1)
            },
        )
        AnimatedVisibility(visible = hyperOsIndicator) {
            SwitchPreference(
                title = stringResource(R.string.hyperos_indicator_blur_title),
                summary = stringResource(R.string.hyperos_indicator_blur_summary),
                checked = hyperOsIndicatorBlur,
                enabled = configurationEnabled && hyperOsIndicator,
                onCheckedChange = onHyperOsIndicatorBlurToggle,
            )
        }
        GlassDropdownPreference(
            backdrop = backdrop,
            items = listOf(
                stringResource(R.string.haptic_feedback_effect_aosp),
                stringResource(R.string.haptic_feedback_effect_hyperos),
                stringResource(R.string.haptic_feedback_effect_disabled),
            ),
            selectedIndex = gestureHapticEffect,
            title = stringResource(R.string.haptic_feedback_effect_title),
            summary = stringResource(
                when (gestureHapticEffect) {
                    PredictiveBackPreferences.GESTURE_HAPTIC_HYPEROS -> R.string.haptic_feedback_effect_hyperos_summary
                    PredictiveBackPreferences.GESTURE_HAPTIC_DISABLED -> R.string.haptic_feedback_effect_disabled_summary
                    else -> R.string.haptic_feedback_effect_aosp_summary
                },
            ),
            enabled = configurationEnabled,
            onSelectedIndexChange = onGestureHapticEffectChange,
        )
        SwitchPreference(
            title = stringResource(R.string.hyperos_haptics_enhanced_title),
            summary = stringResource(
                if (configurationEnabled && !hyperOsHaptics) {
                    R.string.hyperos_haptics_enhanced_disabled_summary
                } else {
                    R.string.hyperos_haptics_enhanced_summary
                },
            ),
            checked = hyperOsHapticsEnhanced,
            enabled = configurationEnabled && hyperOsHaptics,
            onCheckedChange = onHyperOsHapticsEnhancedToggle,
        )
        SwitchPreference(
            title = stringResource(R.string.hyperos_slide_animation_title),
            summary = stringResource(R.string.hyperos_slide_animation_summary),
            checked = hyperOsSlideAnimation,
            enabled = configurationEnabled,
            onCheckedChange = onHyperOsSlideAnimationToggle,
        )
        SwitchPreference(
            title = stringResource(R.string.oneui_cross_task_animation_title),
            summary = stringResource(R.string.oneui_cross_task_animation_summary),
            checked = oneUiCrossTaskAnimation,
            enabled = configurationEnabled,
            onCheckedChange = onOneUiCrossTaskAnimationToggle,
        )
        ArrowPreference(
            title = stringResource(R.string.gesture_trigger_entry_title),
            summary = stringResource(R.string.gesture_trigger_entry_summary),
            onClick = onOpenGestureTriggerSettings,
        )
        GlassDropdownPreference(
            backdrop = backdrop,
            items = listOf(
                stringResource(R.string.fullscreen_back_show_bars),
                stringResource(R.string.fullscreen_back_direct),
            ),
            selectedIndex = if (fullscreenDirectBack) 1 else 0,
            title = stringResource(R.string.fullscreen_back_behavior_title),
            summary = stringResource(
                if (fullscreenDirectBack) {
                    R.string.fullscreen_back_direct_summary
                } else {
                    R.string.fullscreen_back_show_bars_summary
                },
            ),
            enabled = configurationEnabled,
            onSelectedIndexChange = { selectedIndex ->
                onFullscreenDirectBackToggle(selectedIndex == 1)
            },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            val context = LocalContext.current
            // A selection change is applied only after its confirmation dialog.
            var pendingJavaLauncher by remember { mutableStateOf<Boolean?>(null) }
            val javaLauncherEmbedded = remember {
                File(context.applicationInfo.nativeLibraryDir, JAVA_LAUNCHER_LIBRARY).isFile
            }
            // The switch restarts MiuiHome a few seconds after the selection changes.
            val runningJavaLauncher by produceState<Boolean?>(null, javaHomeLauncher) {
                repeat(10) {
                    value = withContext(Dispatchers.IO) { isRunningJavaLauncher(context) }
                    delay(1000)
                }
            }
            if (javaLauncherEmbedded) GlassDropdownPreference(
                backdrop = backdrop,
                items = listOf(
                    stringResource(R.string.launcher_implementation_native),
                    stringResource(R.string.launcher_implementation_java),
                ),
                selectedIndex = if (javaHomeLauncher) 1 else 0,
                title = stringResource(R.string.launcher_implementation_title),
                summary = stringResource(
                    if (javaHomeLauncher) {
                        R.string.launcher_implementation_java_summary
                    } else {
                        R.string.launcher_implementation_native_summary
                    },
                    when (runningJavaLauncher) {
                        true -> stringResource(R.string.launcher_implementation_java)
                        false -> stringResource(R.string.launcher_implementation_native)
                        null -> "…"
                    },
                ),
                enabled = configurationEnabled,
                onSelectedIndexChange = { selectedIndex ->
                    val wantJava = selectedIndex == 1
                    if (wantJava != javaHomeLauncher) pendingJavaLauncher = wantJava
                },
            )
            GlassDialog(
                show = pendingJavaLauncher != null,
                backdrop = backdrop,
                title = stringResource(
                    if (pendingJavaLauncher == true) {
                        R.string.launcher_switch_java_title
                    } else {
                        R.string.launcher_switch_native_title
                    },
                ),
                summary = stringResource(
                    if (pendingJavaLauncher == true) {
                        R.string.launcher_switch_java_body
                    } else {
                        R.string.launcher_switch_native_body
                    },
                ),
                onDismissRequest = { pendingJavaLauncher = null },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassDialogButton(
                        onClick = { pendingJavaLauncher = null },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            style = MiuixTheme.textStyles.button,
                        )
                    }
                    GlassDialogButton(
                        onClick = {
                            val target = pendingJavaLauncher
                            pendingJavaLauncher = null
                            if (target != null) onJavaHomeLauncherToggle(target)
                        },
                        modifier = Modifier.weight(1f),
                        role = GlassDialogButtonRole.Primary,
                    ) {
                        Text(
                            text = stringResource(R.string.launcher_switch_confirm),
                            style = MiuixTheme.textStyles.button,
                        )
                    }
                }
            }
            // The Android 17 preview patch targets the native launcher only.
            if (!javaHomeLauncher) {
                SwitchPreference(
                    title = stringResource(R.string.return_home_icon_preview_title),
                    summary = stringResource(R.string.return_home_icon_preview_summary),
                    checked = returnHomeIconPreview,
                    enabled = configurationEnabled,
                    onCheckedChange = onReturnHomeIconPreviewToggle,
                )
            }
        }
    }
}

@Composable
private fun StatusBarGroupCard(
    enabled: Boolean,
    configurationEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        SwitchPreference(
            title = stringResource(R.string.restore_status_bar_follow_title),
            summary = stringResource(R.string.restore_status_bar_follow_summary),
            checked = enabled,
            enabled = configurationEnabled,
            onCheckedChange = onToggle,
        )
    }
}

@Composable
private fun AppListNavigationCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        ArrowPreference(
            title = stringResource(R.string.predictive_back_apps_entry_title),
            summary = stringResource(R.string.predictive_back_apps_entry_summary),
            onClick = onClick,
        )
    }
}

@Composable
private fun ModuleLoggingCard(
    moduleLogging: Boolean,
    configurationEnabled: Boolean,
    onModuleLoggingToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        SwitchPreference(
            title = stringResource(R.string.module_logging_title),
            summary = stringResource(R.string.module_logging_summary),
            checked = moduleLogging,
            enabled = configurationEnabled,
            onCheckedChange = onModuleLoggingToggle,
        )
    }
}

@Composable
private fun ContextualSearchNavigationCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        ArrowPreference(
            title = stringResource(R.string.contextual_search_entry_title),
            summary = stringResource(R.string.contextual_search_entry_summary),
            onClick = onClick,
        )
    }
}

private const val JAVA_LAUNCHER_LIBRARY = "libjavahome_launcher.so"

/** A dex MiuiHome is the Java launcher; the Android 17 native launcher ships no code. */
private fun isRunningJavaLauncher(context: Context): Boolean? = try {
    val info = context.packageManager.getApplicationInfo("com.miui.home", 0)
    (info.flags and android.content.pm.ApplicationInfo.FLAG_HAS_CODE) != 0
} catch (_: Throwable) {
    null
}
