package io.github.llenoxinn.taptap.android.mesh

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserPrefs(
    val onboardingDone: Boolean = false,
    val languageTag: String = "en",
    val darkTheme: Boolean = true,
    val householdSize: Int = 1,
    val relayDesired: Boolean = false,
)

class PrefsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<UserPrefs> = _state.asStateFlow()

    fun setOnboardingDone(done: Boolean) = edit { putBoolean(KEY_ONBOARDING, done) }

    fun setLanguageTag(tag: String) = edit { putString(KEY_LANG, tag) }

    fun setDarkTheme(dark: Boolean) = edit { putBoolean(KEY_DARK, dark) }

    fun setHouseholdSize(size: Int) = edit { putInt(KEY_HOUSEHOLD, size.coerceIn(1, 255)) }

    fun setRelayDesired(desired: Boolean) = edit { putBoolean(KEY_RELAY_DESIRED, desired) }

    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _state.value = read()
    }

    private fun read(): UserPrefs = UserPrefs(
        onboardingDone = prefs.getBoolean(KEY_ONBOARDING, false),
        languageTag = prefs.getString(KEY_LANG, "en") ?: "en",
        darkTheme = prefs.getBoolean(KEY_DARK, true),
        householdSize = prefs.getInt(KEY_HOUSEHOLD, 1).coerceIn(1, 255),
        relayDesired = prefs.getBoolean(KEY_RELAY_DESIRED, false),
    )

    private companion object {
        const val PREFS_NAME = "taptap_prefs"
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_LANG = "language_tag"
        const val KEY_DARK = "dark_theme"
        const val KEY_HOUSEHOLD = "household_size"
        const val KEY_RELAY_DESIRED = "relay_desired"
    }
}
