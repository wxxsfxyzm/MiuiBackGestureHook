// SPDX-License-Identifier: Apache-2.0
// UI structure adapted with permission from InstallerX Revived's MiuixApplyPage.
// Copyright (C) 2025-2026 InstallerX Revived contributors.
package dev.codex.miuibackgesturehook.activity

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import dev.codex.miuibackgesturehook.ModuleApplication
import dev.codex.miuibackgesturehook.PredictiveBackPreferences
import dev.codex.miuibackgesturehook.R
import dev.codex.miuibackgesturehook.util.rememberMiuixBlurBackdrop
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassDropdownPopup
import top.yukonga.miuix.kmp.glass.GlassFloatingActionButton
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassIconDropdownMenu
import top.yukonga.miuix.kmp.glass.GlassPopupAnchor
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.glass.GlassSearchOverlayDefaults
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.glass.glass
import top.yukonga.miuix.kmp.glass.glassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPopupAnchorContent
import top.yukonga.miuix.kmp.glass.glassPopupAnchorValue
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.icon.glass.More
import top.yukonga.miuix.kmp.icon.glass.Search
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.material.GlassStyle
import top.yukonga.miuix.kmp.material.drawGlassShadow
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import kotlin.time.Duration.Companion.milliseconds

class PredictiveBackAppListActivity :
    ComponentActivity(),
    ModuleApplication.ServiceStateListener {
    private var appListPermissionGranted by mutableStateOf(true)
    private var appListPermissionSupported by mutableStateOf(false)
    private val appListPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        appListPermissionGranted = granted
    }
    private var xposedService: XposedService? by mutableStateOf(null)
    private var serviceStateObserved by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appListPermissionSupported = supportsMiuiAppListPermission()
        appListPermissionGranted = !appListPermissionSupported ||
                checkSelfPermission(GET_INSTALLED_APPS_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
        enableEdgeToEdge()
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MiuixTheme(colors = colors) {
                // Root dialog host, kept outside the page's backdrop recording so glass
                // dialogs sample the page without sampling themselves.
                Scaffold(contentWindowInsets = WindowInsets(0)) {
                    PredictiveBackAppListScreen(
                        service = xposedService,
                        serviceStateObserved = serviceStateObserved,
                        appListPermissionGranted = appListPermissionGranted,
                        appListPermissionSupported = appListPermissionSupported,
                        onClose = { finish() },
                    )
                }
            }
        }
        if (appListPermissionSupported && !appListPermissionGranted) {
            window.decorView.post {
                appListPermissionLauncher.launch(GET_INSTALLED_APPS_PERMISSION)
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

private data class AppListStatusCardMessage(
    val text: String,
    val severity: AppListCardSeverity,
)

private enum class AppListCardSeverity {
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
private const val GET_INSTALLED_APPS_PERMISSION =
    "com.android.permission.GET_INSTALLED_APPS"
private const val APPLICATION_PREDICTIVE_BACK_ENABLE_FLAG = 1 shl 3
private const val APPLICATION_PREDICTIVE_BACK_DUMP_PREFIX =
    "enableOnBackInvokedCallback="

private val SearchListTopGap = 8.dp

private data class OrderOption(val labelResId: Int, val type: AppOrderType)

private val orderOptions = listOf(
    OrderOption(R.string.sort_by_label, AppOrderType.Label),
    OrderOption(R.string.sort_by_package_name, AppOrderType.PackageName),
    OrderOption(R.string.sort_by_install_time, AppOrderType.FirstInstallTime),
)

private val applicationPrivateFlagsExtField by lazy(LazyThreadSafetyMode.PUBLICATION) {
    runCatching { ApplicationInfo::class.java.getField("privateFlagsExt") }.getOrNull()
}

private fun ComponentActivity.supportsMiuiAppListPermission(): Boolean =
    runCatching {
        packageManager.getPermissionInfo(GET_INSTALLED_APPS_PERMISSION, 0).packageName ==
                "com.lbe.security.miui"
    }.getOrDefault(false)

@Composable
@SuppressLint("ApplySharedPref")
private fun PredictiveBackAppListScreen(
    service: XposedService?,
    serviceStateObserved: Boolean,
    appListPermissionGranted: Boolean,
    appListPermissionSupported: Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val iconSize = with(LocalDensity.current) { 48.dp.roundToPx() }
    val appsErrorMessage = stringResource(R.string.predictive_back_apps_error)
    val appListPermissionRequiredMessage =
        stringResource(R.string.predictive_back_apps_permission_required)
    val configurationErrorMessage = stringResource(R.string.predictive_back_config_error)
    val saveErrorMessage = stringResource(R.string.predictive_back_save_error)
    val serviceLoadingMessage = stringResource(R.string.predictive_back_service_loading)
    val serviceUnavailableMessage =
        stringResource(R.string.predictive_back_service_unavailable)
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
    var showCompatibilityDialog by rememberSaveable { mutableStateOf(false) }
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
    var appLoadGeneration by remember { mutableLongStateOf(0L) }
    var preferences by remember { mutableStateOf<SharedPreferences?>(null) }
    var selectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var configurationLoading by remember { mutableStateOf(true) }
    var configurationError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var localWriteGeneration by remember { mutableLongStateOf(0L) }
    val writeMutex = remember(preferences) { Mutex() }
    val lazyListState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    val showFloating by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0 ||
                    lazyListState.firstVisibleItemScrollOffset > 0
        }
    }
    val isContentScrolled by remember { derivedStateOf { lazyListState.canScrollBackward } }
    // The list starts SearchListTopGap below the bar, so the search pill is only overlapped
    // past that gap.
    val searchGapPx = with(LocalDensity.current) { SearchListTopGap.toPx() }
    val searchHasOverlap by remember(searchGapPx) {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0 ||
                    lazyListState.firstVisibleItemScrollOffset > searchGapPx
        }
    }
    val hapticFeedback = LocalHapticFeedback.current
    val glassStyle = GlassDefaults.style(isSystemInDarkTheme())
    val layoutDirection = LocalLayoutDirection.current
    val horizontalSafeInsets = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Horizontal)
        .asPaddingValues()
    val topBarBackdrop = rememberMiuixBlurBackdrop()
    // Popups sample the whole page, bars included, through a separate backdrop.
    val menuBackdrop = rememberMiuixBlurBackdrop()
    val moreMenuAnchor = rememberGlassPopupAnchor()
    var showMoreMenu by remember { mutableStateOf(false) }
    val orderMenuAnchor = rememberGlassPopupAnchor()
    var orderSelectorBounds by remember { mutableStateOf(Rect.Zero) }
    var showOrderMenu by remember { mutableStateOf(false) }

    LaunchedEffect(
        appLoadGeneration,
        appsErrorMessage,
        appListPermissionGranted,
        appListPermissionSupported,
    ) {
        if (!appListPermissionGranted) {
            apps = emptyList()
            applicationOptInPackages = emptySet()
            appsLoading = false
            appsError = if (appListPermissionSupported) {
                appListPermissionRequiredMessage
            } else {
                appsErrorMessage
            }
            return@LaunchedEffect
        }
        val refreshingExistingList = apps.isNotEmpty()
        appsLoading = true
        appsError = null
        try {
            if (refreshingExistingList) {
                delay(1_500.milliseconds)
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
        confirmedPackages = emptySet()
        configurationError = null
        saveError = null
        localWriteGeneration = 0L
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
                val packages = remotePreferences
                    .getStringSet(PredictiveBackPreferences.KEY_PACKAGES, emptySet())
                    .orEmpty()
                    .filterTo(HashSet()) { it.isNotBlank() }
                    .toSet()
                remotePreferences to packages
            }
            preferences = loaded.first
            selectedPackages = loaded.second
            confirmedPackages = loaded.second
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
            if (packageName !in applicationOptInPackages) {
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
                    val fallbackPackages = confirmedPackages
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
                                // Restore the RemotePreferences cache where possible.
                            }
                        }
                        succeeded
                    }
                    if (preferences === activePreferences && commitSucceeded) {
                        confirmedPackages = nextPackages
                    }
                    commitSucceeded
                }
                if (preferences === activePreferences &&
                    writeGeneration == localWriteGeneration
                ) {
                    if (saved) {
                        selectedPackages = nextPackages
                    } else {
                        selectedPackages = confirmedPackages
                        saveError = saveErrorMessage
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

    val configurationEnabled = preferences != null
    val statusMessage = when {
        configurationLoading -> AppListStatusCardMessage(
            text = serviceLoadingMessage,
            severity = AppListCardSeverity.Info,
        )

        configurationError != null -> AppListStatusCardMessage(
            text = configurationError.orEmpty(),
            severity = AppListCardSeverity.Error,
        )

        saveError != null -> AppListStatusCardMessage(
            text = saveError.orEmpty(),
            severity = AppListCardSeverity.Error,
        )

        serviceStateObserved && service == null -> AppListStatusCardMessage(
            text = serviceUnavailableMessage,
            severity = AppListCardSeverity.Error,
        )

        else -> null
    }

    val orderItems = orderOptions.map { stringResource(it.labelResId) }
    val selectedOrderIndex = orderOptions.indexOfFirst { it.type == orderType }
        .coerceAtLeast(0)
    val onOrderIndexChange: (Int) -> Unit = { newIndex ->
        val newOrderType = orderOptions[newIndex].type
        orderTypeName = newOrderType.name
        uiPreferences.edit()
            .putString(UI_KEY_ORDER_TYPE, newOrderType.name)
            .apply()
    }
    val menuEntries = rememberPredictiveBackMenuEntries(
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

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(menuBackdrop),
            topBar = {
                GlassTopAppBar(
                    backdrop = topBarBackdrop,
                    isContentScrolled = isContentScrolled,
                    progressiveBlur = true,
                    style = glassStyle,
                    scrollBehavior = scrollBehavior,
                    title = stringResource(R.string.predictive_back_apps_title),
                    navigationIcon = {
                        GlassIconButton(onClick = onClose) {
                            Icon(
                                imageVector = MiuixIcons.Glass.ChevronBackward,
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                    actions = {
                        GlassIconButton(
                            onClick = { showMoreMenu = !showMoreMenu },
                            modifier = Modifier.glassPopupAnchor(
                                anchor = moreMenuAnchor,
                                cornerRadius = GlassTopAppBarDefaults.ButtonSize / 2,
                                floating = isContentScrolled,
                            ),
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Glass.More,
                                contentDescription = stringResource(R.string.more_options),
                                modifier = Modifier
                                    .size(24.dp)
                                    .glassPopupAnchorContent(moreMenuAnchor),
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    },
                    bottomContent = {
                        GlassSearchBar(
                            query = query,
                            onQueryChange = { query = it },
                            label = stringResource(R.string.predictive_back_search),
                            backdrop = topBarBackdrop,
                            hasOverlap = searchHasOverlap,
                            style = glassStyle,
                            modifier = Modifier
                                .padding(
                                    start = GlassSearchOverlayDefaults.HorizontalPadding +
                                            horizontalSafeInsets.calculateStartPadding(
                                                layoutDirection,
                                            ),
                                    end = GlassSearchOverlayDefaults.HorizontalPadding +
                                            horizontalSafeInsets.calculateEndPadding(
                                                layoutDirection,
                                            ),
                                )
                                .padding(top = 6.dp, bottom = 8.dp),
                        )
                    },
                )
            },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = showFloating,
                    enter = scaleIn(),
                    exit = scaleOut(),
                    modifier = Modifier.padding(bottom = 16.dp),
                ) {
                    GlassFloatingActionButton(
                        modifier = Modifier.padding(end = 16.dp),
                        onClick = {
                            scope.launch {
                                lazyListState.animateScrollToItem(0)
                            }
                        },
                        backdrop = topBarBackdrop,
                        style = glassStyle,
                    ) {
                        Icon(
                            imageVector = ArrowUpIcon,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onPrimary,
                        )
                    }
                }
            },
        ) { paddingValues ->
            // Hosted by the activity's root Scaffold, outside the menuBackdrop recording.
            GlassDialog(
                show = showCompatibilityDialog,
                backdrop = menuBackdrop,
                title = stringResource(R.string.predictive_back_warning_title),
                onDismissRequest = { showCompatibilityDialog = false },
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.predictive_back_warning_body),
                        style = MiuixTheme.textStyles.body2,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.predictive_back_restart_note),
                        style = MiuixTheme.textStyles.body2,
                    )
                    Spacer(Modifier.height(16.dp))
                    GlassDialogButton(
                        onClick = { showCompatibilityDialog = false },
                        modifier = Modifier.fillMaxWidth(),
                        role = GlassDialogButtonRole.Primary,
                    ) {
                        Text(
                            text = stringResource(R.string.close),
                            style = MiuixTheme.textStyles.button,
                        )
                    }
                }
            }
            if (appsLoading && apps.isEmpty()) {
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
            } else {
                PullToRefresh(
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
                            .consumeWindowInsets(paddingValues)
                            .layerBackdrop(topBarBackdrop)
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        state = lazyListState,
                        contentPadding = PaddingValues(
                            start = horizontalSafeInsets.calculateStartPadding(layoutDirection),
                            top = paddingValues.calculateTopPadding() + SearchListTopGap,
                            end = horizontalSafeInsets.calculateEndPadding(layoutDirection),
                            bottom = paddingValues.calculateBottomPadding(),
                        ),
                        overscrollEffect = null,
                    ) {
                        // The compatibility entry and order selector scroll with the list;
                        // only the search pill stays pinned in the bar.
                        item(key = "compatibility_entry") {
                            Card(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 8.dp),
                                insideMargin = PaddingValues(0.dp),
                            ) {
                                ArrowPreference(
                                    title = stringResource(
                                        R.string.predictive_back_compatibility_entry,
                                    ),
                                    onClick = { showCompatibilityDialog = true },
                                )
                            }
                        }
                        item(key = "order_selector", contentType = "order_selector") {
                            GlassOrderSelector(
                                text = orderItems[selectedOrderIndex],
                                anchor = orderMenuAnchor,
                                onClick = {
                                    if (!showOrderMenu) {
                                        hapticFeedback.performHapticFeedback(
                                            HapticFeedbackType.ContextClick,
                                        )
                                    }
                                    showOrderMenu = !showOrderMenu
                                },
                                onBoundsChange = { orderSelectorBounds = it },
                                modifier = Modifier
                                    .padding(horizontal = 6.dp)
                                    .padding(bottom = 6.dp),
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
                                    severity = AppListCardSeverity.Error,
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
                                )
                            }
                        }
                    }
                }
            }
        }
        // Popups sample the whole page, bars included, so they are hosted outside the Scaffold.
        val glassVisuals = GlassPopupDefaults.visuals(style = glassStyle)
        GlassDropdownPopup(
            show = showOrderMenu,
            onDismissRequest = { showOrderMenu = false },
            anchorBounds = orderSelectorBounds,
            backdrop = menuBackdrop,
            anchor = orderMenuAnchor,
            visuals = glassVisuals,
        ) {
            orderItems.forEachIndexed { index, text ->
                GlassPopupItem(
                    text = text,
                    selected = index == selectedOrderIndex,
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        showOrderMenu = false
                        onOrderIndexChange(index)
                    },
                )
            }
        }
        GlassIconDropdownMenu(
            show = showMoreMenu,
            onDismissRequest = { showMoreMenu = false },
            anchor = moreMenuAnchor,
            anchorContent = {
                Icon(
                    imageVector = MiuixIcons.Glass.More,
                    contentDescription = stringResource(R.string.more_options),
                    modifier = Modifier.size(24.dp),
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            },
            entries = menuEntries,
            backdrop = menuBackdrop,
            visuals = glassVisuals,
            collapseOnSelection = false,
        )
    }
}

