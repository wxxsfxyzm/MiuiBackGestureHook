// SPDX-License-Identifier: Apache-2.0
package dev.codex.miuibackgesturehook.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
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
import top.yukonga.miuix.kmp.basic.Slider
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
import kotlin.math.roundToInt

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

private data class PredictiveBackSettingsValues(
    val preferences: SharedPreferences,
    val flags: BooleanArray,
    val aospBackgroundMode: Int,
    val hapticStrengthPercent: Int,
    val customHapticsPreset: Int,
    val aospBackPanelBlurRadius: Int,
    val aospBackPanelTransparencyPercent: Int,
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
    var aospBackPanelMaterial by remember { mutableStateOf(false) }
    var confirmedAospBackPanelMaterial by remember { mutableStateOf(false) }
    var aospBackPanelBlurRadius by remember {
        mutableIntStateOf(PredictiveBackPreferences.DEFAULT_AOSP_BACK_PANEL_BLUR_RADIUS)
    }
    var confirmedAospBackPanelBlurRadius by remember {
        mutableIntStateOf(PredictiveBackPreferences.DEFAULT_AOSP_BACK_PANEL_BLUR_RADIUS)
    }
    var aospBackPanelTransparencyPercent by remember {
        mutableIntStateOf(
            PredictiveBackPreferences.DEFAULT_AOSP_BACK_PANEL_TRANSPARENCY_PERCENT,
        )
    }
    var confirmedAospBackPanelTransparencyPercent by remember {
        mutableIntStateOf(
            PredictiveBackPreferences.DEFAULT_AOSP_BACK_PANEL_TRANSPARENCY_PERCENT,
        )
    }
    var aospBackPanelHighlight by remember { mutableStateOf(false) }
    var confirmedAospBackPanelHighlight by remember { mutableStateOf(false) }
    var hyperOsHaptics by remember { mutableStateOf(false) }
    var confirmedHyperOsHaptics by remember { mutableStateOf(false) }
    var hyperOsHapticsEnhanced by remember { mutableStateOf(false) }
    var confirmedHyperOsHapticsEnhanced by remember { mutableStateOf(false) }
    var customHaptics by remember { mutableStateOf(false) }
    var confirmedCustomHaptics by remember { mutableStateOf(false) }
    var customHapticsPreset by remember {
        mutableIntStateOf(PredictiveBackPreferences.DEFAULT_CUSTOM_HAPTICS_PRESET)
    }
    var confirmedCustomHapticsPreset by remember {
        mutableIntStateOf(PredictiveBackPreferences.DEFAULT_CUSTOM_HAPTICS_PRESET)
    }
    var hyperOsHapticsStrengthPercent by remember {
        mutableIntStateOf(
            PredictiveBackPreferences.DEFAULT_HYPEROS_HAPTICS_STRENGTH_PERCENT,
        )
    }
    var confirmedHyperOsHapticsStrengthPercent by remember {
        mutableIntStateOf(
            PredictiveBackPreferences.DEFAULT_HYPEROS_HAPTICS_STRENGTH_PERCENT,
        )
    }
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
        aospBackPanelMaterial = false
        confirmedAospBackPanelMaterial = false
        aospBackPanelBlurRadius =
            PredictiveBackPreferences.DEFAULT_AOSP_BACK_PANEL_BLUR_RADIUS
        confirmedAospBackPanelBlurRadius = aospBackPanelBlurRadius
        aospBackPanelTransparencyPercent =
            PredictiveBackPreferences.DEFAULT_AOSP_BACK_PANEL_TRANSPARENCY_PERCENT
        confirmedAospBackPanelTransparencyPercent = aospBackPanelTransparencyPercent
        aospBackPanelHighlight = false
        confirmedAospBackPanelHighlight = false
        hyperOsHaptics = false
        confirmedHyperOsHaptics = false
        hyperOsHapticsEnhanced = false
        confirmedHyperOsHapticsEnhanced = false
        customHaptics = false
        confirmedCustomHaptics = false
        customHapticsPreset = PredictiveBackPreferences.DEFAULT_CUSTOM_HAPTICS_PRESET
        confirmedCustomHapticsPreset =
            PredictiveBackPreferences.DEFAULT_CUSTOM_HAPTICS_PRESET
        hyperOsHapticsStrengthPercent =
            PredictiveBackPreferences.DEFAULT_HYPEROS_HAPTICS_STRENGTH_PERCENT
        confirmedHyperOsHapticsStrengthPercent =
            PredictiveBackPreferences.DEFAULT_HYPEROS_HAPTICS_STRENGTH_PERCENT
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
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_AOSP_BACK_PANEL_MATERIAL,
                        PredictiveBackPreferences.DEFAULT_AOSP_BACK_PANEL_MATERIAL,
                    ),
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_CUSTOM_HAPTICS,
                        PredictiveBackPreferences.DEFAULT_CUSTOM_HAPTICS,
                    ),
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_AOSP_BACK_PANEL_HIGHLIGHT,
                        PredictiveBackPreferences.DEFAULT_AOSP_BACK_PANEL_HIGHLIGHT,
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
                PredictiveBackSettingsValues(
                    preferences = remotePreferences,
                    flags = flags,
                    aospBackgroundMode = aospBackgroundMode,
                    hapticStrengthPercent = PredictiveBackPreferences
                        .clampHyperOsHapticsStrengthPercent(
                            remotePreferences.getInt(
                                PredictiveBackPreferences
                                    .KEY_HYPEROS_HAPTICS_STRENGTH_PERCENT,
                                PredictiveBackPreferences
                                    .DEFAULT_HYPEROS_HAPTICS_STRENGTH_PERCENT,
                            ),
                        ),
                    customHapticsPreset = remotePreferences.getInt(
                        PredictiveBackPreferences.KEY_CUSTOM_HAPTICS_PRESET,
                        PredictiveBackPreferences.DEFAULT_CUSTOM_HAPTICS_PRESET,
                    ).let {
                        if (PredictiveBackPreferences.isValidCustomHapticsPreset(it)) {
                            it
                        } else {
                            PredictiveBackPreferences.DEFAULT_CUSTOM_HAPTICS_PRESET
                        }
                    },
                    aospBackPanelBlurRadius = PredictiveBackPreferences
                        .clampAospBackPanelBlurRadius(
                            remotePreferences.getInt(
                                PredictiveBackPreferences.KEY_AOSP_BACK_PANEL_BLUR_RADIUS,
                                PredictiveBackPreferences.DEFAULT_AOSP_BACK_PANEL_BLUR_RADIUS,
                            ),
                        ),
                    aospBackPanelTransparencyPercent = PredictiveBackPreferences
                        .clampAospBackPanelTransparencyPercent(
                            remotePreferences.getInt(
                                PredictiveBackPreferences
                                    .KEY_AOSP_BACK_PANEL_TRANSPARENCY_PERCENT,
                                PredictiveBackPreferences
                                    .DEFAULT_AOSP_BACK_PANEL_TRANSPARENCY_PERCENT,
                            ),
                        ),
                )
            }
            preferences = loaded.preferences
            hyperOsIndicator = loaded.flags[0]
            confirmedHyperOsIndicator = loaded.flags[0]
            hyperOsHaptics = loaded.flags[1]
            confirmedHyperOsHaptics = loaded.flags[1]
            hyperOsHapticsEnhanced = loaded.flags[2]
            confirmedHyperOsHapticsEnhanced = loaded.flags[2]
            hyperOsSlideAnimation = loaded.flags[3]
            confirmedHyperOsSlideAnimation = loaded.flags[3]
            aospBackgroundMode = loaded.aospBackgroundMode
            confirmedAospBackgroundMode = loaded.aospBackgroundMode
            moduleLogging = loaded.flags[4]
            confirmedModuleLogging = loaded.flags[4]
            aospWallpaperBlur = loaded.flags[5]
            confirmedAospWallpaperBlur = loaded.flags[5]
            aospBackPanelMaterial = loaded.flags[6]
            confirmedAospBackPanelMaterial = loaded.flags[6]
            customHaptics = loaded.flags[7]
            confirmedCustomHaptics = loaded.flags[7]
            aospBackPanelHighlight = loaded.flags[8]
            confirmedAospBackPanelHighlight = loaded.flags[8]
            customHapticsPreset = loaded.customHapticsPreset
            confirmedCustomHapticsPreset = loaded.customHapticsPreset
            hyperOsHapticsStrengthPercent = loaded.hapticStrengthPercent
            confirmedHyperOsHapticsStrengthPercent = loaded.hapticStrengthPercent
            aospBackPanelBlurRadius = loaded.aospBackPanelBlurRadius
            confirmedAospBackPanelBlurRadius = loaded.aospBackPanelBlurRadius
            aospBackPanelTransparencyPercent = loaded.aospBackPanelTransparencyPercent
            confirmedAospBackPanelTransparencyPercent =
                loaded.aospBackPanelTransparencyPercent
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
    val persistIntPreference: (
        String,
        Int,
        (Int) -> Unit,
        () -> Int,
        (Int) -> Unit,
    ) -> Unit = { key, requestedValue, setLocal, getConfirmed, setConfirmed ->
        val activePreferences = preferences
        if (activePreferences != null) {
            setLocal(requestedValue)
            saveError = null
            scope.launch {
                val saved = writeMutex.withLock {
                    val fallbackValue = getConfirmed()
                    val commitSucceeded = withContext(Dispatchers.IO) {
                        val succeeded = try {
                            activePreferences.edit().putInt(key, requestedValue).commit()
                        } catch (_: Throwable) {
                            false
                        }
                        if (!succeeded) {
                            try {
                                activePreferences.edit().putInt(key, fallbackValue).commit()
                            } catch (_: Throwable) {
                                // Keep the last confirmed value below.
                            }
                        }
                        succeeded
                    }
                    if (preferences === activePreferences && commitSucceeded) {
                        setConfirmed(requestedValue)
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
    val persistCustomHaptics: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_CUSTOM_HAPTICS,
            requestedEnabled,
            { customHaptics = it },
            { confirmedCustomHaptics },
            { confirmedCustomHaptics = it },
        )
    }
    val persistCustomHapticsPreset: (Int) -> Unit = persistPreset@{ requestedPreset ->
        if (!PredictiveBackPreferences.isValidCustomHapticsPreset(requestedPreset)) {
            return@persistPreset
        }
        val activePreferences = preferences ?: return@persistPreset
        customHapticsPreset = requestedPreset
        scope.launch {
            val saved = writeMutex.withLock {
                val succeeded = withContext(Dispatchers.IO) {
                    try {
                        activePreferences.edit()
                            .putInt(
                                PredictiveBackPreferences.KEY_CUSTOM_HAPTICS_PRESET,
                                requestedPreset,
                            )
                            .commit()
                    } catch (_: Throwable) {
                        false
                    }
                }
                if (preferences === activePreferences && succeeded) {
                    confirmedCustomHapticsPreset = requestedPreset
                }
                succeeded
            }
            if (preferences === activePreferences && !saved) {
                customHapticsPreset = confirmedCustomHapticsPreset
                saveError = saveErrorMessage
            }
        }
    }
    val persistAospBackPanelMaterial: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_AOSP_BACK_PANEL_MATERIAL,
            requestedEnabled,
            { aospBackPanelMaterial = it },
            { confirmedAospBackPanelMaterial },
            { confirmedAospBackPanelMaterial = it },
        )
    }
    val persistAospBackPanelHighlight: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_AOSP_BACK_PANEL_HIGHLIGHT,
            requestedEnabled,
            { aospBackPanelHighlight = it },
            { confirmedAospBackPanelHighlight },
            { confirmedAospBackPanelHighlight = it },
        )
    }
    val persistAospBackPanelBlurRadius: (Int) -> Unit = { requestedRadius ->
        val radius = PredictiveBackPreferences.clampAospBackPanelBlurRadius(requestedRadius)
        persistIntPreference(
            PredictiveBackPreferences.KEY_AOSP_BACK_PANEL_BLUR_RADIUS,
            radius,
            { aospBackPanelBlurRadius = it },
            { confirmedAospBackPanelBlurRadius },
            { confirmedAospBackPanelBlurRadius = it },
        )
    }
    val persistAospBackPanelTransparency: (Int) -> Unit = { requestedTransparency ->
        val transparency = PredictiveBackPreferences
            .clampAospBackPanelTransparencyPercent(requestedTransparency)
        persistIntPreference(
            PredictiveBackPreferences.KEY_AOSP_BACK_PANEL_TRANSPARENCY_PERCENT,
            transparency,
            { aospBackPanelTransparencyPercent = it },
            { confirmedAospBackPanelTransparencyPercent },
            { confirmedAospBackPanelTransparencyPercent = it },
        )
    }
    val persistHyperOsHapticsStrength: (Int) -> Unit = { requestedStrength ->
        val strength = PredictiveBackPreferences
            .clampHyperOsHapticsStrengthPercent(requestedStrength)
        val activePreferences = preferences
        if (activePreferences != null) {
            hyperOsHapticsStrengthPercent = strength
            saveError = null
            scope.launch {
                val saved = writeMutex.withLock {
                    val fallbackStrength = confirmedHyperOsHapticsStrengthPercent
                    val commitSucceeded = withContext(Dispatchers.IO) {
                        val succeeded = try {
                            activePreferences.edit()
                                .putInt(
                                    PredictiveBackPreferences
                                        .KEY_HYPEROS_HAPTICS_STRENGTH_PERCENT,
                                    strength,
                                )
                                .commit()
                        } catch (_: Throwable) {
                            false
                        }
                        if (!succeeded) {
                            try {
                                activePreferences.edit()
                                    .putInt(
                                        PredictiveBackPreferences
                                            .KEY_HYPEROS_HAPTICS_STRENGTH_PERCENT,
                                        fallbackStrength,
                                    )
                                    .commit()
                            } catch (_: Throwable) {
                                // Keep the last confirmed value below.
                            }
                        }
                        succeeded
                    }
                    if (preferences === activePreferences && commitSucceeded) {
                        confirmedHyperOsHapticsStrengthPercent = strength
                    }
                    commitSucceeded
                }
                if (preferences === activePreferences && !saved) {
                    hyperOsHapticsStrengthPercent =
                        confirmedHyperOsHapticsStrengthPercent
                    saveError = saveErrorMessage
                }
            }
        }
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
                    aospBackPanelMaterial = aospBackPanelMaterial,
                    aospBackPanelBlurRadius = aospBackPanelBlurRadius,
                    aospBackPanelTransparencyPercent =
                        aospBackPanelTransparencyPercent,
                    aospBackPanelHighlight = aospBackPanelHighlight,
                    hyperOsHaptics = hyperOsHaptics,
                    hyperOsHapticsEnhanced = hyperOsHapticsEnhanced,
                    customHaptics = customHaptics,
                    customHapticsPreset = customHapticsPreset,
                    hyperOsHapticsStrengthPercent = hyperOsHapticsStrengthPercent,
                    hyperOsSlideAnimation = hyperOsSlideAnimation,
                    configurationEnabled = configurationEnabled,
                    onHyperOsIndicatorToggle = persistHyperOsIndicator,
                    onAospBackPanelMaterialToggle = persistAospBackPanelMaterial,
                    onAospBackPanelBlurRadiusChange = { aospBackPanelBlurRadius = it },
                    onAospBackPanelBlurRadiusChangeFinished = {
                        persistAospBackPanelBlurRadius(aospBackPanelBlurRadius)
                    },
                    onAospBackPanelBlurRadiusReset = {
                        persistAospBackPanelBlurRadius(
                            PredictiveBackPreferences.DEFAULT_AOSP_BACK_PANEL_BLUR_RADIUS,
                        )
                    },
                    onAospBackPanelTransparencyChange = {
                        aospBackPanelTransparencyPercent = it
                    },
                    onAospBackPanelTransparencyChangeFinished = {
                        persistAospBackPanelTransparency(aospBackPanelTransparencyPercent)
                    },
                    onAospBackPanelTransparencyReset = {
                        persistAospBackPanelTransparency(
                            PredictiveBackPreferences
                                .DEFAULT_AOSP_BACK_PANEL_TRANSPARENCY_PERCENT,
                        )
                    },
                    onAospBackPanelHighlightToggle = persistAospBackPanelHighlight,
                    onHyperOsHapticsToggle = { enabled ->
                        if (enabled && customHaptics) persistCustomHaptics(false)
                        persistHyperOsHaptics(enabled)
                    },
                    onHyperOsHapticsEnhancedToggle = persistHyperOsHapticsEnhanced,
                    onCustomHapticsToggle = { enabled ->
                        if (enabled && hyperOsHaptics) persistHyperOsHaptics(false)
                        persistCustomHaptics(enabled)
                    },
                    onCustomHapticsPresetChange = persistCustomHapticsPreset,
                    onHyperOsHapticsStrengthChange = {
                        hyperOsHapticsStrengthPercent = it
                    },
                    onHyperOsHapticsStrengthChangeFinished = {
                        persistHyperOsHapticsStrength(hyperOsHapticsStrengthPercent)
                    },
                    onHyperOsHapticsStrengthReset = {
                        persistHyperOsHapticsStrength(
                            PredictiveBackPreferences
                                .DEFAULT_HYPEROS_HAPTICS_STRENGTH_PERCENT,
                        )
                    },
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
    aospBackPanelMaterial: Boolean,
    aospBackPanelBlurRadius: Int,
    aospBackPanelTransparencyPercent: Int,
    aospBackPanelHighlight: Boolean,
    hyperOsHaptics: Boolean,
    hyperOsHapticsEnhanced: Boolean,
    customHaptics: Boolean,
    customHapticsPreset: Int,
    hyperOsHapticsStrengthPercent: Int,
    hyperOsSlideAnimation: Boolean,
    configurationEnabled: Boolean,
    onHyperOsIndicatorToggle: (Boolean) -> Unit,
    onAospBackPanelMaterialToggle: (Boolean) -> Unit,
    onAospBackPanelBlurRadiusChange: (Int) -> Unit,
    onAospBackPanelBlurRadiusChangeFinished: () -> Unit,
    onAospBackPanelBlurRadiusReset: () -> Unit,
    onAospBackPanelTransparencyChange: (Int) -> Unit,
    onAospBackPanelTransparencyChangeFinished: () -> Unit,
    onAospBackPanelTransparencyReset: () -> Unit,
    onAospBackPanelHighlightToggle: (Boolean) -> Unit,
    onHyperOsHapticsToggle: (Boolean) -> Unit,
    onHyperOsHapticsEnhancedToggle: (Boolean) -> Unit,
    onCustomHapticsToggle: (Boolean) -> Unit,
    onCustomHapticsPresetChange: (Int) -> Unit,
    onHyperOsHapticsStrengthChange: (Int) -> Unit,
    onHyperOsHapticsStrengthChangeFinished: () -> Unit,
    onHyperOsHapticsStrengthReset: () -> Unit,
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
            title = stringResource(R.string.aosp_back_panel_material_title),
            summary = stringResource(R.string.aosp_back_panel_material_summary),
            checked = aospBackPanelMaterial,
            enabled = configurationEnabled && !hyperOsIndicator,
            onCheckedChange = onAospBackPanelMaterialToggle,
        )
        BackPanelMaterialSliderPreference(
            title = stringResource(R.string.aosp_back_panel_blur_radius_title),
            summary = stringResource(
                R.string.aosp_back_panel_blur_radius_summary,
                aospBackPanelBlurRadius,
            ),
            value = aospBackPanelBlurRadius,
            valueRange = PredictiveBackPreferences.MIN_AOSP_BACK_PANEL_BLUR_RADIUS..
                PredictiveBackPreferences.MAX_AOSP_BACK_PANEL_BLUR_RADIUS,
            stepSize = 8,
            defaultValue = PredictiveBackPreferences.DEFAULT_AOSP_BACK_PANEL_BLUR_RADIUS,
            enabled = configurationEnabled && aospBackPanelMaterial && !hyperOsIndicator,
            onValueChange = onAospBackPanelBlurRadiusChange,
            onValueChangeFinished = onAospBackPanelBlurRadiusChangeFinished,
            onReset = onAospBackPanelBlurRadiusReset,
        )
        BackPanelMaterialSliderPreference(
            title = stringResource(R.string.aosp_back_panel_transparency_title),
            summary = stringResource(
                R.string.aosp_back_panel_transparency_summary,
                aospBackPanelTransparencyPercent,
            ),
            value = aospBackPanelTransparencyPercent,
            valueRange = PredictiveBackPreferences
                .MIN_AOSP_BACK_PANEL_TRANSPARENCY_PERCENT..
                PredictiveBackPreferences.MAX_AOSP_BACK_PANEL_TRANSPARENCY_PERCENT,
            stepSize = 2,
            defaultValue = PredictiveBackPreferences
                .DEFAULT_AOSP_BACK_PANEL_TRANSPARENCY_PERCENT,
            enabled = configurationEnabled && aospBackPanelMaterial && !hyperOsIndicator,
            onValueChange = onAospBackPanelTransparencyChange,
            onValueChangeFinished = onAospBackPanelTransparencyChangeFinished,
            onReset = onAospBackPanelTransparencyReset,
        )
        SwitchPreference(
            title = stringResource(R.string.aosp_back_panel_highlight_title),
            summary = stringResource(R.string.aosp_back_panel_highlight_summary),
            checked = aospBackPanelHighlight,
            enabled = configurationEnabled && aospBackPanelMaterial && !hyperOsIndicator,
            onCheckedChange = onAospBackPanelHighlightToggle,
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
            title = stringResource(R.string.custom_haptics_title),
            summary = stringResource(R.string.custom_haptics_summary),
            checked = customHaptics,
            enabled = configurationEnabled,
            onCheckedChange = onCustomHapticsToggle,
        )
        ArrowPreference(
            title = stringResource(R.string.custom_haptics_preset_title),
            summary = customHapticsPresetName(customHapticsPreset),
            enabled = configurationEnabled && customHaptics,
            onClick = {
                val next = when (customHapticsPreset) {
                    PredictiveBackPreferences.CUSTOM_HAPTICS_PRESET_CRISP ->
                        PredictiveBackPreferences.CUSTOM_HAPTICS_PRESET_SOFT
                    PredictiveBackPreferences.CUSTOM_HAPTICS_PRESET_SOFT ->
                        PredictiveBackPreferences.CUSTOM_HAPTICS_PRESET_DEEP
                    PredictiveBackPreferences.CUSTOM_HAPTICS_PRESET_DEEP ->
                        PredictiveBackPreferences.CUSTOM_HAPTICS_PRESET_DOUBLE
                    else -> PredictiveBackPreferences.CUSTOM_HAPTICS_PRESET_CRISP
                }
                onCustomHapticsPresetChange(next)
            },
        )
        HapticStrengthPreference(
            strengthPercent = hyperOsHapticsStrengthPercent,
            preset = customHapticsPreset,
            enabled = configurationEnabled && customHaptics,
            onStrengthChange = onHyperOsHapticsStrengthChange,
            onStrengthChangeFinished = onHyperOsHapticsStrengthChangeFinished,
            onReset = onHyperOsHapticsStrengthReset,
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
    val blackBackgroundEnabled = configurationEnabled && !hyperOsSlideAnimation
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        SwitchPreference(
            title = stringResource(R.string.aosp_background_black_title),
            summary = stringResource(R.string.aosp_background_black_summary),
            checked = backgroundMode ==
                PredictiveBackPreferences.AOSP_BACKGROUND_BLACK,
            enabled = blackBackgroundEnabled,
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
            enabled = configurationEnabled,
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
            enabled = configurationEnabled && backgroundMode ==
                PredictiveBackPreferences.AOSP_BACKGROUND_WALLPAPER,
            onCheckedChange = onWallpaperBlurToggle,
        )
    }
}

