// SPDX-License-Identifier: Apache-2.0
// UI structure adapted with permission from InstallerX Revived's MiuixApplyPage.
// Copyright (C) 2025-2026 InstallerX Revived contributors.
package dev.codex.miuibackgesturehook

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Looper
import android.util.LruCache
import android.util.Printer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.libxposed.service.XposedService
import java.util.HashSet
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowListPopup

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
        val update = {
            xposedService = service
            serviceStateObserved = true
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            update()
        } else {
            runOnUiThread(update)
        }
    }
}

private data class AppEntry(
    val packageName: String,
    val label: String,
    val launcherActivity: ComponentName?,
    val firstInstallTime: Long,
    val isSystem: Boolean,
    val isAvailable: Boolean,
)

private data class AppLoadResult(
    val apps: List<AppEntry>,
    val applicationOptInPackages: Set<String>,
)

private data class StatusCardMessage(
    val text: String,
    val severity: CardSeverity,
)

private enum class CardSeverity {
    Info,
    Error,
}

private enum class AppOrderType {
    Label,
    PackageName,
    FirstInstallTime,
}

private const val UI_PREFERENCES_GROUP = "predictive_back_ui"
private const val UI_KEY_ORDER_TYPE = "order_type"
private const val UI_KEY_ORDER_REVERSED = "order_reversed"
private const val UI_KEY_SELECTED_FIRST = "selected_first"
private const val UI_KEY_SHOW_SYSTEM_APPS = "show_system_apps"
private const val UI_KEY_SHOW_PACKAGE_NAME = "show_package_name"
private const val APPLICATION_PREDICTIVE_BACK_ENABLE_FLAG = 1 shl 3
private const val APPLICATION_PREDICTIVE_BACK_DUMP_PREFIX =
    "enableOnBackInvokedCallback="

private val applicationPrivateFlagsExtField by lazy(LazyThreadSafetyMode.PUBLICATION) {
    runCatching { ApplicationInfo::class.java.getField("privateFlagsExt") }.getOrNull()
}

