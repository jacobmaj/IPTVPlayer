package com.example.iptv

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifra contraseñas, enlaces M3U y el PIN antes de guardarlos (AES-256 con una llave que
 * Android guarda en el chip de seguridad del equipo y que no se puede copiar).
 * En Android 5.x (sin esa función) se guardan sin cifrar, igual que antes.
 */
object Secrets {
    private const val ALIAS = "iptv_secret_key"
    private const val PREFIX = "v1:"

    private fun key(): SecretKey? = runCatching {
        if (Build.VERSION.SDK_INT < 23) return@runCatching null
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        (ks.getKey(ALIAS, null) as? SecretKey) ?: run {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            gen.generateKey()
        }
    }.getOrNull()

    fun enc(plain: String): String {
        if (plain.isEmpty()) return plain
        val k = key() ?: return plain
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k)
            val iv = c.iv
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        }.getOrDefault(plain)
    }

    /** Devuelve el texto original; los valores guardados antes del cifrado se leen tal cual. */
    fun dec(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        val k = key() ?: return ""
        return runCatching {
            val raw = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, 12)
            val ct = raw.copyOfRange(12, raw.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