@Composable
private fun BackPanelMaterialSliderPreference(
    title: String,
    summary: String,
    value: Int,
    valueRange: IntRange,
    stepSize: Int,
    defaultValue: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Column(
            modifier = Modifier.combinedClickable(
                enabled = enabled,
                onClick = {},
                onLongClick = {
                    if (value != defaultValue) onReset()
                },
            ),
        ) {
            Text(text = title, style = MiuixTheme.textStyles.title4)
            Text(
                text = summary,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
            )
        }
        Slider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            value = value.toFloat(),
            onValueChange = { rawValue ->
                val snapped = (
                    valueRange.first +
                        ((rawValue - valueRange.first) / stepSize).roundToInt() * stepSize
                    ).coerceIn(valueRange)
                if (snapped != value) onValueChange(snapped)
            },
            enabled = enabled,
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = ((valueRange.last - valueRange.first) / stepSize - 1).coerceAtLeast(0),
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}

@Composable
private fun HapticStrengthPreference(
    strengthPercent: Int,
    preset: Int,
    enabled: Boolean,
    onStrengthChange: (Int) -> Unit,
    onStrengthChangeFinished: () -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Column(
            modifier = Modifier.combinedClickable(
                enabled = enabled,
                onClick = {},
                onLongClick = {
                    val defaultStrength = PredictiveBackPreferences
                        .DEFAULT_HYPEROS_HAPTICS_STRENGTH_PERCENT
                    if (strengthPercent != defaultStrength) {
                        playHapticStrengthPreview(context, defaultStrength, preset)
                        onReset()
                    }
                },
            ),
        ) {
            Text(
                text = stringResource(R.string.hyperos_haptics_strength_title),
                style = MiuixTheme.textStyles.title4,
            )
            Text(
                text = stringResource(
                    R.string.hyperos_haptics_strength_summary,
                    strengthPercent,
                ),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
            )
        }
        Slider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            value = strengthPercent.toFloat(),
            onValueChange = { rawValue ->
                val step = ((rawValue / 10.0f).roundToInt() * 10).coerceIn(
                    PredictiveBackPreferences.MIN_HYPEROS_HAPTICS_STRENGTH_PERCENT,
                    PredictiveBackPreferences.MAX_HYPEROS_HAPTICS_STRENGTH_PERCENT,
                )
                if (step != strengthPercent) {
                    playHapticStrengthPreview(context, step, preset)
                    onStrengthChange(step)
                }
            },
            enabled = enabled,
            valueRange = PredictiveBackPreferences
                .MIN_HYPEROS_HAPTICS_STRENGTH_PERCENT.toFloat()..
                PredictiveBackPreferences.MAX_HYPEROS_HAPTICS_STRENGTH_PERCENT.toFloat(),
            steps = 17,
            onValueChangeFinished = onStrengthChangeFinished,
        )
    }
}

