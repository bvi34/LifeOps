package com.operations.sandbox

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.operations.backupkit.AppId
import com.operations.sandbox.ui.BackupController
import com.operations.sandbox.ui.CloudBackupController
import com.operations.sandbox.ui.SandboxHomeScreen
import com.operations.sandbox.ui.SandboxSettingsScreen
import com.operations.sandbox.ui.SettingsTab
import com.operations.sandbox.ui.UpdateController
import com.operations.sandbox.ui.WeatherWidgetController
import com.operations.sandbox.ui.rememberBackupController
import com.operations.sandbox.ui.rememberCloudBackupController
import com.operations.sandbox.ui.rememberUpdateController
import com.operations.sandbox.ui.rememberWeatherWidgetController
import com.operations.sandbox.shortcuts.SuiteShortcuts
import com.operations.sandbox.ui.theme.SandboxTheme
import com.operations.suite.ui.SuiteAppearanceStore
import com.operations.suite.ui.SuiteNotifications

/**
 * The Operations Sandbox: the suite's single launcher entry point, and the only screen that is
 * about the *collection* rather than about one app.
 *
 * It opens on a phone-style home screen — a tile per hosted app, in that app's colour and glyph —
 * with a dock holding the two things the container owns: the settings that paint every app, and the
 * cross-app backup. The suite is sideloaded, so the shell also owns keeping itself current: it asks
 * GitHub at launch whether a newer release exists and, if so, says so on the home screen.
 *
 * There are exactly two destinations here, so this is a `when` over one route rather than a
 * navigation graph; the settings screen's own state (which tab, which app is being recoloured) is
 * hoisted here so returning to it is predictable.
 */
class SandboxActivity : ComponentActivity() {

