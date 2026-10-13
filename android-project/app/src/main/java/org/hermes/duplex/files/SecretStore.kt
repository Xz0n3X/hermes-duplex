package org.hermes.duplex.files

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets at rest, encrypted with a key that never leaves the Keystore.
 *
 * For the GitHub token. A token in plain SharedPreferences is readable by
 * anything that can read a backup or a rooted filesystem; sealed with an
 * AES-GCM key held in hardware-backed storage it is only ever usable by this
 * app on this device. Nothing here is logged, and [remove] really removes it.
 */
object SecretStore {
    private const val ALIAS = "hermes-duplex-secrets"
    private const val PREFS = "hermes-secrets"
    private const val PROVIDER = "AndroidKeyStore"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    fun put(context: Context, name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    fun get(context: Context, name: String): String? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null)
            ?: return null
        return runCatching {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12)) }
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    fun remove(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(name).apply()
    }
}
