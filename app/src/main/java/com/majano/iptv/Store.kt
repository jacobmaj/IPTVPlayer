package com.majano.iptv

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Guarda la sesión cifrada (Android Keystore). Si el dispositivo no lo soporta, usa almacenamiento privado de la app. */
class Store(context: Context) {
    private val prefs: SharedPreferences = try {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "majano_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        context.getSharedPreferences("majano_plain", Context.MODE_PRIVATE)
    }

    val link: String get() = prefs.getString("link", "").orEmpty()
    val user: String get() = prefs.getString("user", "").orEmpty()
    val pass: String get() = prefs.getString("pass", "").orEmpty()
    val auto: Boolean get() = prefs.getBoolean("auto", false)

    fun saveLogin(link: String, user: String, pass: String) {
        prefs.edit().putString("link", link).putString("user", user).putString("pass", pass).putBoolean("auto", true).apply()
    }

    fun clearLogin() {
        prefs.edit().remove("pass").putBoolean("auto", false).apply()
    }

    fun favorites(): Set<String> = prefs.getStringSet("favs", emptySet()) ?: emptySet()

    fun saveFavorites(s: Set<String>) {
        prefs.edit().putStringSet("favs", HashSet(s)).apply()
    }
}
