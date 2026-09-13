package com.operations.suite.ui

import android.content.Context
import com.operations.backupkit.AppId
import com.operations.suitekit.SuiteAppearance
import com.operations.suitekit.SuiteAppearanceCodec
import com.operations.suitekit.SuiteIconPaint
import com.operations.suitekit.SuitePalette
import com.operations.suitekit.SuitePreset
import com.operations.suitekit.SuiteWallpaper
import com.operations.suitekit.WallpaperDesign
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the suite's look actually lives: one [SuiteAppearance] document, in one preferences file,
 * shared by the Operations Sandbox and every app it hosts.
 *
 * It is a process-wide singleton on purpose. The sandbox and the hosted apps run in the same
 * process (they are library modules of :app), so a change made in settings reaches every open
 * screen through [state] with no broadcast, no restart and no re-read — the app you back out into
 * has already repainted.
 *
 * Appearance used to be LifeOps' alone; [migrateFromLifeOps] carries an existing install's preset,
 * mode and custom palette across the first time this store is read, so nobody's theme resets.
 */
class SuiteAppearanceStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())

    /** The live appearance. Collect this in Compose; every screen in the process sees each edit. */
    val state: StateFlow<SuiteAppearance> = _state.asStateFlow()

    var appearance: SuiteAppearance
        get() = _state.value
        set(value) {
            prefs.edit().putString(KEY_DOCUMENT, SuiteAppearanceCodec.toJson(value)).apply()
            _state.value = value
        }

    /** Read-modify-write the whole document; the only way anything here changes. */
    fun update(transform: (SuiteAppearance) -> SuiteAppearance) {
        appearance = transform(appearance)
    }

    var preset: SuitePreset
        get() = appearance.preset
        set(value) = update { it.copy(preset = value) }

    var darkMode: Boolean
        get() = appearance.darkMode
        set(value) = update { it.copy(darkMode = value) }

    var palette: SuitePalette
        get() = appearance.palette
        set(value) = update { it.copy(palette = value) }

    var appAccentsEnabled: Boolean
        get() = appearance.appAccentsEnabled
        set(value) = update { it.copy(appAccentsEnabled = value) }

    /** Whose colours win when an app ships icon colours and has also been repainted here. */
    var sandboxWins: Boolean
        get() = appearance.sandboxWins
        set(value) = update { it.copy(sandboxWins = value) }

    /** The launcher's backdrop. Only the sandbox home screen reads it; no hosted app does. */
    var wallpaper: SuiteWallpaper
        get() = appearance.wallpaper
        set(value) = update { it.copy(wallpaper = value) }

    /** Read-modify-write one field of the wallpaper, leaving the rest of the appearance alone. */
    fun updateWallpaper(transform: (SuiteWallpaper) -> SuiteWallpaper) =
        update { it.withWallpaper(transform) }

    /** Back to the wallpaper mixed from the suite's own preset, keeping the custom colours saved. */
    fun resetWallpaper() = updateWallpaper { it.copy(design = WallpaperDesign.THEME, dim = 0f) }

    fun setAccent(appId: AppId, hex: String) = update { it.withAccent(appId, hex) }

    fun resetAccent(appId: AppId) = update { it.withDefaultAccent(appId) }

    /** Give [appId] icon colours of its own — the customization LifeOps has always shipped with. */
    fun setIconPaint(appId: AppId, paint: SuiteIconPaint) = update { it.withIconPaint(appId, paint) }

    /** Back to the icon colours [appId] ships with, or to being tinted when it ships none. */
    fun resetIconPaint(appId: AppId) = update { it.withoutIconPaint(appId) }

    /**
     * Put every app back to its shipped identity — its colour and its icon colours alike — leaving
     * the shared preset alone.
     */
    fun resetAllAppColors() = update { it.copy(accents = emptyMap(), iconPaints = emptyMap()) }

    /** Take [appId]'s tile off the home screen, or put it back. Only the grid is affected. */
    fun setHidden(appId: AppId, hidden: Boolean) = update { it.withHidden(appId, hidden) }

    /** Swap [appId]'s tile past its nearest visible neighbour. */
    fun moveApp(appId: AppId, forward: Boolean) = update { it.withMoved(appId, forward) }

    /** Back to the grid the suite ships: every app showing, in the order it declares them. */
    fun resetHomeLayout() = update { it.copy(homeOrder = emptyList(), hiddenApps = emptySet()) }

    private fun load(): SuiteAppearance {
        SuiteAppearanceCodec.fromJson(prefs.getString(KEY_DOCUMENT, null))?.let { return it }
        val migrated = migrateFromLifeOps()
        prefs.edit().putString(KEY_DOCUMENT, SuiteAppearanceCodec.toJson(migrated)).apply()
        return migrated
    }

    /**
     * First run after the theme became suite-wide: adopt whatever LifeOps was already themed with.
     * Its preferences are read directly (same process, same app id) and left untouched — LifeOps
     * writes through this store now, so its old keys simply stop being read.
     */
    private fun migrateFromLifeOps(): SuiteAppearance {
        val lifeOps = appContext.getSharedPreferences(LIFEOPS_PREFS, Context.MODE_PRIVATE)
        if (!lifeOps.contains(LIFEOPS_KEY_PRESET) &&
            !lifeOps.contains(LIFEOPS_KEY_DARK) &&
            !lifeOps.contains(LIFEOPS_KEY_PALETTE)
        ) {
            return SuiteAppearance()
        }
        val defaults = SuiteAppearance()
        return SuiteAppearance(
            preset = SuitePreset.from(lifeOps.getString(LIFEOPS_KEY_PRESET, null)),
            darkMode = lifeOps.getBoolean(LIFEOPS_KEY_DARK, defaults.darkMode),
            palette = SuiteAppearanceCodec.paletteFromJson(lifeOps.getString(LIFEOPS_KEY_PALETTE, null))
                ?: defaults.palette
        )
    }

    companion object {
        private const val PREFS = "operations_suite_appearance"
        private const val KEY_DOCUMENT = "appearance"

        private const val LIFEOPS_PREFS = "lifeops_settings"
        private const val LIFEOPS_KEY_PRESET = "theme_preset"
        private const val LIFEOPS_KEY_DARK = "dark_mode"
        private const val LIFEOPS_KEY_PALETTE = "custom_palette"

        @Volatile
        private var instance: SuiteAppearanceStore? = null

        /** The one store for this process. Safe to call from any thread, at any time. */
        fun get(context: Context): SuiteAppearanceStore =
            instance ?: synchronized(this) {
                instance ?: SuiteAppearanceStore(context).also { instance = it }
            }
    }
}
