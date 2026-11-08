// SPDX-License-Identifier: Apache-2.0
package dev.codex.miuibackgesturehook.activity

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.codex.miuibackgesturehook.ModuleApplication
import dev.codex.miuibackgesturehook.PredictiveBackPreferences
import dev.codex.miuibackgesturehook.R
import dev.codex.miuibackgesturehook.util.SettingsProgressiveAppBarSurface
import dev.codex.miuibackgesturehook.util.rememberMiuixBlurBackdrop
import io.github.libxposed.service.XposedService
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

class ContextualSearchSettingsActivity :
    ComponentActivity(),
    ModuleApplication.ServiceStateListener {
    private var xposedService: XposedService? by mutableStateOf(null)
    private var serviceStateObserved by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MiuixTheme(colors = colors) {
                ContextualSearchSettingsScreen(
                    service = xposedService,
                    serviceStateObserved = serviceStateObserved,
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ModuleApplication.addServiceStateListener(this, notifyImmediately = true)
    }

    override fun onStop() {
        ModuleApplication.removeServiceStateListener(this)
        super.onStop()
    }

    override fun onServiceStateChanged(service: XposedService?) {
        xposedService = service
        serviceStateObserved = true
    }
}

private data class ContextualSearchValues(
    val preferences: SharedPreferences,
    val longPressEnabled: Boolean,
    val liveTranslateEnabled: Boolean,
    val lensContextualSearchboxEnabled: Boolean,
    val hapticsEnabled: Boolean,
)

private enum class ContextualSearchStatusSeverity {
    Info,
    Error,
}

private data class ContextualSearchStatus(
    val text: String,
    val severity: ContextualSearchStatusSeverity,
)

private enum class GoogleAppScopeResult {
    AlreadyPresent,
    Approved,
    Failed,
}

private const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"

private suspend fun ensureGoogleAppScope(service: XposedService): GoogleAppScopeResult =
    withContext(Dispatchers.IO) {
        try {
            if (service.getScope().contains(GOOGLE_APP_PACKAGE)) {
                return@withContext GoogleAppScopeResult.AlreadyPresent
            }
            val approval = CompletableDeferred<Boolean>()
            fun complete(result: Boolean) {
                approval.complete(result)
            }
            try {
                service.requestScope(
                    listOf(GOOGLE_APP_PACKAGE),
                    object : XposedService.OnScopeEventListener {
                        override fun onScopeRequestApproved(approved: List<String>) {
                            complete(approved.contains(GOOGLE_APP_PACKAGE))
                        }

                        override fun onScopeRequestFailed(message: String) {
                            complete(false)
                        }
                    },
                )
            } catch (_: Throwable) {
                complete(false)
            }
            val approved = approval.await()
            if (approved && service.getScope().contains(GOOGLE_APP_PACKAGE)) {
                GoogleAppScopeResult.Approved
            } else {
                GoogleAppScopeResult.Failed
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            GoogleAppScopeResult.Failed
        }
    }

@Composable
@SuppressLint("ApplySharedPref")
private fun ContextualSearchSettingsScreen(
    service: XposedService?,
    serviceStateObserved: Boolean,
    onClose: () -> Unit,
) {
    val configurationErrorMessage = stringResource(R.string.predictive_back_config_error)
    val saveErrorMessage = stringResource(R.string.predictive_back_save_error)
    val serviceLoadingMessage = stringResource(R.string.predictive_back_service_loading)
    val serviceUnavailableMessage =
        stringResource(R.string.predictive_back_service_unavailable)
    val scopeRequestMessage =
        stringResource(R.string.contextual_search_live_translate_scope_request)
    val scopeApprovedMessage =
        stringResource(R.string.contextual_search_live_translate_scope_approved)
    val lensScopeApprovedMessage =
        stringResource(R.string.google_lens_contextual_searchbox_scope_approved)
    val scopeFailedMessage =
        stringResource(R.string.contextual_search_live_translate_scope_failed)
    val scope = rememberCoroutineScope()
    var preferences by remember { mutableStateOf<SharedPreferences?>(null) }
    var configurationLoading by remember { mutableStateOf(true) }
    var configurationError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var scopeRequestInFlight by remember { mutableStateOf(false) }
    var scopeStatus by remember { mutableStateOf<ContextualSearchStatus?>(null) }
    var scopeRequestJob by remember { mutableStateOf<Job?>(null) }
    val scopeRequestGeneration = remember { AtomicLong() }
    var longPressEnabled by remember {
        mutableStateOf(PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_LONG_PRESS)
    }
    var confirmedLongPressEnabled by remember {
        mutableStateOf(PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_LONG_PRESS)
    }
    var liveTranslateEnabled by remember {
        mutableStateOf(PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_LIVE_TRANSLATE)
    }
    var confirmedLiveTranslateEnabled by remember {
        mutableStateOf(PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_LIVE_TRANSLATE)
    }
    var lensContextualSearchboxEnabled by remember {
        mutableStateOf(PredictiveBackPreferences.DEFAULT_GOOGLE_LENS_CONTEXTUAL_SEARCHBOX)
    }
    var confirmedLensContextualSearchboxEnabled by remember {
        mutableStateOf(PredictiveBackPreferences.DEFAULT_GOOGLE_LENS_CONTEXTUAL_SEARCHBOX)
    }
    var hapticsEnabled by remember {
        mutableStateOf(PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_HAPTICS)
    }
    var confirmedHapticsEnabled by remember {
        mutableStateOf(PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_HAPTICS)
    }
    val writeMutex = remember(preferences) { Mutex() }
    val lazyListState = rememberLazyListState()
    val isContentScrolled by remember(lazyListState) {
        derivedStateOf { lazyListState.canScrollBackward }
    }
    val scrollBehavior = MiuixScrollBehavior()
    val horizontalSafeInsets = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Horizontal)
        .asPaddingValues()
    val topBarBackdrop = rememberMiuixBlurBackdrop()

    LaunchedEffect(service, serviceStateObserved, configurationErrorMessage) {
        scopeRequestJob?.cancel()
        scopeRequestJob = null
        scopeRequestGeneration.incrementAndGet()
        preferences = null
        configurationError = null
        saveError = null
        scopeRequestInFlight = false
        scopeStatus = null
        longPressEnabled = PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_LONG_PRESS
        confirmedLongPressEnabled = longPressEnabled
        liveTranslateEnabled = PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_LIVE_TRANSLATE
        confirmedLiveTranslateEnabled = liveTranslateEnabled
        lensContextualSearchboxEnabled =
            PredictiveBackPreferences.DEFAULT_GOOGLE_LENS_CONTEXTUAL_SEARCHBOX
        confirmedLensContextualSearchboxEnabled = lensContextualSearchboxEnabled
        hapticsEnabled = PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_HAPTICS
        confirmedHapticsEnabled = hapticsEnabled
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
                ContextualSearchValues(
                    preferences = remotePreferences,
                    longPressEnabled = remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS,
                        PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_LONG_PRESS,
                    ),
                    liveTranslateEnabled = remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_LIVE_TRANSLATE,
                        PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_LIVE_TRANSLATE,
                    ),
                    lensContextualSearchboxEnabled = remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_GOOGLE_LENS_CONTEXTUAL_SEARCHBOX,
                        PredictiveBackPreferences.DEFAULT_GOOGLE_LENS_CONTEXTUAL_SEARCHBOX,
                    ),
                    hapticsEnabled = remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_HAPTICS,
                        PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_HAPTICS,
                    ),
                )
            }
            preferences = loaded.preferences
            longPressEnabled = loaded.longPressEnabled
            confirmedLongPressEnabled = loaded.longPressEnabled
            liveTranslateEnabled = loaded.liveTranslateEnabled
            confirmedLiveTranslateEnabled = loaded.liveTranslateEnabled
            lensContextualSearchboxEnabled = loaded.lensContextualSearchboxEnabled
            confirmedLensContextualSearchboxEnabled = loaded.lensContextualSearchboxEnabled
            hapticsEnabled = loaded.hapticsEnabled
            confirmedHapticsEnabled = loaded.hapticsEnabled
        } catch (_: Throwable) {
            configurationError = configurationErrorMessage
        } finally {
            configurationLoading = false
        }
    }

    fun persistBooleanPreference(
        key: String,
        requestedEnabled: Boolean,
        setLocal: (Boolean) -> Unit,
        getConfirmed: () -> Boolean,
        setConfirmed: (Boolean) -> Unit,
    ) {
        val activePreferences = preferences ?: return
        setLocal(requestedEnabled)
        saveError = null
        scope.launch {
            val saved = writeMutex.withLock {
                val fallbackEnabled = getConfirmed()
                val commitSucceeded = withContext(Dispatchers.IO) {
                    val succeeded = try {
                        activePreferences.edit().putBoolean(key, requestedEnabled).commit()
                    } catch (_: Throwable) {
                        false
                    }
                    if (!succeeded) {
                        try {
                            activePreferences.edit().putBoolean(key, fallbackEnabled).commit()
                        } catch (_: Throwable) {
                            // Keep the last confirmed local value below.
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

    fun enableGoogleScopedFeature(
        preferenceKey: String,
        approvedMessage: String,
        setLocal: (Boolean) -> Unit,
        getConfirmed: () -> Boolean,
        setConfirmed: (Boolean) -> Unit,
    ) {
        val activeService = service ?: return
        val activePreferences = preferences ?: return
        val requestGeneration = scopeRequestGeneration.incrementAndGet()
        scopeRequestJob?.cancel()
        setLocal(getConfirmed())
        saveError = null
        scopeRequestInFlight = true
        scopeStatus = ContextualSearchStatus(
            scopeRequestMessage,
            ContextualSearchStatusSeverity.Info,
        )
        scopeRequestJob = scope.launch {
            val result = ensureGoogleAppScope(activeService)
            if (
                scopeRequestGeneration.get() != requestGeneration ||
                service !== activeService ||
                preferences !== activePreferences
            ) {
                return@launch
            }
            scopeRequestInFlight = false
            scopeRequestJob = null
            when (result) {
                GoogleAppScopeResult.AlreadyPresent -> {
                    scopeStatus = null
                    persistBooleanPreference(
                        preferenceKey,
                        true,
                        setLocal,
                        getConfirmed,
                        setConfirmed,
                    )
                }

                GoogleAppScopeResult.Approved -> {
                    scopeStatus = ContextualSearchStatus(
                        approvedMessage,
                        ContextualSearchStatusSeverity.Info,
                    )
                    persistBooleanPreference(
                        preferenceKey,
                        true,
                        setLocal,
                        getConfirmed,
                        setConfirmed,
                    )
                }

                GoogleAppScopeResult.Failed -> {
                    setLocal(getConfirmed())
                    scopeStatus = ContextualSearchStatus(
                        scopeFailedMessage,
                        ContextualSearchStatusSeverity.Error,
                    )
                }
            }
        }
    }

    val configurationEnabled = preferences != null && !configurationLoading
    val statusMessage = when {
        configurationLoading -> ContextualSearchStatus(
            serviceLoadingMessage,
            ContextualSearchStatusSeverity.Info,
        )
        configurationError != null -> ContextualSearchStatus(
            configurationError.orEmpty(),
            ContextualSearchStatusSeverity.Error,
        )
        saveError != null -> ContextualSearchStatus(
            saveError.orEmpty(),
            ContextualSearchStatusSeverity.Error,
        )
        scopeStatus != null -> scopeStatus
        serviceStateObserved && service == null -> ContextualSearchStatus(
            serviceUnavailableMessage,
            ContextualSearchStatusSeverity.Error,
        )
        else -> null
    }

    Scaffold(
        topBar = {
            SettingsProgressiveAppBarSurface(backdrop = topBarBackdrop) {
                GlassTopAppBar(
                    backdrop = topBarBackdrop,
                    isContentScrolled = isContentScrolled,
                    backgroundAlpha = { 0f },
                    scrollBehavior = scrollBehavior,
                    title = stringResource(R.string.contextual_search_title),
                    navigationIcon = {
                        val layoutDirection = LocalLayoutDirection.current
                        GlassIconButton(onClick = onClose) {
                            Icon(
                                imageVector = MiuixIcons.Glass.ChevronBackward,
                                modifier = Modifier
                                    .size(24.dp)
                                    .graphicsLayer {
                                        if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                    },
                                tint = MiuixTheme.colorScheme.onSurface,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                )
            }
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
                    start = horizontalSafeInsets.calculateLeftPadding(LocalLayoutDirection.current),
                    top = paddingValues.calculateTopPadding() + 8.dp,
                    end = horizontalSafeInsets.calculateRightPadding(LocalLayoutDirection.current),
                    bottom = paddingValues.calculateBottomPadding(),
                ),
                overscrollEffect = null,
            ) {
                item(key = "contextual_search_switches") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 8.dp),
                        insideMargin = PaddingValues(0.dp),
                    ) {
                        SwitchPreference(
                            title = stringResource(R.string.contextual_search_title),
                            summary = stringResource(R.string.contextual_search_summary),
                            checked = longPressEnabled,
                            enabled = configurationEnabled,
                            onCheckedChange = { requested ->
                                persistBooleanPreference(
                                    PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS,
                                    requested,
                                    { longPressEnabled = it },
                                    { confirmedLongPressEnabled },
                                    { confirmedLongPressEnabled = it },
                                )
                            },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.contextual_search_live_translate_title),
                            summary = stringResource(R.string.contextual_search_live_translate_summary),
                            checked = liveTranslateEnabled,
                            enabled = configurationEnabled && longPressEnabled && !scopeRequestInFlight,
                            onCheckedChange = { requested ->
                                if (requested) {
                                    enableGoogleScopedFeature(
                                        PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_LIVE_TRANSLATE,
                                        scopeApprovedMessage,
                                        { liveTranslateEnabled = it },
                                        { confirmedLiveTranslateEnabled },
                                        { confirmedLiveTranslateEnabled = it },
                                    )
                                } else {
                                    scopeStatus = null
                                    persistBooleanPreference(
                                        PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_LIVE_TRANSLATE,
                                        false,
                                        { liveTranslateEnabled = it },
                                        { confirmedLiveTranslateEnabled },
                                        { confirmedLiveTranslateEnabled = it },
                                    )
                                }
                            },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.google_lens_contextual_searchbox_title),
                            summary = stringResource(R.string.google_lens_contextual_searchbox_summary),
                            checked = lensContextualSearchboxEnabled,
                            enabled = configurationEnabled && longPressEnabled && !scopeRequestInFlight,
                            onCheckedChange = { requested ->
                                if (requested) {
                                    enableGoogleScopedFeature(
                                        PredictiveBackPreferences.KEY_GOOGLE_LENS_CONTEXTUAL_SEARCHBOX,
                                        lensScopeApprovedMessage,
                                        { lensContextualSearchboxEnabled = it },
                                        { confirmedLensContextualSearchboxEnabled },
                                        { confirmedLensContextualSearchboxEnabled = it },
                                    )
                                } else {
                                    scopeStatus = null
                                    persistBooleanPreference(
                                        PredictiveBackPreferences.KEY_GOOGLE_LENS_CONTEXTUAL_SEARCHBOX,
                                        false,
                                        { lensContextualSearchboxEnabled = it },
                                        { confirmedLensContextualSearchboxEnabled },
                                        { confirmedLensContextualSearchboxEnabled = it },
                                    )
                                }
                            },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.contextual_search_haptics_title),
                            summary = stringResource(R.string.contextual_search_haptics_summary),
                            checked = hapticsEnabled,
                            enabled = configurationEnabled && longPressEnabled,
                            onCheckedChange = { requested ->
                                persistBooleanPreference(
                                    PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_HAPTICS,
                                    requested,
                                    { hapticsEnabled = it },
                                    { confirmedHapticsEnabled },
                                    { confirmedHapticsEnabled = it },
                                )
                            },
                        )
                    }
                }
                if (statusMessage != null) {
                    item(key = "contextual_search_status") {
                        ContextualSearchStatusCard(
                            message = statusMessage.text,
                            severity = statusMessage.severity,
                            modifier = Modifier
                                .fillMaxWidth()
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
private fun ContextualSearchStatusCard(
    message: String,
    severity: ContextualSearchStatusSeverity,
    modifier: Modifier = Modifier,
) {
    val accent = when (severity) {
        ContextualSearchStatusSeverity.Info -> MiuixTheme.colorScheme.primary
        ContextualSearchStatusSeverity.Error -> MiuixTheme.colorScheme.error
    }
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
        colors = CardDefaults.defaultColors(
            color = accent.copy(alpha = 0.2f),
            contentColor = accent,
        ),
    ) {
        Text(
            text = message,
            style = MiuixTheme.textStyles.body2,
        )
    }
}