private fun playHapticStrengthPreview(
    context: android.content.Context,
    strengthPercent: Int,
    preset: Int,
) {
    try {
        val vibrator = context.getSystemService(VibratorManager::class.java)
            ?.defaultVibrator ?: return
        val scale = (strengthPercent / 160.0f).coerceIn(0.1f, 1.0f)
        val composition = VibrationEffect.startComposition()
        when (preset) {
            PredictiveBackPreferences.CUSTOM_HAPTICS_PRESET_SOFT ->
                composition.addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_TICK,
                    (scale * 0.65f).coerceAtMost(0.7f),
                )
            PredictiveBackPreferences.CUSTOM_HAPTICS_PRESET_DEEP ->
                composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale)
            PredictiveBackPreferences.CUSTOM_HAPTICS_PRESET_DOUBLE ->
                composition
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale)
                    .addPrimitive(
                        VibrationEffect.Composition.PRIMITIVE_TICK,
                        (scale * 0.8f).coerceAtMost(1.0f),
                        42,
                    )
            else -> composition.addPrimitive(
                VibrationEffect.Composition.PRIMITIVE_TICK,
                scale,
            )
        }
        vibrator.vibrate(composition.compose())
    } catch (_: Throwable) {
        // Keep the slider usable when the device cannot scale this primitive.
    }
}

@Composable
private fun customHapticsPresetName(preset: Int): String = stringResource(
    when (preset) {
        PredictiveBackPreferences.CUSTOM_HAPTICS_PRESET_SOFT ->
            R.string.custom_haptics_preset_soft
        PredictiveBackPreferences.CUSTOM_HAPTICS_PRESET_DEEP ->
            R.string.custom_haptics_preset_deep
        PredictiveBackPreferences.CUSTOM_HAPTICS_PRESET_DOUBLE ->
            R.string.custom_haptics_preset_double
        else -> R.string.custom_haptics_preset_crisp
    },
)
