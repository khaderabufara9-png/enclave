package com.example.core.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.BuildConfig
import com.example.ui.theme.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.enclaveDataStore: DataStore<Preferences> by preferencesDataStore(name = "enclave_prefs")

data class SecuritySettingsState(
    val isOnboarded: Boolean = false,
    val installationId: String = "",
    val signingPublicKeyB64: String = "",
    val signingPrivateKeyWrappedB64: String = "",
    val ecdhPublicKeyB64: String = "",
    val ecdhPrivateKeyWrappedB64: String = "",
    val pinHashB64: String = "",
    val pinSaltB64: String = "",
    val pinEnabled: Boolean = false,
    val duressPinHashB64: String = "",
    val duressPinSaltB64: String = "",
    val duressPinEnabled: Boolean = false,
    val biometricEnabled: Boolean = false,
    val autoLockSeconds: Int = 60,
    val hideMessagePreviews: Boolean = true,
    val screenshotProtection: Boolean = true,
    val readReceiptsEnabled: Boolean = true,
    val typingIndicatorsEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val callNotificationsEnabled: Boolean = true,
    val activeSpaceId: String = "space_personal",
    val themePreference: ThemePreference = ThemePreference.DARK,
    val languageCode: String = "en",
    val backendUrl: String = BuildConfig.DEFAULT_BACKEND_URL,
    val stunUrl: String = BuildConfig.DEFAULT_STUN_URL,
    val turnUrl: String = BuildConfig.DEFAULT_TURN_URL,
    val fcmToken: String = "",
    val alwaysOnBackgroundRelay: Boolean = false,
    val lastBackgroundTimestamp: Long = 0L,
)

class SecurityPreferences(private val context: Context) {

    private object Keys {
        val IS_ONBOARDED = booleanPreferencesKey("is_onboarded")
        val INSTALLATION_ID = stringPreferencesKey("installation_id")
        val SIGNING_PUB = stringPreferencesKey("signing_pub")
        val SIGNING_PRIV_WRAPPED = stringPreferencesKey("signing_priv_wrapped")
        val ECDH_PUB = stringPreferencesKey("ecdh_pub")
        val ECDH_PRIV_WRAPPED = stringPreferencesKey("ecdh_priv_wrapped")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val PIN_ENABLED = booleanPreferencesKey("pin_enabled")
        val DURESS_PIN_HASH = stringPreferencesKey("duress_pin_hash")
        val DURESS_PIN_SALT = stringPreferencesKey("duress_pin_salt")
        val DURESS_PIN_ENABLED = booleanPreferencesKey("duress_pin_enabled")
        val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        val AUTO_LOCK_SECONDS = intPreferencesKey("auto_lock_seconds")
        val HIDE_PREVIEWS = booleanPreferencesKey("hide_previews")
        val SCREENSHOT_PROTECTION = booleanPreferencesKey("screenshot_protection")
        val READ_RECEIPTS = booleanPreferencesKey("read_receipts")
        val TYPING_INDICATORS = booleanPreferencesKey("typing_indicators")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val CALL_NOTIFICATIONS_ENABLED = booleanPreferencesKey("call_notifications_enabled")
        val ACTIVE_SPACE_ID = stringPreferencesKey("active_space_id")
        val THEME_PREF = stringPreferencesKey("theme_pref")
        val LANGUAGE_CODE = stringPreferencesKey("language_code")
        val BACKEND_URL = stringPreferencesKey("backend_url")
        val STUN_URL = stringPreferencesKey("stun_url")
        val TURN_URL = stringPreferencesKey("turn_url")
        val FCM_TOKEN = stringPreferencesKey("fcm_token")
        val ALWAYS_ON_BG_RELAY = booleanPreferencesKey("always_on_bg_relay")
        val LAST_BG_TS = longPreferencesKey("last_bg_ts")
    }