    /**
     * The suite's notification permission, asked for here and nowhere else.
     *
     * Nothing is done with the answer. A refusal is not an error and there is nothing on a home
     * screen it should change — the apps that schedule reminders are the ones that can say what a
     * refusal costs, on the screen where the reminder is being set up. What matters is that the
     * question is *asked*, once, of everybody: it is granted to the package rather than to an app,
     * and until now only LifeOps ever asked, so a household that lived in Health had medication
     * reminders that were posted and never delivered. See [SuiteNotifications].
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * The app a launcher shortcut asked for, or null on an ordinary launch. Consumed by the shell
     * once it has opened it — the same shape LifeOps' own deep links use, and for the same reason:
     * an intent acted on twice opens an app the household has already backed out of.
     */
    private var requestedApp by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedApp = intent?.getStringExtra(SuiteShortcuts.EXTRA_OPEN_APP)
        // The archive records the build that wrote it; now that the version is the release tag
        // rather than a hardcoded "1.0", a restore can say what it came from.
        val center = BackupCenter(this, sandboxVersion = BuildConfig.VERSION_NAME)
        setContent {
            SandboxTheme {
                SandboxShell(
                    center = center,
                    requestedApp = requestedApp,
                    onRequestConsumed = { requestedApp = null }
                )
            }
        }
        askAboutNotificationsOnce()
        // Only on a real launch, not on a rotation: republishing costs four icons being drawn, and
        // a configuration change has not changed which apps the household has or what colour
        // they are.
        if (savedInstanceState == null) SuiteShortcuts.publish(this)
    }

    /**
     * A shortcut tapped while the suite is already running arrives here rather than through
     * `onCreate`, because the shortcut's own `CLEAR_TASK` reuses the task it is clearing.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(SuiteShortcuts.EXTRA_OPEN_APP)?.let { requestedApp = it }
    }

    /**
     * Marked as asked *before* the dialog goes up rather than in the result callback: the callback
     * arrives whenever the household gets round to answering, and an activity that was recreated in
     * the meantime would ask a second time on the way back.
     */
    private fun askAboutNotificationsOnce() {
        if (!SuiteNotifications.shouldAsk(this)) return
        SuiteNotifications.markAsked(this)
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private const val ROUTE_HOME = "home"
private const val ROUTE_SETTINGS = "settings"

@Composable
private fun SandboxShell(
    center: BackupCenter,
    requestedApp: String? = null,
    onRequestConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    // The scheduled upload's settings are read first because the Backups tab's ticks start from
    // them: the apps a scheduled archive includes are the apps the tab shows ticked, so the two
    // cannot drift apart across a relaunch.
    val cloud: CloudBackupController = rememberCloudBackupController()
    val backup: BackupController = rememberBackupController(center, cloud.includedApps)
    // Hoisted for the same reason the backup controller is: leaving the home screen for Settings
    // shouldn't cancel a location fix or a forecast fetch that's already in flight. Null only if
    // LifeOps somehow isn't installed in this process, in which case the home screen omits the tile.
    val weather: WeatherWidgetController? = rememberWeatherWidgetController()
    // Hoisted for the strongest version of the same reason: a download is tens of megabytes and
    // must not be cancelled by walking from Settings back to the home screen.
    val updates: UpdateController = rememberUpdateController()

    // The one thing the shell does on the network by itself, and only if the switch is on and the
    // six-hour throttle has expired. Keyed on the controller so it runs once per process, not once
    // per recomposition: a check on every navigation would be both wasteful and rate-limited.
    LaunchedEffect(updates) { updates.checkOnLaunch() }

    // A shortcut asked for an app. Opening it from here rather than from `onCreate` means the home
    // screen is underneath it, so backing out of the app lands where backing out always lands.
    LaunchedEffect(requestedApp) {
        requestedApp?.let { key ->
            AppId.fromKey(key)?.let { openApp(context, it) }
            onRequestConsumed()
        }
    }

    // What the phone's own launcher offers when the suite's icon is long-pressed. Keyed on the
    // arrangement rather than on the whole appearance: hiding an app or moving it changes the list,
    // and re-rendering four icons on every keystroke in a colour field would not.
    val appearance by SuiteAppearanceStore.get(context).state.collectAsState()
    val arrangement = appearance.homeApps.joinToString(",") { it.key }
    LaunchedEffect(arrangement) { SuiteShortcuts.publish(context) }

    var route by rememberSaveable { mutableStateOf(ROUTE_HOME) }
    var tabName by rememberSaveable { mutableStateOf(SettingsTab.APPEARANCE.name) }
    // The app whose colour the settings should open on — set by a long-press on its home tile.
    var focusedAppKey by rememberSaveable { mutableStateOf<String?>(null) }

    fun openSettings(tab: SettingsTab, appKey: String? = null) {
        tabName = tab.name
        focusedAppKey = appKey
        route = ROUTE_SETTINGS
    }

    BackHandler(enabled = route != ROUTE_HOME) { route = ROUTE_HOME }

    when (route) {
        ROUTE_SETTINGS -> SandboxSettingsScreen(
            backup = backup,
            cloud = cloud,
            updates = updates,
            tab = SettingsTab.entries.firstOrNull { it.name == tabName } ?: SettingsTab.APPEARANCE,
            onTabChange = { tabName = it.name },
            focusedApp = focusedAppKey?.let { AppId.fromKey(it) },
            onBack = { route = ROUTE_HOME }
        )

        else -> SandboxHomeScreen(
            weather = weather,
            onOpenApp = { appId -> openApp(context, appId) },
            onOpenSettings = { openSettings(SettingsTab.APPEARANCE) },
            onOpenBackups = { openSettings(SettingsTab.BACKUPS) },
            onCustomizeApp = { appId -> openSettings(SettingsTab.APPEARANCE, appId.key) },
            onOpenWeather = { openWeather(context) },
            availableUpdateTag = updates.pendingRelease?.tag,
            onOpenUpdates = { openSettings(SettingsTab.UPDATES) }
        )
    }
}

/**
 * The weather tile's tap target: LifeOps' own weather screen, which is where the hourly strip,
 * radar, alerts and the outdoor-task windows live. The tile is one glance; this is the whole thing.
 */
private fun openWeather(context: Context) {
    context.startActivity(
        Intent(context, com.lifeops.app.MainActivity::class.java)
            .putExtra(
                com.lifeops.app.MainActivity.EXTRA_OPEN_DESTINATION,
                com.lifeops.app.MainActivity.DEST_WEATHER
            )
    )
}

/**
 * Open a hosted app's UI in this same process by launching its (now non-launcher) activity.
 *
 * Every open is recorded, because "recently opened" is what the phone's launcher offers when the
 * suite's icon is long-pressed, and this is the one place an app is opened from.
 */
private fun openApp(context: Context, appId: AppId) {
    SuiteShortcuts.recordOpened(context, appId)
    val target = when (appId) {
        AppId.LIFEOPS -> com.lifeops.app.MainActivity::class.java
        AppId.CITATION -> com.citation.app.MainActivity::class.java
        AppId.LOGISTICS -> com.logistics.app.MainActivity::class.java
        AppId.ADVISOR -> com.advisor.app.MainActivity::class.java
        AppId.HEALTH -> com.health.app.MainActivity::class.java
        AppId.PEOPLE -> com.people.app.MainActivity::class.java
        AppId.PROJECT -> com.project.app.MainActivity::class.java
        AppId.MAINTENANCE -> com.maintenance.app.MainActivity::class.java
        AppId.FINANCE -> com.finance.app.MainActivity::class.java
        AppId.REPOSITORY -> com.repository.app.MainActivity::class.java
        AppId.SECRETS -> com.secrets.app.MainActivity::class.java
        AppId.UTILITIES -> com.utilities.app.MainActivity::class.java
    }
    context.startActivity(Intent(context, target))
}