@Composable
private fun StatusCard(
    message: String,
    severity: AppListCardSeverity,
    modifier: Modifier = Modifier,
) {
    val accentColor = when (severity) {
        AppListCardSeverity.Info -> MiuixTheme.colorScheme.primary
        AppListCardSeverity.Error -> MiuixTheme.colorScheme.error
    }
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
private fun rememberPredictiveBackMenuEntries(
    orderInReverse: Boolean,
    selectedFirst: Boolean,
    showSystemApps: Boolean,
    showPackageName: Boolean,
    onOrderInReverseChanged: (Boolean) -> Unit,
    onSelectedFirstChanged: (Boolean) -> Unit,
    onShowSystemAppsChanged: (Boolean) -> Unit,
    onShowPackageNameChanged: (Boolean) -> Unit,
): List<DropdownEntry> {
    val reverseText = stringResource(R.string.sort_by_reverse_order)
    val selectedFirstText = stringResource(R.string.sort_by_selected_first)
    val systemAppText = stringResource(R.string.sort_by_show_system_app)
    val packageNameText = stringResource(R.string.sort_by_show_package_name)
    return remember(
        orderInReverse,
        selectedFirst,
        showSystemApps,
        showPackageName,
        reverseText,
        selectedFirstText,
        systemAppText,
        packageNameText,
    ) {
        listOf(
            DropdownEntry(
                items = listOf(
                    DropdownItem(
                        text = reverseText,
                        selected = orderInReverse,
                        onClick = { onOrderInReverseChanged(!orderInReverse) },
                    ),
                    DropdownItem(
                        text = selectedFirstText,
                        selected = selectedFirst,
                        onClick = { onSelectedFirstChanged(!selectedFirst) },
                    ),
                    DropdownItem(
                        text = systemAppText,
                        selected = showSystemApps,
                        onClick = { onShowSystemAppsChanged(!showSystemApps) },
                    ),
                    DropdownItem(
                        text = packageNameText,
                        selected = showPackageName,
                        onClick = { onShowPackageNameChanged(!showPackageName) },
                    ),
                ),
            ),
        )
    }
}

/**
 * Inline search pill mirroring the [top.yukonga.miuix.kmp.glass.GlassSearchOverlay] pill,
 * including its solid-to-glass transition once list content scrolls under it, but filtering
 * the page list in place.
 */
@Composable
private fun GlassSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    label: String,
    backdrop: LayerBackdrop,
    hasOverlap: Boolean,
    style: GlassStyle,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val mutedColor = if (MiuixTheme.isDynamicColor) {
        colors.onSurfaceVariantSummary
    } else {
        colors.disabledOnSurface
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = remember { GlassShape(GlassSearchOverlayDefaults.BarHeight / 2) }
    val material = GlassSearchOverlayDefaults.material()
    val stroke = GlassSearchOverlayDefaults.stroke()
    val shadow = GlassSearchOverlayDefaults.shadow()
    val materialProgress by animateFloatAsState(
        targetValue = if (hasOverlap) 1f else 0f,
        animationSpec = tween(450, easing = LinearEasing),
        label = "appListSearchPillMaterial",
    )

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .height(GlassSearchOverlayDefaults.BarHeight),
        singleLine = true,
        textStyle = MiuixTheme.textStyles.body1.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        decorationBox = { field ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind { drawGlassShadow(shape, shadow, materialProgress) }
                    .background(colors.surfaceContainerHigh, CircleShape),
            ) {
                if (materialProgress > 0.001f) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer { alpha = materialProgress }
                            .glass(
                                backdrop = backdrop,
                                shape = shape,
                                style = style,
                                material = material,
                                stroke = stroke,
                                shading = false,
                            ),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Glass.Search,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = mutedColor,
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                text = label,
                                color = mutedColor,
                                style = MiuixTheme.textStyles.body1,
                            )
                        }
                        field()
                    }
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Glass.Close,
                                contentDescription = stringResource(R.string.clear_search),
                                modifier = Modifier.size(16.dp * (7f / 6f)),
                                tint = mutedColor,
                            )
                        }
                    }
                }
            }
        },
    )
}

