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
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import dev.codex.miuibackgesturehook.ModuleApplication
import dev.codex.miuibackgesturehook.PredictiveBackPreferences
import dev.codex.miuibackgesturehook.R
import io.github.libxposed.service.XposedService
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
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import dev.codex.miuibackgesturehook.util.miuixBlurEffect
import dev.codex.miuibackgesturehook.util.rememberMiuixBlurBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowListPopup
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
                PredictiveBackAppListScreen(
                    service = xposedService,
                    serviceStateObserved = serviceStateObserved,
                    appListPermissionGranted = appListPermissionGranted,
                    appListPermissionSupported = appListPermissionSupported,
                    onClose = { finish() },
                )
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
    val layoutDirection = LocalLayoutDirection.current
    val horizontalSafeInsets = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Horizontal)
        .asPaddingValues()
    val topBarBackdrop = rememberMiuixBlurBackdrop()

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

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .miuixBlurEffect(topBarBackdrop)
                    .background(Color.Transparent),
            ) {
                TopAppBar(
                    color = Color.Transparent,
                    scrollBehavior = scrollBehavior,
                    title = stringResource(R.string.predictive_back_apps_title),
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = MiuixIcons.Regular.Back,
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
                Card(
                    modifier = Modifier
                        .padding(
                            start = 12.dp + horizontalSafeInsets.calculateStartPadding(
                                layoutDirection,
                            ),
                            end = 12.dp + horizontalSafeInsets.calculateEndPadding(
                                layoutDirection,
                            ),
                        )
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
                        .fillMaxWidth()
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
        OverlayDialog(
            show = showCompatibilityDialog,
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
                TextButton(
                    text = stringResource(R.string.close),
                    onClick = { showCompatibilityDialog = false },
                    modifier = Modifier.fillMaxWidth(),
                )
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
                        top = paddingValues.calculateTopPadding() + 8.dp,
                        end = horizontalSafeInsets.calculateEndPadding(layoutDirection),
                        bottom = paddingValues.calculateBottomPadding(),
                    ),
                    overscrollEffect = null,
                ) {
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
) {
    val isDropdownExpanded = remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
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
            Text(
                text = items[selectedIndex],
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

private val DropdownWithStartMarginProvider =
    ListPopupDefaults.dropdownPositionProvider(12.dp, 8.dp)

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
