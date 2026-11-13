// SPDX-License-Identifier: Apache-2.0
package dev.codex.miuibackgesturehook.activity

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.codex.miuibackgesturehook.ModuleApplication
import dev.codex.miuibackgesturehook.PredictiveBackPreferences
import dev.codex.miuibackgesturehook.R
import dev.codex.miuibackgesturehook.util.rememberMiuixBlurBackdrop
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

class GestureTriggerSettingsActivity :
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
                GestureTriggerSettingsScreen(
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

private data class GestureTriggerSettingsValues(
    val preferences: android.content.SharedPreferences,
    val heightPercent: Int,
    val positionPercent: Int,
)

private enum class GestureTriggerStatusSeverity {
    Info,
    Error,
}

private data class GestureTriggerStatus(
    val text: String,
    val severity: GestureTriggerStatusSeverity,
)

@Composable
@SuppressLint("ApplySharedPref")
private fun GestureTriggerSettingsScreen(
    service: XposedService?,
    serviceStateObserved: Boolean,
    onClose: () -> Unit,
) {
    val configurationErrorMessage = stringResource(R.string.predictive_back_config_error)
    val saveErrorMessage = stringResource(R.string.predictive_back_save_error)
    val serviceLoadingMessage = stringResource(R.string.predictive_back_service_loading)
    val serviceUnavailableMessage =
        stringResource(R.string.predictive_back_service_unavailable)
    val scope = rememberCoroutineScope()
    var preferences by remember { mutableStateOf<android.content.SharedPreferences?>(null) }
    var configurationLoading by remember { mutableStateOf(true) }
    var configurationError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var heightPercent by remember {
        mutableIntStateOf(PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_HEIGHT_PERCENT)
    }
    var confirmedHeightPercent by remember {
        mutableIntStateOf(PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_HEIGHT_PERCENT)
    }
    var positionPercent by remember {
        mutableIntStateOf(PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_POSITION_PERCENT)
    }
    var confirmedPositionPercent by remember {
        mutableIntStateOf(PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_POSITION_PERCENT)
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
        preferences = null
        configurationError = null
        saveError = null
        heightPercent = PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_HEIGHT_PERCENT
        confirmedHeightPercent = PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_HEIGHT_PERCENT
        positionPercent = PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_POSITION_PERCENT
        confirmedPositionPercent =
            PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_POSITION_PERCENT
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
                GestureTriggerSettingsValues(
                    preferences = remotePreferences,
                    heightPercent = remotePreferences.getInt(
                        PredictiveBackPreferences.KEY_GESTURE_TRIGGER_HEIGHT_PERCENT,
                        PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_HEIGHT_PERCENT,
                    ).coerceIn(
                        PredictiveBackPreferences.MIN_GESTURE_TRIGGER_HEIGHT_PERCENT,
                        PredictiveBackPreferences.MAX_GESTURE_TRIGGER_HEIGHT_PERCENT,
                    ),
                    positionPercent = remotePreferences.getInt(
                        PredictiveBackPreferences.KEY_GESTURE_TRIGGER_POSITION_PERCENT,
                        PredictiveBackPreferences.DEFAULT_GESTURE_TRIGGER_POSITION_PERCENT,
                    ).coerceIn(
                        PredictiveBackPreferences.MIN_GESTURE_TRIGGER_POSITION_PERCENT,
                        PredictiveBackPreferences.MAX_GESTURE_TRIGGER_POSITION_PERCENT,
                    ),
                )
            }
            preferences = loaded.preferences
            heightPercent = loaded.heightPercent
            confirmedHeightPercent = loaded.heightPercent
            positionPercent = loaded.positionPercent
            confirmedPositionPercent = loaded.positionPercent
        } catch (_: Throwable) {
            configurationError = configurationErrorMessage
        } finally {
            configurationLoading = false
        }
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
                            activePreferences.edit()
                                .putInt(key, requestedValue)
                                .commit()
                        } catch (_: Throwable) {
                            false
                        }
                        if (!succeeded) {
                            try {
                                activePreferences.edit()
                                    .putInt(key, fallbackValue)
                                    .commit()
                            } catch (_: Throwable) {
                                // Keep the last confirmed local value below.
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

    val configurationEnabled = preferences != null && !configurationLoading
    val statusMessage = when {
        configurationLoading -> GestureTriggerStatus(
            serviceLoadingMessage,
            GestureTriggerStatusSeverity.Info,
        )

        configurationError != null -> GestureTriggerStatus(
            configurationError.orEmpty(),
            GestureTriggerStatusSeverity.Error,
        )

        saveError != null -> GestureTriggerStatus(
            saveError.orEmpty(),
            GestureTriggerStatusSeverity.Error,
        )

        serviceStateObserved && service == null -> GestureTriggerStatus(
            serviceUnavailableMessage,
            GestureTriggerStatusSeverity.Error,
        )

        else -> null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                GlassTopAppBar(
                    backdrop = topBarBackdrop,
                    isContentScrolled = isContentScrolled,
                    progressiveBlur = true,
                    scrollBehavior = scrollBehavior,
                    title = stringResource(R.string.gesture_trigger_title),
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
                        start = horizontalSafeInsets.calculateLeftPadding(
                            LocalLayoutDirection.current,
                        ),
                        top = paddingValues.calculateTopPadding() + 8.dp,
                        end = horizontalSafeInsets.calculateRightPadding(
                            LocalLayoutDirection.current,
                        ),
                        bottom = paddingValues.calculateBottomPadding(),
                    ),
                    overscrollEffect = null,
                ) {
                    item(key = "sliders") {
                        GestureTriggerSliderCard(
                            heightPercent = heightPercent,
                            positionPercent = positionPercent,
                            enabled = configurationEnabled,
                            onHeightChange = { heightPercent = it },
                            onHeightChangeFinished = {
                                persistIntPreference(
                                    PredictiveBackPreferences.KEY_GESTURE_TRIGGER_HEIGHT_PERCENT,
                                    heightPercent,
                                    { heightPercent = it },
                                    { confirmedHeightPercent },
                                    { confirmedHeightPercent = it },
                                )
                            },
                            onPositionChange = { positionPercent = it },
                            onPositionChangeFinished = {
                                persistIntPreference(
                                    PredictiveBackPreferences.KEY_GESTURE_TRIGGER_POSITION_PERCENT,
                                    positionPercent,
                                    { positionPercent = it },
                                    { confirmedPositionPercent },
                                    { confirmedPositionPercent = it },
                                )
                            },
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 8.dp),
                        )
                    }
                    item(key = "apply_note") {
                        Card(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 8.dp)
                                .fillMaxWidth(),
                            insideMargin = PaddingValues(16.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.gesture_trigger_apply_note),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    if (statusMessage != null) {
                        item(key = "status") {
                            GestureTriggerStatusCard(
                                status = statusMessage,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 8.dp),
                            )
                        }
                    }
                }
            }
        }
        if (configurationEnabled) {
            GestureTriggerScreenEdgeIndicator(
                heightPercent = heightPercent,
                positionPercent = positionPercent,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun GestureTriggerScreenEdgeIndicator(
    heightPercent: Int,
    positionPercent: Int,
    modifier: Modifier = Modifier,
) {
    val primary = MiuixTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val indicatorHeight = size.height * heightPercent / 100f
        val travel = (size.height - indicatorHeight).coerceAtLeast(0f)
        val indicatorTop = travel * positionPercent / 100f
        val glowWidth = 10.dp.toPx()
        val coreWidth = 3.dp.toPx()

        drawRect(
            color = primary.copy(alpha = 0.18f),
            topLeft = Offset(0f, indicatorTop),
            size = Size(glowWidth, indicatorHeight),
        )
        drawRect(
            color = primary.copy(alpha = 0.9f),
            topLeft = Offset(0f, indicatorTop),
            size = Size(coreWidth, indicatorHeight),
        )
        drawRect(
            color = primary.copy(alpha = 0.18f),
            topLeft = Offset(size.width - glowWidth, indicatorTop),
            size = Size(glowWidth, indicatorHeight),
        )
        drawRect(
            color = primary.copy(alpha = 0.9f),
            topLeft = Offset(size.width - coreWidth, indicatorTop),
            size = Size(coreWidth, indicatorHeight),
        )
    }
}

@Composable
private fun GestureTriggerSliderCard(
    heightPercent: Int,
    positionPercent: Int,
    enabled: Boolean,
    onHeightChange: (Int) -> Unit,
    onHeightChangeFinished: () -> Unit,
    onPositionChange: (Int) -> Unit,
    onPositionChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TriggerSlider(
                title = stringResource(R.string.gesture_trigger_height_title),
                summary = stringResource(R.string.gesture_trigger_height_summary),
                value = heightPercent,
                valueRange = PredictiveBackPreferences.MIN_GESTURE_TRIGGER_HEIGHT_PERCENT
                    .toFloat()..PredictiveBackPreferences.MAX_GESTURE_TRIGGER_HEIGHT_PERCENT
                    .toFloat(),
                steps = PredictiveBackPreferences.MAX_GESTURE_TRIGGER_HEIGHT_PERCENT
                        - PredictiveBackPreferences.MIN_GESTURE_TRIGGER_HEIGHT_PERCENT - 1,
                enabled = enabled,
                onValueChange = { onHeightChange(it.toInt()) },
                onValueChangeFinished = onHeightChangeFinished,
            )
            TriggerSlider(
                title = stringResource(R.string.gesture_trigger_position_title),
                summary = stringResource(R.string.gesture_trigger_position_summary),
                value = positionPercent,
                valueRange = PredictiveBackPreferences.MIN_GESTURE_TRIGGER_POSITION_PERCENT
                    .toFloat()..PredictiveBackPreferences.MAX_GESTURE_TRIGGER_POSITION_PERCENT
                    .toFloat(),
                steps = PredictiveBackPreferences.MAX_GESTURE_TRIGGER_POSITION_PERCENT
                        - PredictiveBackPreferences.MIN_GESTURE_TRIGGER_POSITION_PERCENT - 1,
                enabled = enabled,
                onValueChange = { onPositionChange(it.toInt()) },
                onValueChangeFinished = onPositionChangeFinished,
            )
        }
    }
}

@Composable
private fun TriggerSlider(
    title: String,
    summary: String,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.title4,
        )
        Text(
            text = summary,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2,
        )
        Slider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            value = value.toFloat(),
            onValueChange = onValueChange,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}

@Composable
private fun GestureTriggerStatusCard(
    status: GestureTriggerStatus,
    modifier: Modifier = Modifier,
) {
    val accent = when (status.severity) {
        GestureTriggerStatusSeverity.Info -> MiuixTheme.colorScheme.primary
        GestureTriggerStatusSeverity.Error -> MiuixTheme.colorScheme.error
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
        colors = CardDefaults.defaultColors(
            color = accent.copy(alpha = 0.2f),
            contentColor = accent,
        ),
    ) {
        Text(text = status.text, style = MiuixTheme.textStyles.body2)
    }
}