// Copyright 2025, miuix-kotlin-multiplatform contributors
// SPDX-License-Identifier: Apache-2.0
/** Order selector with the classic dropdown look, anchoring a glass dropdown to its value. */
@Composable
private fun GlassOrderSelector(
    text: String,
    anchor: GlassPopupAnchor,
    onClick: () -> Unit,
    onBoundsChange: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 28.dp, vertical = 8.dp)
                .onGloballyPositioned { onBoundsChange(it.boundsInWindow()) }
                .glassPopupAnchorValue(anchor),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = text,
                fontSize = MiuixTheme.textStyles.subtitle.fontSize,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
            Image(
                modifier = Modifier.size(10.dp, 16.dp),
                imageVector = MiuixIcons.Basic.ArrowUpDown,
                colorFilter = ColorFilter.tint(
                    MiuixTheme.colorScheme.onSurfaceVariantActions,
                ),
                contentDescription = stringResource(R.string.toggle_dropdown),
            )
        }
    }
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
                    enabled = enabled,
                    onClick = { onToggle(!checked) },
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
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions
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
    // Three package-manager queries in total; per-package lookups cost several Binder calls
    // for every installed package.
    val installedPackages = packageManager.getInstalledPackages(
        PackageManager.PackageInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
    )
    val launcherActivities = resolveMainActivities(packageManager, launcherOnly = true)
    val mainActivities = resolveMainActivities(packageManager, launcherOnly = false)
    val entries = LinkedHashMap<String, AppEntry>()
    val applicationOptInPackages = HashSet<String>()
    installedPackages.forEach { packageInfo ->
        val applicationInfo = packageInfo.applicationInfo ?: return@forEach
        val packageName = applicationInfo.packageName ?: return@forEach
        if (packageName == ownPackageName) {
            return@forEach
        }
        if (packageName in applicationOptInPackages || entries.containsKey(packageName)) {
            return@forEach
        }
        val launcherActivity = launcherActivities[packageName]
        if (launcherActivity != null && isApplicationPredictiveBackOptedIn(applicationInfo)) {
            applicationOptInPackages.add(packageName)
            return@forEach
        }

        val selectedActivity = launcherActivity ?: mainActivities[packageName]
            ?: return@forEach
        val label = runCatching { applicationInfo.loadLabel(packageManager).toString() }
            .getOrNull()
            .orEmpty()
            .ifBlank { packageName }
        val firstInstallTime = packageInfo.firstInstallTime
        entries[packageName] = AppEntry(
            packageName = packageName,
            label = label,
            launcherActivity = selectedActivity.let {
                ComponentName(packageName, it.name)
            },
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

/** First matching MAIN activity per package, in the resolver's result order. */
private fun resolveMainActivities(
    packageManager: PackageManager,
    launcherOnly: Boolean,
): Map<String, android.content.pm.ActivityInfo> {
    val intent = Intent(Intent.ACTION_MAIN)
    if (launcherOnly) {
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
    }
    val activities = HashMap<String, android.content.pm.ActivityInfo>()
    packageManager.queryIntentActivities(
        intent,
        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
    ).forEach { resolveInfo ->
        val activityInfo = resolveInfo.activityInfo ?: return@forEach
        activities.putIfAbsent(activityInfo.packageName, activityInfo)
    }
    return activities
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
            { line ->
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
    return runCatching {
        drawable?.toBitmap(iconSize, iconSize)?.asImageBitmap()
    }.getOrNull()
}