    val stateFlow: Flow<SecuritySettingsState> = context.enclaveDataStore.data.map { prefs ->
        SecuritySettingsState(
            isOnboarded = prefs[Keys.IS_ONBOARDED] ?: false,
            installationId = prefs[Keys.INSTALLATION_ID] ?: "",
            signingPublicKeyB64 = prefs[Keys.SIGNING_PUB] ?: "",
            signingPrivateKeyWrappedB64 = prefs[Keys.SIGNING_PRIV_WRAPPED] ?: "",
            ecdhPublicKeyB64 = prefs[Keys.ECDH_PUB] ?: "",
            ecdhPrivateKeyWrappedB64 = prefs[Keys.ECDH_PRIV_WRAPPED] ?: "",
            pinHashB64 = prefs[Keys.PIN_HASH] ?: "",
            pinSaltB64 = prefs[Keys.PIN_SALT] ?: "",
            pinEnabled = prefs[Keys.PIN_ENABLED] ?: false,
            duressPinHashB64 = prefs[Keys.DURESS_PIN_HASH] ?: "",
            duressPinSaltB64 = prefs[Keys.DURESS_PIN_SALT] ?: "",
            duressPinEnabled = prefs[Keys.DURESS_PIN_ENABLED] ?: false,
            biometricEnabled = prefs[Keys.BIOMETRIC_ENABLED] ?: false,
            autoLockSeconds = prefs[Keys.AUTO_LOCK_SECONDS] ?: 60,
            hideMessagePreviews = prefs[Keys.HIDE_PREVIEWS] ?: true,
            screenshotProtection = prefs[Keys.SCREENSHOT_PROTECTION] ?: true,
            readReceiptsEnabled = prefs[Keys.READ_RECEIPTS] ?: true,
            typingIndicatorsEnabled = prefs[Keys.TYPING_INDICATORS] ?: true,
            notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: true,
            callNotificationsEnabled = prefs[Keys.CALL_NOTIFICATIONS_ENABLED] ?: true,
            activeSpaceId = prefs[Keys.ACTIVE_SPACE_ID] ?: "space_personal",
            themePreference = runCatching {
                ThemePreference.valueOf(prefs[Keys.THEME_PREF] ?: ThemePreference.DARK.name)
            }.getOrDefault(ThemePreference.DARK),
            languageCode = prefs[Keys.LANGUAGE_CODE] ?: "en",
            backendUrl = prefs[Keys.BACKEND_URL] ?: BuildConfig.DEFAULT_BACKEND_URL,
            stunUrl = prefs[Keys.STUN_URL] ?: BuildConfig.DEFAULT_STUN_URL,
            turnUrl = prefs[Keys.TURN_URL] ?: BuildConfig.DEFAULT_TURN_URL,
            fcmToken = prefs[Keys.FCM_TOKEN] ?: "",
            alwaysOnBackgroundRelay = prefs[Keys.ALWAYS_ON_BG_RELAY] ?: false,
            lastBackgroundTimestamp = prefs[Keys.LAST_BG_TS] ?: 0L,
        )
    }

    suspend fun saveDeviceKeys(
        installationId: String,
        signingPublicKeyB64: String,
        signingPrivateKeyWrappedB64: String,
        ecdhPublicKeyB64: String,
        ecdhPrivateKeyWrappedB64: String,
    ) {
        context.enclaveDataStore.edit { prefs ->
            prefs[Keys.INSTALLATION_ID] = installationId
            prefs[Keys.SIGNING_PUB] = signingPublicKeyB64
            prefs[Keys.SIGNING_PRIV_WRAPPED] = signingPrivateKeyWrappedB64
            prefs[Keys.ECDH_PUB] = ecdhPublicKeyB64
            prefs[Keys.ECDH_PRIV_WRAPPED] = ecdhPrivateKeyWrappedB64
        }
    }

    suspend fun setOnboarded(onboarded: Boolean) {
        context.enclaveDataStore.edit { it[Keys.IS_ONBOARDED] = onboarded }
    }

