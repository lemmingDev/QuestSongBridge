package com.questsongbridge.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object Settings {
    private const val PREFS = "qs_prefs"
    private const val KEY_TOKEN = "token"
    private const val KEY_PORT = "port"
    private const val KEY_MAX_UPLOAD_MB = "max_upload_mb"
    private const val KEY_DUP_POLICY = "dup_policy"
    private const val KEY_HEADSET_NAME = "headset_name"
    private const val KEY_RETAIN_ZIP = "retain_zip"
    private const val KEY_LOG_LEVEL = "log_level"
    private const val KEY_DEST_URI = "dest_uri"

    private val masterKey by lazy {
        MasterKey.Builder(AppContext.context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs: SharedPreferences by lazy {
        try {
            EncryptedSharedPreferences.create(
                AppContext.context,
                PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            AppContext.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun getToken(): String {
        var t = prefs.getString(KEY_TOKEN, null)
        if (t.isNullOrBlank() || !t.matches(Regex("\\d{4}"))) {
            t = generateToken()
            setToken(t)
        }
        return t
    }
    fun setToken(t: String) = prefs.edit().putString(KEY_TOKEN, t).apply()
    fun regenerateToken(): String {
        val t = generateToken()
        setToken(t)
        return t
    }
    private fun generateToken(): String {
        // 4-digit numeric token (user preference). Low entropy: rely on local network + rate limiting.
        val n = java.security.SecureRandom().nextInt(10000)
        return String.format("%04d", n)
    }

    fun getPort(): Int = prefs.getInt(KEY_PORT, 8080)
    fun setPort(p: Int) = prefs.edit().putInt(KEY_PORT, p).apply()

    fun getMaxUploadBytes(): Long = prefs.getLong(KEY_MAX_UPLOAD_MB, 200L) * 1024L * 1024L
    fun setMaxUploadMb(mb: Long) = prefs.edit().putLong(KEY_MAX_UPLOAD_MB, mb).apply()

    fun getDuplicatePolicy(): String = prefs.getString(KEY_DUP_POLICY, "replace") ?: "replace"
    fun setDuplicatePolicy(p: String) = prefs.edit().putString(KEY_DUP_POLICY, p).apply()

    fun getHeadsetName(): String {
        var name = prefs.getString(KEY_HEADSET_NAME, null)
        if (name.isNullOrBlank()) {
            name = defaultHeadsetName()
            setHeadsetName(name)
        }
        return name
    }
    fun setHeadsetName(name: String) = prefs.edit().putString(KEY_HEADSET_NAME, name.trim()).apply()

    private fun defaultHeadsetName(): String {
        val androidId = android.provider.Settings.Secure.getString(
            AppContext.context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: ""
        val num = if (androidId.isNotBlank()) {
            kotlin.math.abs(androidId.hashCode()) % 10000
        } else {
            java.security.SecureRandom().nextInt(10000)
        }
        return String.format("QUEST%04d", num)
    }

    fun getRetainZip(): Boolean = prefs.getBoolean(KEY_RETAIN_ZIP, false)
    fun setRetainZip(v: Boolean) = prefs.edit().putBoolean(KEY_RETAIN_ZIP, v).apply()

    fun getLogLevel(): String = prefs.getString(KEY_LOG_LEVEL, "INFO") ?: "INFO"
    fun setLogLevel(v: String) = prefs.edit().putString(KEY_LOG_LEVEL, v).apply()

    fun getDestUri(): String? = prefs.getString(KEY_DEST_URI, null)
    fun setDestUri(uri: String?) = prefs.edit().putString(KEY_DEST_URI, uri).apply()
}