@Composable
@SuppressLint("ApplySharedPref")
private fun PredictiveBackSettingsScreen(
    service: XposedService?,
    serviceStateObserved: Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val iconSize = with(LocalDensity.current) { 48.dp.roundToPx() }
    val appsErrorMessage = stringResource(R.string.predictive_back_apps_error)
    val configurationErrorMessage = stringResource(R.string.predictive_back_config_error)
    val saveErrorMessage = stringResource(R.string.predictive_back_save_error)
    val scope = rememberCoroutineScope()
    val uiPreferences = remember(context) {
        context.getSharedPreferences(UI_PREFERENCES_GROUP, Context.MODE_PRIVATE)
    }
    val iconCache = remember {
        object : LruCache<String, ImageBitmap>(6 * 1024 * 1024) {
            override fun sizeOf(key: String, value: ImageBitmap): Int =
                value.width * value.height * 4
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var orderTypeName by rememberSaveable {
        mutableStateOf(
            uiPreferences.getString(UI_KEY_ORDER_TYPE, AppOrderType.Label.name)
                ?: AppOrderType.Label.name,
        )
    }
    var orderInReverse by rememberSaveable {
        mutableStateOf(uiPreferences.getBoolean(UI_KEY_ORDER_REVERSED, false))
    }
    var selectedFirst by rememberSaveable {
        mutableStateOf(uiPreferences.getBoolean(UI_KEY_SELECTED_FIRST, true))
    }
    var showSystemApps by rememberSaveable {
        mutableStateOf(uiPreferences.getBoolean(UI_KEY_SHOW_SYSTEM_APPS, false))
    }
    var showPackageName by rememberSaveable {
        mutableStateOf(uiPreferences.getBoolean(UI_KEY_SHOW_PACKAGE_NAME, false))
    }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var applicationOptInPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var appsLoading by remember { mutableStateOf(true) }
    var appsError by remember { mutableStateOf<String?>(null) }
    var appLoadGeneration by remember { mutableStateOf(0L) }
    var preferences by remember { mutableStateOf<SharedPreferences?>(null) }
    var selectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var configurationLoading by remember { mutableStateOf(true) }
    var configurationError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var localWriteGeneration by remember { mutableStateOf(0L) }
    val confirmedPackages = remember { AtomicReference<Set<String>>(emptySet()) }
    val writeMutex = remember(preferences) { Mutex() }
    val lazyListState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    val showFloating by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0 ||
                lazyListState.firstVisibleItemScrollOffset > 0
        }
    }
    val layoutDirection = LocalLayoutDirection.current
    val horizontalSafeInsets = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Horizontal)
        .asPaddingValues()
    val topBarBackdrop = rememberMiuixBlurBackdrop(enableBlur = true)

    LaunchedEffect(appLoadGeneration, appsErrorMessage) {
        val refreshingExistingList = apps.isNotEmpty()
        appsLoading = true
        appsError = null
        try {
            if (refreshingExistingList) {
                delay(1_500)
            }
            val loaded = withContext(Dispatchers.IO) {
                loadLaunchableApps(
                    packageManager = context.packageManager,
                    ownPackageName = context.packageName,
                )
            }
            apps = loaded.apps
            applicationOptInPackages = loaded.applicationOptInPackages
        } catch (_: Throwable) {
            appsError = appsErrorMessage
        } finally {
            appsLoading = false
        }
    }

    LaunchedEffect(service, serviceStateObserved, configurationErrorMessage) {
        preferences = null
        selectedPackages = emptySet()
        configurationError = null
        saveError = null
        localWriteGeneration = 0L
        confirmedPackages.set(emptySet())
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
                val remotePreferences = service.getRemotePreferences(PredictiveBackPreferences.GROUP)
                val packages = remotePreferences
                    .getStringSet(PredictiveBackPreferences.KEY_PACKAGES, emptySet())
                    .orEmpty()
                    .filterTo(HashSet()) { it.isNotBlank() }
                remotePreferences to packages.toSet()
            }
            preferences = loaded.first
            selectedPackages = loaded.second
            confirmedPackages.set(loaded.second)
        } catch (_: Throwable) {
            configurationError = configurationErrorMessage
        } finally {
            configurationLoading = false
        }
    }

    val orderType = remember(orderTypeName) {
        runCatching { AppOrderType.valueOf(orderTypeName) }
            .getOrDefault(AppOrderType.Label)
    }
    val visibleApps = remember(
        apps,
        applicationOptInPackages,
        selectedPackages,
        query,
        showSystemApps,
        selectedFirst,
        orderType,
        orderInReverse,
    ) {
        val entriesByPackage = LinkedHashMap<String, AppEntry>()
        apps.forEach { app ->
            if (app.packageName !in applicationOptInPackages) {
                entriesByPackage.putIfAbsent(app.packageName, app)
            }
        }
        selectedPackages.forEach { packageName ->
            if (packageName in applicationOptInPackages) {
                return@forEach
            }
            entriesByPackage.putIfAbsent(
                packageName,
                AppEntry(
                    packageName = packageName,
                    label = packageName,
                    launcherActivity = null,
                    firstInstallTime = 0L,
                    isSystem = false,
                    isAvailable = false,
                ),
            )
        }
        val filteredApps = entriesByPackage.values
            .asSequence()
            .filter { app -> showSystemApps || !app.isSystem }
            .filter { app ->
                query.isEmpty() ||
                    app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }
            .toList()

        val comparators = mutableListOf<(AppEntry) -> Comparable<*>?>()
        if (selectedFirst) {
            comparators.add { app -> if (app.packageName in selectedPackages) 0 else 1 }
        }
        val property: (AppEntry, AppOrderType) -> Comparable<*> = { app, type ->
            when (type) {
                AppOrderType.Label -> app.label
                AppOrderType.PackageName -> app.packageName
                AppOrderType.FirstInstallTime -> app.firstInstallTime
            }
        }
        comparators.add { app -> property(app, orderType) }
        AppOrderType.entries.filter { it != orderType }.forEach { fallbackType ->
            comparators.add { app -> property(app, fallbackType) }
        }
        var comparator = compareBy(*comparators.toTypedArray())
        if (orderInReverse) {
            comparator = comparator.reversed()
        }
        filteredApps.sortedWith(comparator)
    }

    val configurationEnabled = preferences != null
    val serviceLoadingMessage = stringResource(R.string.predictive_back_service_loading)
    val serviceUnavailableMessage =
        stringResource(R.string.predictive_back_service_unavailable)
    val statusMessage = when {
        configurationLoading -> StatusCardMessage(
            text = serviceLoadingMessage,
            severity = CardSeverity.Info,
        )
        configurationError != null -> StatusCardMessage(
            text = configurationError.orEmpty(),
            severity = CardSeverity.Error,
        )
        saveError != null -> StatusCardMessage(
            text = saveError.orEmpty(),
            severity = CardSeverity.Error,
        )
        serviceStateObserved && service == null -> StatusCardMessage(
            text = serviceUnavailableMessage,
            severity = CardSeverity.Error,
        )
        else -> null
    }
    val persistSelection: (Set<String>) -> Unit = { requestedPackages ->
        val activePreferences = preferences
        if (activePreferences != null) {
            val nextPackages = requestedPackages
                .filterTo(HashSet()) {
                    it.isNotBlank() && it !in applicationOptInPackages
                }
                .toSet()
            localWriteGeneration += 1L
            val writeGeneration = localWriteGeneration
            selectedPackages = nextPackages
            saveError = null
            scope.launch {
                val saved = writeMutex.withLock {
                    val fallbackPackages = confirmedPackages.get()
                    val commitSucceeded = withContext(Dispatchers.IO) {
                        val succeeded = try {
                            activePreferences.edit()
                                .putStringSet(
                                    PredictiveBackPreferences.KEY_PACKAGES,
                                    HashSet(nextPackages),
                                )
                                .commit()
                        } catch (_: Throwable) {
                            false
                        }
                        if (!succeeded) {
                            try {
                                activePreferences.edit()
                                    .putStringSet(
                                        PredictiveBackPreferences.KEY_PACKAGES,
                                        HashSet(fallbackPackages),
                                    )
                                    .commit()
                            } catch (_: Throwable) {
                                // RemotePreferences updates its local map before the Binder call,
                                // so the compensation restores the cached value even when its
                                // own remote commit fails as well.
                            }
                        }
                        succeeded
                    }
                    if (preferences === activePreferences && commitSucceeded) {
                        confirmedPackages.set(nextPackages)
                    }
                    commitSucceeded
                }
                if (preferences === activePreferences) {
                    if (writeGeneration == localWriteGeneration) {
                        if (saved) {
                            selectedPackages = nextPackages
                        } else {
                            selectedPackages = confirmedPackages.get()
                            saveError = saveErrorMessage
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(preferences, applicationOptInPackages) {
        if (preferences != null && applicationOptInPackages.isNotEmpty()) {
            val visibleSelection = selectedPackages - applicationOptInPackages
            if (visibleSelection != selectedPackages) {
                persistSelection(visibleSelection)
            }
        }
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .installerMiuixBlurEffect(topBarBackdrop)
                    .background(topBarBackdrop.getMiuixAppBarColor()),
            ) {
                TopAppBar(
                    color = Color.Transparent,
                    scrollBehavior = scrollBehavior,
                    title = stringResource(R.string.predictive_back_title),
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = MiuixIcons.Regular.Close,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                    actions = {
                        PredictiveBackTopAppBarActions(
                            orderInReverse = orderInReverse,
                            selectedFirst = selectedFirst,
                            showSystemApps = showSystemApps,
                            showPackageName = showPackageName,
                            onOrderInReverseChanged = { enabled ->
                                orderInReverse = enabled
                                uiPreferences.edit()
                                    .putBoolean(UI_KEY_ORDER_REVERSED, enabled)
                                    .apply()
                            },
                            onSelectedFirstChanged = { enabled ->
                                selectedFirst = enabled
                                uiPreferences.edit()
                                    .putBoolean(UI_KEY_SELECTED_FIRST, enabled)
                                    .apply()
                            },
                            onShowSystemAppsChanged = { enabled ->
                                showSystemApps = enabled
                                uiPreferences.edit()
                                    .putBoolean(UI_KEY_SHOW_SYSTEM_APPS, enabled)
                                    .apply()
                            },
                            onShowPackageNameChanged = { enabled ->
                                showPackageName = enabled
                                uiPreferences.edit()
                                    .putBoolean(UI_KEY_SHOW_PACKAGE_NAME, enabled)
                                    .apply()
                            },
                        )
                    },
                )
                Spacer(modifier = Modifier.size(6.dp))
                InputField(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = {},
                    expanded = false,
                    onExpandedChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 16.dp + horizontalSafeInsets.calculateStartPadding(
                                layoutDirection,
                            ),
                            end = 16.dp + horizontalSafeInsets.calculateEndPadding(
                                layoutDirection,
                            ),
                        )
                        .padding(bottom = 8.dp),
                    label = stringResource(R.string.predictive_back_search),
                )

                data class OrderData(val labelResId: Int, val type: AppOrderType)

                val orderOptions = remember {
                    listOf(
                        OrderData(R.string.sort_by_label, AppOrderType.Label),
                        OrderData(R.string.sort_by_package_name, AppOrderType.PackageName),
                        OrderData(R.string.sort_by_install_time, AppOrderType.FirstInstallTime),
                    )
                }
                val dropdownItems = orderOptions.map { stringResource(it.labelResId) }
                val selectedIndex = orderOptions.indexOfFirst { it.type == orderType }
                    .coerceAtLeast(0)
                Box(
                    modifier = Modifier
                        .padding(
                            start = 6.dp + horizontalSafeInsets.calculateStartPadding(
                                layoutDirection,
                            ),
                            end = 6.dp + horizontalSafeInsets.calculateEndPadding(
                                layoutDirection,
                            ),
                        )
                        .padding(bottom = 6.dp),
                ) {
                    MiuixDropdown(
                        items = dropdownItems,
                        selectedIndex = selectedIndex,
                        onSelectedIndexChange = { newIndex ->
                            val newOrderType = orderOptions[newIndex].type
                            orderTypeName = newOrderType.name
                            uiPreferences.edit()
                                .putString(UI_KEY_ORDER_TYPE, newOrderType.name)
                                .apply()
                        },
                    )
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = showFloating,
                enter = scaleIn(),
                exit = scaleOut(),
                modifier = Modifier.padding(bottom = 16.dp),
            ) {
                FloatingActionButton(
                    modifier = Modifier.padding(end = 16.dp),
                    containerColor = MiuixTheme.colorScheme.surface,
                    onClick = {
                        scope.launch {
                            lazyListState.animateScrollToItem(0)
                        }
                    },
                ) {
                    Icon(
                        imageVector = ArrowUpIcon,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                    )
                }
            }
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                appsLoading && apps.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            InfiniteProgressIndicator()
                            Text(
                                text = stringResource(R.string.predictive_back_loading_apps),
                                style = MiuixTheme.textStyles.main,
                            )
                        }
                    }
                }

                else -> PullToRefresh(
                    isRefreshing = appsLoading,
                    onRefresh = {
                        if (!appsLoading) {
                            appsLoading = true
                            appLoadGeneration += 1L
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    topAppBarScrollBehavior = scrollBehavior,
                    contentPadding = paddingValues,
                    refreshTexts = listOf(
                        stringResource(R.string.pull_to_refresh_hint1),
                        stringResource(R.string.pull_to_refresh_hint2),
                        stringResource(R.string.pull_to_refresh_hint3),
                        stringResource(R.string.pull_to_refresh_hint4),
                    ),
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                topBarBackdrop?.let { Modifier.layerBackdrop(it) }
                                    ?: Modifier,
                            )
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        state = lazyListState,
                        contentPadding = PaddingValues(
                            start = horizontalSafeInsets.calculateStartPadding(layoutDirection),
                            top = paddingValues.calculateTopPadding() + 8.dp,
                            end = horizontalSafeInsets.calculateEndPadding(layoutDirection),
                            bottom = paddingValues.calculateBottomPadding(),
                        ),
                        overscrollEffect = null,
                    ) {
                        item(key = "compatibility_warning") {
                            WarningCard(
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
                        if (appsError != null) {
                            item(key = "apps_error") {
                                StatusCard(
                                    message = appsError.orEmpty(),
                                    severity = CardSeverity.Error,
                                    modifier = Modifier
                                        .padding(horizontal = 12.dp)
                                        .padding(bottom = 8.dp),
                                )
                            }
                        }
                        if (visibleApps.isEmpty()) {
                            item(key = "empty") {
                                EmptyRow()
                            }
                        } else {
                            itemsIndexed(
                                items = visibleApps,
                                key = { _, app -> app.packageName },
                                contentType = { _, _ -> "app_item" },
                            ) { index, app ->
                                val cardRadius = CardDefaults.CornerRadius
                                val shape = when {
                                    visibleApps.size == 1 -> RoundedCornerShape(cardRadius)
                                    index == 0 -> RoundedCornerShape(
                                        topStart = cardRadius,
                                        topEnd = cardRadius,
                                        bottomStart = 0.dp,
                                        bottomEnd = 0.dp,
                                    )

                                    index == visibleApps.lastIndex -> RoundedCornerShape(
                                        topStart = 0.dp,
                                        topEnd = 0.dp,
                                        bottomStart = cardRadius,
                                        bottomEnd = cardRadius,
                                    )

                                    else -> RoundedCornerShape(0.dp)
                                }
                                val checked = app.packageName in selectedPackages
                                MiuixAppItem(
                                    modifier = Modifier
                                        .padding(horizontal = 12.dp)
                                        .zIndex(-index.toFloat())
                                        .animateItem(
                                            fadeInSpec = null,
                                            fadeOutSpec = null,
                                            placementSpec = spring(
                                                stiffness = Spring.StiffnessMediumLow,
                                                visibilityThreshold =
                                                    IntOffset.VisibilityThreshold,
                                            ),
                                        ),
                                    app = app,
                                    iconCache = iconCache,
                                    iconSize = iconSize,
                                    checked = checked,
                                    enabled = configurationEnabled,
                                    shape = shape,
                                    showPackageName = showPackageName,
                                    onToggle = { isChecked ->
                                        persistSelection(
                                            if (isChecked) {
                                                selectedPackages + app.packageName
                                            } else {
                                                selectedPackages - app.packageName
                                            },
                                        )
                                    },
                                    onClick = {
                                        persistSelection(
                                            if (checked) {
                                                selectedPackages - app.packageName
                                            } else {
                                                selectedPackages + app.packageName
                                            },
                                        )
                                    },
                                )
                            }
                        }
                        item(key = "navigation_bar_spacer") {
                            Spacer(modifier = Modifier.navigationBarsPadding())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WarningCard(modifier: Modifier = Modifier) {
    val infoColor = cardAccentColor(CardSeverity.Info)
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
        colors = CardDefaults.defaultColors(
            color = infoColor.copy(alpha = 0.2f),
            contentColor = infoColor,
        ),
    ) {
        Text(
            text = stringResource(R.string.predictive_back_warning_title),
            style = MiuixTheme.textStyles.title4,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.predictive_back_warning_body),
            style = MiuixTheme.textStyles.body2,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.predictive_back_restart_note),
            style = MiuixTheme.textStyles.footnote1,
        )
    }
}

@Composable
private fun StatusCard(
    message: String,
    severity: CardSeverity,
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
private fun cardAccentColor(severity: CardSeverity): Color = when (severity) {
    CardSeverity.Info -> MiuixTheme.colorScheme.primary
    CardSeverity.Error -> MiuixTheme.colorScheme.error
}

@Composable
private fun PredictiveBackTopAppBarActions(
    orderInReverse: Boolean,
    selectedFirst: Boolean,
    showSystemApps: Boolean,
    showPackageName: Boolean,
    onOrderInReverseChanged: (Boolean) -> Unit,
    onSelectedFirstChanged: (Boolean) -> Unit,
    onShowSystemAppsChanged: (Boolean) -> Unit,
    onShowPackageNameChanged: (Boolean) -> Unit,
) {
    val showMenu = remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current
    val menuOptions = remember(
        orderInReverse,
        selectedFirst,
        showSystemApps,
        showPackageName,
    ) {
        listOf(
            R.string.sort_by_reverse_order to orderInReverse,
            R.string.sort_by_selected_first to selectedFirst,
            R.string.sort_by_show_system_app to showSystemApps,
            R.string.sort_by_show_package_name to showPackageName,
        )
    }

    WindowListPopup(
        show = showMenu.value,
        popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
        alignment = PopupPositionProvider.Align.TopEnd,
        onDismissRequest = { showMenu.value = false },
    ) {
        ListPopupColumn {
            menuOptions.forEachIndexed { index, (labelResId, isSelected) ->
                DropdownImpl(
                    text = stringResource(labelResId),
                    optionSize = menuOptions.size,
                    isSelected = isSelected,
                    onSelectedIndexChange = { selectedIndex ->
                        when (selectedIndex) {
                            0 -> onOrderInReverseChanged(!orderInReverse)
                            1 -> onSelectedFirstChanged(!selectedFirst)
                            2 -> onShowSystemAppsChanged(!showSystemApps)
                            3 -> onShowPackageNameChanged(!showPackageName)
                        }
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                    },
                    index = index,
                )
            }
        }
    }

    IconButton(
        onClick = { showMenu.value = true },
        holdDownState = showMenu.value,
    ) {
        Icon(
            imageVector = MiuixIcons.Regular.More,
            tint = MiuixTheme.colorScheme.onBackground,
            contentDescription = stringResource(R.string.more_options),
        )
    }
}

// Copyright 2025, miuix-kotlin-multiplatform contributors
// SPDX-License-Identifier: Apache-2.0
@Composable
private fun MiuixDropdown(
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "No Selection",
) {
    val isDropdownExpanded = remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current
    val itemsNotEmpty = items.isNotEmpty()
    val actualEnabled = enabled && itemsNotEmpty

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = actualEnabled,
                    onClick = {
                        isDropdownExpanded.value = !isDropdownExpanded.value
                        if (isDropdownExpanded.value) {
                            hapticFeedback.performHapticFeedback(
                                HapticFeedbackType.ContextClick,
                            )
                        }
                    },
                )
                .padding(horizontal = 28.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val textColor = if (actualEnabled) {
                MiuixTheme.colorScheme.onBackgroundVariant
            } else {
                MiuixTheme.colorScheme.disabledOnSecondaryVariant
            }
            Text(
                text = if (itemsNotEmpty) items[selectedIndex] else placeholder,
                fontSize = MiuixTheme.textStyles.subtitle.fontSize,
                fontWeight = FontWeight.Bold,
                color = textColor,
            )
            val iconColor = if (actualEnabled) {
                MiuixTheme.colorScheme.onSurfaceVariantActions
            } else {
                MiuixTheme.colorScheme.disabledOnSecondaryVariant
            }
            Image(
                modifier = Modifier.size(10.dp, 16.dp),
                imageVector = MiuixIcons.Basic.ArrowUpDown,
                colorFilter = ColorFilter.tint(iconColor),
                contentDescription = stringResource(R.string.toggle_dropdown),
            )
        }

        WindowListPopup(
            show = isDropdownExpanded.value,
            alignment = PopupPositionProvider.Align.Start,
            popupPositionProvider = DropdownWithStartMarginProvider,
            onDismissRequest = { isDropdownExpanded.value = false },
        ) {
            ListPopupColumn {
                items.forEachIndexed { index, text ->
                    DropdownImpl(
                        text = text,
                        optionSize = items.size,
                        isSelected = selectedIndex == index,
                        index = index,
                        onSelectedIndexChange = { newIndex ->
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                            onSelectedIndexChange(newIndex)
                            isDropdownExpanded.value = false
                        },
                    )
                }
            }
        }
    }
}

private val DropdownWithStartMarginProvider = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowBounds: IntRect,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
        popupMargin: IntRect,
        alignment: PopupPositionProvider.Align,
    ): IntOffset {
        val offsetX = if (alignment == PopupPositionProvider.Align.End) {
            anchorBounds.right - popupContentSize.width - popupMargin.right
        } else {
            anchorBounds.left + popupMargin.left
        }
        val offsetY = if (windowBounds.bottom - anchorBounds.bottom > popupContentSize.height) {
            anchorBounds.bottom + popupMargin.bottom
        } else if (anchorBounds.top - windowBounds.top > popupContentSize.height) {
            anchorBounds.top - popupContentSize.height - popupMargin.top
        } else {
            anchorBounds.top + anchorBounds.height / 2 - popupContentSize.height / 2
        }
        return IntOffset(
            x = offsetX.coerceIn(
                windowBounds.left,
                (windowBounds.right - popupContentSize.width - popupMargin.right)
                    .coerceAtLeast(windowBounds.left),
            ),
            y = offsetY.coerceIn(
                (windowBounds.top + popupMargin.top).coerceAtMost(
                    windowBounds.bottom - popupContentSize.height - popupMargin.bottom,
                ),
                windowBounds.bottom - popupContentSize.height - popupMargin.bottom,
            ),
        )
    }

    override fun getMargins(): PaddingValues =
        PaddingValues(horizontal = 12.dp, vertical = 8.dp)
}

@Composable
private fun rememberMiuixBlurBackdrop(enableBlur: Boolean): LayerBackdrop? {
    if (!enableBlur || !isRenderEffectSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

@Composable
private fun LayerBackdrop?.getMiuixAppBarColor(): Color =
    this?.let { Color.Transparent } ?: MiuixTheme.colorScheme.surface

@Composable
private fun Modifier.installerMiuixBlurEffect(
    backdrop: LayerBackdrop?,
    enabled: Boolean = true,
    blurRadius: Float = 25f,
    shape: Shape = RectangleShape,
): Modifier {
    if (!enabled || backdrop == null) return this
    val blendColor = MiuixTheme.colorScheme.surface.copy(alpha = 0.8f)
    return then(
        Modifier.textureBlur(
            backdrop = backdrop,
            shape = shape,
            blurRadius = blurRadius,
            colors = BlurColors(
                blendColors = listOf(BlendColorEntry(color = blendColor)),
            ),
        ),
    )
}

private val ArrowUpIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ArrowUpward",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(4f, 12f)
            lineTo(5.41f, 13.41f)
            lineTo(11f, 7.83f)
            verticalLineTo(20f)
            horizontalLineTo(13f)
            verticalLineTo(7.83f)
            lineTo(18.59f, 13.41f)
            lineTo(20f, 12f)
            lineTo(12f, 4f)
            close()
        }
    }.build()
}

@Composable
private fun EmptyRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.predictive_back_no_apps),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.main,
        )
    }
}