    suspend fun savePin(saltB64: String, hashB64: String, enabled: Boolean) {
        context.enclaveDataStore.edit {
            it[Keys.PIN_SALT] = saltB64
            it[Keys.PIN_HASH] = hashB64
            it[Keys.PIN_ENABLED] = enabled
        }
    }

    suspend fun disablePin() {
        context.enclaveDataStore.edit {
            it[Keys.PIN_SALT] = ""
            it[Keys.PIN_HASH] = ""
            it[Keys.PIN_ENABLED] = false
        }
    }

    suspend fun saveDuressPin(saltB64: String, hashB64: String, enabled: Boolean = true) {
        context.enclaveDataStore.edit {
            it[Keys.DURESS_PIN_SALT] = saltB64
            it[Keys.DURESS_PIN_HASH] = hashB64
            it[Keys.DURESS_PIN_ENABLED] = enabled
        }
    }

    suspend fun disableDuressPin() {
        context.enclaveDataStore.edit {
            it[Keys.DURESS_PIN_SALT] = ""
            it[Keys.DURESS_PIN_HASH] = ""
            it[Keys.DURESS_PIN_ENABLED] = false
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.enclaveDataStore.edit { it[Keys.BIOMETRIC_ENABLED] = enabled }
    }

    suspend fun setAutoLockSeconds(seconds: Int) {
        context.enclaveDataStore.edit { it[Keys.AUTO_LOCK_SECONDS] = seconds }
    }

    suspend fun setHidePreviews(hide: Boolean) {
        context.enclaveDataStore.edit { it[Keys.HIDE_PREVIEWS] = hide }
    }

    suspend fun setScreenshotProtection(enabled: Boolean) {
        context.enclaveDataStore.edit { it[Keys.SCREENSHOT_PROTECTION] = enabled }
    }

    suspend fun setReadReceipts(enabled: Boolean) {
        context.enclaveDataStore.edit { it[Keys.READ_RECEIPTS] = enabled }
    }

    suspend fun setTypingIndicators(enabled: Boolean) {
        context.enclaveDataStore.edit { it[Keys.TYPING_INDICATORS] = enabled }
    }

    suspend fun setNotifications(enabled: Boolean, callNotifications: Boolean) {
        context.enclaveDataStore.edit {
            it[Keys.NOTIFICATIONS_ENABLED] = enabled
            it[Keys.CALL_NOTIFICATIONS_ENABLED] = callNotifications
        }
    }

    suspend fun setActiveSpaceId(spaceId: String) {
        context.enclaveDataStore.edit { it[Keys.ACTIVE_SPACE_ID] = spaceId }
    }

    suspend fun setThemePreference(pref: ThemePreference) {
        context.enclaveDataStore.edit { it[Keys.THEME_PREF] = pref.name }
    }

    suspend fun setLanguageCode(lang: String) {
        context.enclaveDataStore.edit { it[Keys.LANGUAGE_CODE] = lang }
    }

    suspend fun setNetworkEndpoints(backendUrl: String, stunUrl: String, turnUrl: String) {
        context.enclaveDataStore.edit {
            it[Keys.BACKEND_URL] = backendUrl.trim().removeSuffix("/")
            it[Keys.STUN_URL] = stunUrl.trim()
            it[Keys.TURN_URL] = turnUrl.trim()
        }
    }

    suspend fun setFcmToken(token: String) {
        context.enclaveDataStore.edit { it[Keys.FCM_TOKEN] = token.trim() }
    }

    suspend fun setAlwaysOnBackgroundRelay(enabled: Boolean) {
        context.enclaveDataStore.edit { it[Keys.ALWAYS_ON_BG_RELAY] = enabled }
    }

    suspend fun recordBackgroundTimestamp(timestamp: Long) {
        context.enclaveDataStore.edit { it[Keys.LAST_BG_TS] = timestamp }
    }
}
