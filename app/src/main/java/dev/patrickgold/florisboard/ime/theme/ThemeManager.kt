/*
 * Copyright (C) 2020 Patrick Goldinger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.theme

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import com.github.michaelbull.result.*
import dev.patrickgold.florisboard.ime.core.PrefHelper
import dev.patrickgold.florisboard.ime.extension.AssetManager
import dev.patrickgold.florisboard.ime.extension.AssetRef
import dev.patrickgold.florisboard.ime.extension.AssetSource
import dev.patrickgold.florisboard.util.TimeUtil
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Core class which manages the keyboard theme. Note, that this does not affect the UI theme of the
 * Settings Activities.
 */
class ThemeManager private constructor(
    private val applicationContext: Context,
    private val assetManager: AssetManager,
    private val prefs: PrefHelper
) {
    private val callbackReceivers: CopyOnWriteArrayList<OnThemeUpdatedListener> = CopyOnWriteArrayList()
    private val packageManager: PackageManager? = applicationContext.packageManager

    var activeTheme: Theme = Theme.empty()
        private set
    var indexedDayThemeRefs: MutableMap<AssetRef, ThemeMetaOnly> = mutableMapOf()
    var indexedNightThemeRefs: MutableMap<AssetRef, ThemeMetaOnly> = mutableMapOf()
    var isAdaptiveThemeEnabled: Boolean = false
        private set

    var remoteColorPrimary: ThemeValue.SolidColor? = null
        private set
    var remoteColorPrimaryVariant: ThemeValue.SolidColor? = null
        private set

    companion object {
        /**
         * The static relative path where a theme is located, regardless of the [AssetSource].
         */
        const val THEME_PATH_REL: String = "ime/theme"

        private var defaultInstance: ThemeManager? = null

        fun init(
            applicationContext: Context,
            assetManager: AssetManager,
            prefs: PrefHelper
        ): ThemeManager {
            val instance = ThemeManager(applicationContext, assetManager, prefs)
            defaultInstance = instance
            return instance
        }

        fun default(): ThemeManager {
            val instance = defaultInstance
            if (instance != null) {
                return instance
            } else {
                throw UninitializedPropertyAccessException(
                    "${ThemeManager::class.simpleName} has not been initialized previously. Make sure to call init(prefs) before using default()."
                )
            }
        }
    }

    init {
        update()
    }

    /**
     * Updates the current theme ref and loads the corresponding theme, as well as notfies all
     * callback receivers about the new theme.
     */
    fun update() {
        indexThemeRefs()
        val ref = evaluateActiveThemeRef()
        Timber.i(ref.toString())
        activeTheme = AdaptiveThemeOverlay(this, if (ref == null) {
            Theme.BASE_THEME
        } else {
            loadTheme(ref).getOr(Theme.BASE_THEME)
        })
        Timber.i(activeTheme.label)
        notifyCallbackReceivers()
    }

    /**
     * Gets the primary and ark variants of the app with given [packageName].
     * Based on a Stock Overflow answer by adneal.
     * Source: https://stackoverflow.com/a/27138913/6801193
     *
     * @param packageName The package name from which the colors should be extracted.
     */
    @SuppressLint("ResourceType")
    fun updateRemoteColorValues(packageName: String) {
        try {
            val pm = packageManager ?: return
            val res = pm.getResourcesForApplication(packageName)
            val attrs = listOf(
                res.getIdentifier("colorPrimary", "attr", packageName),
                android.R.attr.colorPrimary,
                res.getIdentifier("colorPrimaryDark", "attr", packageName),
                android.R.attr.colorPrimaryDark,
                res.getIdentifier("colorPrimaryVariant", "attr", packageName)
            )
            val androidTheme = res.newTheme()
            val defColor = if (activeTheme.isNightTheme) {
                Color.BLACK
            } else {
                Color.WHITE
            }
            val cn = pm.getLaunchIntentForPackage(packageName)?.component
            if (cn != null) {
                androidTheme.applyStyle(pm.getActivityInfo(cn, 0).theme, false)
                @Suppress("UNNECESSARY_SAFE_CALL")
                androidTheme.obtainStyledAttributes(attrs.toIntArray())?.let { a ->
                    remoteColorPrimary = when {
                        a.hasValue(0) -> {
                            ThemeValue.SolidColor(a.getColor(0, defColor))
                        }
                        a.hasValue(1) -> {
                            ThemeValue.SolidColor(a.getColor(1, defColor))
                        }
                        else -> {
                            null
                        }
                    }
                    remoteColorPrimaryVariant = when {
                        a.hasValue(2) -> {
                            ThemeValue.SolidColor(a.getColor(2, defColor))
                        }
                        a.hasValue(3) -> {
                            ThemeValue.SolidColor(a.getColor(3, defColor))
                        }
                        a.hasValue(4) -> {
                            ThemeValue.SolidColor(a.getColor(4, defColor))
                        }
                        else -> {
                            null
                        }
                    }
                    a.recycle()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Sends a theme update to the given [onThemeUpdatedListener], regardless if it is currently
     * registered or not.
     */
    fun requestThemeUpdate(onThemeUpdatedListener: OnThemeUpdatedListener): Boolean {
        onThemeUpdatedListener.onThemeUpdated(activeTheme)
        return true
    }

    @Synchronized
    fun registerOnThemeUpdatedListener(onThemeUpdatedListener: OnThemeUpdatedListener): Boolean {
        val ret = callbackReceivers.addIfAbsent(onThemeUpdatedListener)
        onThemeUpdatedListener.onThemeUpdated(activeTheme)
        return ret
    }

    @Synchronized
    fun unregisterOnThemeUpdatedListener(onThemeUpdatedListener: OnThemeUpdatedListener): Boolean {
        return callbackReceivers.remove(onThemeUpdatedListener)
    }

    @Synchronized
    fun notifyCallbackReceivers() {
        callbackReceivers.forEach {
            it.onThemeUpdated(activeTheme)
        }
    }

    fun deleteTheme(ref: AssetRef): Result<Nothing?, Throwable> {
        return assetManager.deleteAsset(ref)
    }

    fun loadTheme(ref: AssetRef): Result<Theme, Throwable> {
        assetManager.loadAsset(ref, ThemeJson::class.java).onSuccess { themeJson ->
            val theme = themeJson.toTheme()
            return Ok(theme)
        }.onFailure {
            Timber.e(it.toString())
            return Err(it)
        }
        return Err(Exception("Unreachable code"))
    }

    fun writeTheme(ref: AssetRef, theme: Theme): Result<Boolean, Throwable> {
        return assetManager.writeAsset(ref, ThemeJson::class.java, ThemeJson.fromTheme(theme))
    }

    private fun evaluateActiveThemeRef(): AssetRef? {
        Timber.i(prefs.theme.mode.toString())
        Timber.i(prefs.theme.dayThemeRef)
        Timber.i(prefs.theme.nightThemeRef)
        return AssetRef.fromString(when (prefs.theme.mode) {
            ThemeMode.ALWAYS_DAY -> {
                isAdaptiveThemeEnabled = prefs.theme.dayThemeAdaptToApp
                prefs.theme.dayThemeRef
            }
            ThemeMode.ALWAYS_NIGHT -> {
                isAdaptiveThemeEnabled = prefs.theme.nightThemeAdaptToApp
                prefs.theme.nightThemeRef
            }
            ThemeMode.FOLLOW_SYSTEM -> if (applicationContext.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            ) {
                isAdaptiveThemeEnabled = prefs.theme.nightThemeAdaptToApp
                prefs.theme.nightThemeRef
            } else {
                isAdaptiveThemeEnabled = prefs.theme.dayThemeAdaptToApp
                prefs.theme.dayThemeRef
            }
            ThemeMode.FOLLOW_TIME -> {
                val current = TimeUtil.currentLocalTime()
                val sunrise = TimeUtil.decode(prefs.theme.sunriseTime)
                val sunset = TimeUtil.decode(prefs.theme.sunsetTime)
                if (TimeUtil.isNightTime(sunrise, sunset, current)) {
                    isAdaptiveThemeEnabled = prefs.theme.nightThemeAdaptToApp
                    prefs.theme.nightThemeRef
                } else {
                    isAdaptiveThemeEnabled = prefs.theme.dayThemeAdaptToApp
                    prefs.theme.dayThemeRef
                }
            }
        }).onFailure { Timber.e(it) }.getOr(null)
    }

    private fun indexThemeRefs() {
        indexedDayThemeRefs.clear()
        indexedNightThemeRefs.clear()
        assetManager.listAssets(
            AssetRef(AssetSource.Assets, THEME_PATH_REL),
            ThemeMetaOnly::class.java
        ).onSuccess {
            for ((ref, themeMetaOnly) in it) {
                if (themeMetaOnly.isNightTheme) {
                    indexedNightThemeRefs[ref] = themeMetaOnly
                } else {
                    indexedDayThemeRefs[ref] = themeMetaOnly
                }
            }
        }.onFailure {
            Timber.e(it.toString())
        }
        assetManager.listAssets(
            AssetRef(AssetSource.Internal, THEME_PATH_REL),
            ThemeMetaOnly::class.java
        ).onSuccess {
            for ((ref, themeMetaOnly) in it) {
                if (themeMetaOnly.isNightTheme) {
                    indexedNightThemeRefs[ref] = themeMetaOnly
                } else {
                    indexedDayThemeRefs[ref] = themeMetaOnly
                }
            }
        }.onFailure {
            Timber.e(it.toString())
        }
    }

    /**
     * Functional interface which should be implemented by event listeners to be able to receive
     * theme updates.
     */
    fun interface OnThemeUpdatedListener {
        fun onThemeUpdated(theme: Theme)
    }
}