@Composable
private fun MiuixAppItem(
    modifier: Modifier = Modifier,
    app: AppEntry,
    iconCache: LruCache<String, ImageBitmap>,
    iconSize: Int,
    checked: Boolean,
    enabled: Boolean,
    shape: Shape,
    showPackageName: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CardDefaults.defaultColors().color),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClick = onClick,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = MiuixTheme.colorScheme.primary),
                    enabled = enabled,
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppIcon(
                app = app,
                iconCache = iconCache,
                iconSize = iconSize,
                modifier = Modifier
                    .size(40.dp)
                    .align(Alignment.CenterVertically),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .weight(1f),
            ) {
                Text(
                    text = app.label,
                    style = MiuixTheme.textStyles.title4,
                )
                AnimatedVisibility(showPackageName) {
                    Text(
                        text = app.packageName,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        style = MiuixTheme.textStyles.subtitle,
                    )
                }
                if (!app.isAvailable) {
                    Text(
                        text = stringResource(R.string.predictive_back_unavailable_app),
                        color = MiuixTheme.colorScheme.error,
                        style = MiuixTheme.textStyles.footnote2,
                    )
                }
            }
            Switch(
                modifier = Modifier.align(Alignment.CenterVertically),
                checked = checked,
                onCheckedChange = onToggle,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun AppIcon(
    app: AppEntry,
    iconCache: LruCache<String, ImageBitmap>,
    iconSize: Int,
    modifier: Modifier = Modifier,
) {
    val packageManager = LocalContext.current.packageManager
    val cacheKey = remember(app.packageName, app.launcherActivity, iconSize) {
        "${app.launcherActivity?.flattenToShortString() ?: app.packageName}:$iconSize"
    }
    val icon by produceState<ImageBitmap?>(
        initialValue = iconCache.get(cacheKey),
        cacheKey,
        app.isAvailable,
    ) {
        if (value == null && app.isAvailable) {
            val loaded = withContext(Dispatchers.IO) {
                loadAppIcon(
                    packageManager = packageManager,
                    launcherActivity = app.launcherActivity,
                    packageName = app.packageName,
                    iconSize = iconSize.coerceAtLeast(1),
                )
            }
            if (loaded != null) {
                iconCache.put(cacheKey, loaded)
            }
            value = loaded
        }
    }
    val loadedIcon = icon
    if (loadedIcon != null) {
        Image(
            bitmap = loadedIcon,
            contentDescription = null,
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier)
    }
}

private fun loadLaunchableApps(
    packageManager: PackageManager,
    ownPackageName: String,
): AppLoadResult {
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolvedActivities = packageManager.queryIntentActivities(
        launcherIntent,
        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
    )
    val entries = LinkedHashMap<String, AppEntry>()
    val applicationOptInPackages = HashSet<String>()
    resolvedActivities.forEach { resolved ->
        val activityInfo = resolved.activityInfo ?: return@forEach
        val applicationInfo = activityInfo.applicationInfo ?: return@forEach
        val packageName = activityInfo.packageName ?: return@forEach
        if (packageName == ownPackageName) {
            return@forEach
        }
        if (isApplicationPredictiveBackOptedIn(applicationInfo)) {
            applicationOptInPackages.add(packageName)
            entries.remove(packageName)
            return@forEach
        }
        if (packageName in applicationOptInPackages || entries.containsKey(packageName)) {
            return@forEach
        }
        val label = runCatching { resolved.loadLabel(packageManager).toString() }
            .getOrNull()
            .orEmpty()
            .ifBlank { packageName }
        val firstInstallTime = runCatching {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0L),
            ).firstInstallTime
        }.getOrDefault(0L)
        entries[packageName] = AppEntry(
            packageName = packageName,
            label = label,
            launcherActivity = ComponentName(packageName, activityInfo.name),
            firstInstallTime = firstInstallTime,
            isSystem = applicationInfo.flags and (
                ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            ) != 0,
            isAvailable = true,
        )
    }
    return AppLoadResult(
        apps = entries.values.toList(),
        applicationOptInPackages = applicationOptInPackages.toSet(),
    )
}

