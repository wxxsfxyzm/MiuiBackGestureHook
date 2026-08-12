// SPDX-License-Identifier: Apache-2.0
package dev.codex.miuibackgesturehook.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.codex.miuibackgesturehook.BuildConfig
import dev.codex.miuibackgesturehook.ModuleApplication
import dev.codex.miuibackgesturehook.PredictiveBackPreferences
import dev.codex.miuibackgesturehook.R
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import dev.codex.miuibackgesturehook.util.miuixBlurEffect
import dev.codex.miuibackgesturehook.util.rememberMiuixBlurBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

class PredictiveBackSettingsActivity :
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
                PredictiveBackSettingsScreen(
                    service = xposedService,
                    serviceStateObserved = serviceStateObserved,
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
    onClose: () -> Unit,
    onOpenGestureTriggerSettings: () -> Unit,
    onOpenAppList: () -> Unit,
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
    var hyperOsHaptics by remember { mutableStateOf(false) }
    var confirmedHyperOsHaptics by remember { mutableStateOf(false) }
    var hyperOsHapticsEnhanced by remember { mutableStateOf(false) }
    var confirmedHyperOsHapticsEnhanced by remember { mutableStateOf(false) }
    var hyperOsSlideAnimation by remember { mutableStateOf(false) }
    var confirmedHyperOsSlideAnimation by remember { mutableStateOf(false) }
    var aospBackgroundMode by remember {
        mutableStateOf(PredictiveBackPreferences.DEFAULT_AOSP_BACKGROUND_MODE)
    }
    var confirmedAospBackgroundMode by remember {
        mutableStateOf(PredictiveBackPreferences.DEFAULT_AOSP_BACKGROUND_MODE)
    }
    var aospWallpaperBlur by remember {
        mutableStateOf(PredictiveBackPreferences.DEFAULT_AOSP_WALLPAPER_BLUR)
    }
    var confirmedAospWallpaperBlur by remember {
        mutableStateOf(PredictiveBackPreferences.DEFAULT_AOSP_WALLPAPER_BLUR)
    }
    var moduleLogging by remember { mutableStateOf(true) }
    var confirmedModuleLogging by remember { mutableStateOf(true) }
    val writeMutex = remember(preferences) { Mutex() }
    val lazyListState = rememberLazyListState()
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
        hyperOsHaptics = false
        confirmedHyperOsHaptics = false
        hyperOsHapticsEnhanced = false
        confirmedHyperOsHapticsEnhanced = false
        hyperOsSlideAnimation = false
        confirmedHyperOsSlideAnimation = false
        aospBackgroundMode = PredictiveBackPreferences.DEFAULT_AOSP_BACKGROUND_MODE
        confirmedAospBackgroundMode =
            PredictiveBackPreferences.DEFAULT_AOSP_BACKGROUND_MODE
        aospWallpaperBlur = PredictiveBackPreferences.DEFAULT_AOSP_WALLPAPER_BLUR
        confirmedAospWallpaperBlur =
            PredictiveBackPreferences.DEFAULT_AOSP_WALLPAPER_BLUR
        moduleLogging = PredictiveBackPreferences.DEFAULT_MODULE_LOGGING
        confirmedModuleLogging = PredictiveBackPreferences.DEFAULT_MODULE_LOGGING
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
                        val migrated = remotePreferences.edit()
                            .putBoolean(PredictiveBackPreferences.KEY_HYPEROS_HAPTICS, true)
                            .remove(PredictiveBackPreferences.LEGACY_KEY_AOSP_HYPEROS_HAPTICS)
                            .commit()
                        if (migrated) true else storedHyperOsHaptics
                    } catch (_: Throwable) {
                        storedHyperOsHaptics
                    }
                }
                val flags = booleanArrayOf(
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_HYPEROS_INDICATOR,
                        PredictiveBackPreferences.DEFAULT_HYPEROS_INDICATOR,
                    ),
                    unifiedHyperOsHaptics,
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_HYPEROS_HAPTICS_ENHANCED,
                        PredictiveBackPreferences.DEFAULT_HYPEROS_HAPTICS_ENHANCED,
                    ),
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_HYPEROS_SLIDE_ANIMATION,
                        PredictiveBackPreferences.DEFAULT_HYPEROS_SLIDE_ANIMATION,
                    ),
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_MODULE_LOGGING,
                        PredictiveBackPreferences.DEFAULT_MODULE_LOGGING,
                    ),
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_AOSP_WALLPAPER_BLUR,
                        PredictiveBackPreferences.DEFAULT_AOSP_WALLPAPER_BLUR,
                    ),
                )
                val storedAospBackgroundMode = remotePreferences.getInt(
                    PredictiveBackPreferences.KEY_AOSP_BACKGROUND_MODE,
                    PredictiveBackPreferences.DEFAULT_AOSP_BACKGROUND_MODE,
                )
                val aospBackgroundMode = if (
                    PredictiveBackPreferences.isValidAospBackgroundMode(
                        storedAospBackgroundMode,
                    )
                ) {
                    storedAospBackgroundMode
                } else {
                    PredictiveBackPreferences.DEFAULT_AOSP_BACKGROUND_MODE
                }
                Triple(remotePreferences, flags, aospBackgroundMode)
            }
            preferences = loaded.first
            hyperOsIndicator = loaded.second[0]
            confirmedHyperOsIndicator = loaded.second[0]
            hyperOsHaptics = loaded.second[1]
            confirmedHyperOsHaptics = loaded.second[1]
            hyperOsHapticsEnhanced = loaded.second[2]
            confirmedHyperOsHapticsEnhanced = loaded.second[2]
            hyperOsSlideAnimation = loaded.second[3]
            confirmedHyperOsSlideAnimation = loaded.second[3]
            aospBackgroundMode = loaded.third
            confirmedAospBackgroundMode = loaded.third
            moduleLogging = loaded.second[4]
            confirmedModuleLogging = loaded.second[4]
            aospWallpaperBlur = loaded.second[5]
            confirmedAospWallpaperBlur = loaded.second[5]
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
                        val succeeded = try {
                            activePreferences.edit()
                                .putBoolean(key, requestedEnabled)
                                .commit()
                        } catch (_: Throwable) {
                            false
                        }
                        if (!succeeded) {
                            try {
                                activePreferences.edit()
                                    .putBoolean(key, fallbackEnabled)
                                    .commit()
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
    val persistHyperOsHaptics: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_HYPEROS_HAPTICS,
            requestedEnabled,
            { hyperOsHaptics = it },
            { confirmedHyperOsHaptics },
            { confirmedHyperOsHaptics = it },
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
    val persistModuleLogging: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_MODULE_LOGGING,
            requestedEnabled,
            { moduleLogging = it },
            { confirmedModuleLogging },
            { confirmedModuleLogging = it },
        )
    }
    val persistAospWallpaperBlur: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_AOSP_WALLPAPER_BLUR,
            requestedEnabled,
            { aospWallpaperBlur = it },
            { confirmedAospWallpaperBlur },
            { confirmedAospWallpaperBlur = it },
        )
    }
    val persistAospBackgroundMode: (Int) -> Unit = persistMode@{ requestedMode ->
        if (!PredictiveBackPreferences.isValidAospBackgroundMode(requestedMode)) {
            return@persistMode
        }
        val activePreferences = preferences ?: return@persistMode
        aospBackgroundMode = requestedMode
        saveError = null
        scope.launch {
            val saved = writeMutex.withLock {
                val fallbackMode = confirmedAospBackgroundMode
                val commitSucceeded = withContext(Dispatchers.IO) {
                    val succeeded = try {
                        activePreferences.edit()
                            .putInt(
                                PredictiveBackPreferences.KEY_AOSP_BACKGROUND_MODE,
                                requestedMode,
                            )
                            .commit()
                    } catch (_: Throwable) {
                        false
                    }
                    if (!succeeded) {
                        try {
                            activePreferences.edit()
                                .putInt(
                                    PredictiveBackPreferences.KEY_AOSP_BACKGROUND_MODE,
                                    fallbackMode,
                                )
                                .commit()
                        } catch (_: Throwable) {
                            // Restore the RemotePreferences cache where possible.
                        }
                    }
                    succeeded
                }
                if (preferences === activePreferences && commitSucceeded) {
                    confirmedAospBackgroundMode = requestedMode
                }
                commitSucceeded
            }
            if (preferences === activePreferences && !saved) {
                aospBackgroundMode = confirmedAospBackgroundMode
                saveError = saveErrorMessage
            }
        }
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
            TopAppBar(
                modifier = Modifier
                    .miuixBlurEffect(topBarBackdrop)
                    .background(Color.Transparent),
                color = Color.Transparent,
                scrollBehavior = scrollBehavior,
                title = stringResource(R.string.predictive_back_title),
                subtitle = "v${BuildConfig.VERSION_NAME}",
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = MiuixIcons.Regular.Close,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(topBarBackdrop)
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
            item(key = "hyperos_switches") {
                HyperOsSwitchGroupCard(
                    hyperOsIndicator = hyperOsIndicator,
                    hyperOsHaptics = hyperOsHaptics,
                    hyperOsHapticsEnhanced = hyperOsHapticsEnhanced,
                    hyperOsSlideAnimation = hyperOsSlideAnimation,
                    configurationEnabled = configurationEnabled,
                    onHyperOsIndicatorToggle = persistHyperOsIndicator,
                    onHyperOsHapticsToggle = persistHyperOsHaptics,
                    onHyperOsHapticsEnhancedToggle = persistHyperOsHapticsEnhanced,
                    onHyperOsSlideAnimationToggle = persistHyperOsSlideAnimation,
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
            item(key = "aosp_background") {
                AospBackgroundCard(
                    backgroundMode = aospBackgroundMode,
                    wallpaperBlur = aospWallpaperBlur,
                    configurationEnabled = configurationEnabled,
                    hyperOsSlideAnimation = hyperOsSlideAnimation,
                    onBackgroundModeChange = persistAospBackgroundMode,
                    onWallpaperBlurToggle = persistAospWallpaperBlur,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                )
            }
            item(key = "gesture_trigger_navigation") {
                GestureTriggerNavigationCard(
                    onClick = onOpenGestureTriggerSettings,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                )
            }
            item(key = "app_list_navigation") {
                AppListNavigationCard(
                    onClick = onOpenAppList,
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
            item(key = "navigation_bar_spacer") {
                Spacer(modifier = Modifier.navigationBarsPadding())
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
private fun HyperOsSwitchGroupCard(
    hyperOsIndicator: Boolean,
    hyperOsHaptics: Boolean,
    hyperOsHapticsEnhanced: Boolean,
    hyperOsSlideAnimation: Boolean,
    configurationEnabled: Boolean,
    onHyperOsIndicatorToggle: (Boolean) -> Unit,
    onHyperOsHapticsToggle: (Boolean) -> Unit,
    onHyperOsHapticsEnhancedToggle: (Boolean) -> Unit,
    onHyperOsSlideAnimationToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        SwitchPreference(
            title = stringResource(R.string.hyperos_indicator_title),
            summary = stringResource(R.string.hyperos_indicator_summary),
            checked = hyperOsIndicator,
            enabled = configurationEnabled,
            onCheckedChange = onHyperOsIndicatorToggle,
        )
        SwitchPreference(
            title = stringResource(R.string.hyperos_haptics_title),
            summary = stringResource(R.string.hyperos_haptics_summary),
            checked = hyperOsHaptics,
            enabled = configurationEnabled,
            onCheckedChange = onHyperOsHapticsToggle,
        )
        SwitchPreference(
            title = stringResource(R.string.hyperos_haptics_enhanced_title),
            summary = stringResource(R.string.hyperos_haptics_enhanced_summary),
            checked = hyperOsHapticsEnhanced,
            enabled = configurationEnabled && hyperOsIndicator && hyperOsHaptics,
            onCheckedChange = onHyperOsHapticsEnhancedToggle,
        )
        SwitchPreference(
            title = stringResource(R.string.hyperos_slide_animation_title),
            summary = stringResource(R.string.hyperos_slide_animation_summary),
            checked = hyperOsSlideAnimation,
            enabled = configurationEnabled,
            onCheckedChange = onHyperOsSlideAnimationToggle,
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
private fun GestureTriggerNavigationCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        ArrowPreference(
            title = stringResource(R.string.gesture_trigger_entry_title),
            summary = stringResource(R.string.gesture_trigger_entry_summary),
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
private fun AospBackgroundCard(
    backgroundMode: Int,
    wallpaperBlur: Boolean,
    configurationEnabled: Boolean,
    hyperOsSlideAnimation: Boolean,
    onBackgroundModeChange: (Int) -> Unit,
    onWallpaperBlurToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val switchesEnabled = configurationEnabled && !hyperOsSlideAnimation
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        SwitchPreference(
            title = stringResource(R.string.aosp_background_black_title),
            summary = stringResource(R.string.aosp_background_black_summary),
            checked = backgroundMode ==
                PredictiveBackPreferences.AOSP_BACKGROUND_BLACK,
            enabled = switchesEnabled,
            onCheckedChange = { checked ->
                onBackgroundModeChange(
                    if (checked) {
                        PredictiveBackPreferences.AOSP_BACKGROUND_BLACK
                    } else {
                        PredictiveBackPreferences.AOSP_BACKGROUND_SYSTEM
                    },
                )
            },
        )
        SwitchPreference(
            title = stringResource(R.string.aosp_background_wallpaper_title),
            summary = stringResource(R.string.aosp_background_wallpaper_summary),
            checked = backgroundMode ==
                PredictiveBackPreferences.AOSP_BACKGROUND_WALLPAPER,
            enabled = switchesEnabled,
            onCheckedChange = { checked ->
                onBackgroundModeChange(
                    if (checked) {
                        PredictiveBackPreferences.AOSP_BACKGROUND_WALLPAPER
                    } else {
                        PredictiveBackPreferences.AOSP_BACKGROUND_SYSTEM
                    },
                )
            },
        )
        SwitchPreference(
            title = stringResource(R.string.aosp_wallpaper_blur_title),
            summary = stringResource(R.string.aosp_wallpaper_blur_summary),
            checked = wallpaperBlur,
            enabled = switchesEnabled && backgroundMode ==
                PredictiveBackPreferences.AOSP_BACKGROUND_WALLPAPER,
            onCheckedChange = onWallpaperBlurToggle,
        )
    }
}