private fun isApplicationPredictiveBackOptedIn(applicationInfo: ApplicationInfo): Boolean {
    val privateFlagsExt = applicationPrivateFlagsExtField?.let { field ->
        runCatching { field.getInt(applicationInfo) }.getOrNull()
    }
    if (privateFlagsExt != null) {
        return privateFlagsExt and APPLICATION_PREDICTIVE_BACK_ENABLE_FLAG != 0
    }

    var dumpResult: Boolean? = null
    runCatching {
        applicationInfo.dump(
            Printer { line ->
                if (line.startsWith(APPLICATION_PREDICTIVE_BACK_DUMP_PREFIX)) {
                    dumpResult = line
                        .removePrefix(APPLICATION_PREDICTIVE_BACK_DUMP_PREFIX)
                        .toBooleanStrictOrNull()
                }
            },
            "",
        )
    }
    return dumpResult ?: false
}

private fun loadAppIcon(
    packageManager: PackageManager,
    launcherActivity: ComponentName?,
    packageName: String,
    iconSize: Int,
): ImageBitmap? {
    val drawable = runCatching {
        launcherActivity?.let(packageManager::getActivityIcon)
            ?: packageManager.getApplicationIcon(packageName)
    }.recoverCatching {
        packageManager.getApplicationIcon(packageName)
    }.getOrNull()
    return runCatching { drawable?.toImageBitmap(iconSize) }.getOrNull()
}

private fun Drawable.toImageBitmap(size: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val previousBounds = Rect(bounds)
    try {
        setBounds(0, 0, size, size)
        draw(canvas)
    } finally {
        bounds = previousBounds
    }
    return bitmap.asImageBitmap()
}
