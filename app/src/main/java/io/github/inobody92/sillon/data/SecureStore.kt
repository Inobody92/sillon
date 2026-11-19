package io.github.inobody92.sillon.data

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
 * Stocke les identifiants. Le mot de passe est chiffré en AES-GCM avec une clé
 * non exportable du Keystore Android ; le reste n'est pas sensible.
 */
class SecureStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("sillon_session", Context.MODE_PRIVATE)

    fun save(c: Credentials) {
        prefs.edit()
            .putString(K_URL, c.url)
            .putString(K_ACCOUNT, c.account)
            .putString(K_PASSWORD, encrypt(c.password))
            .putBoolean(K_INSECURE, c.insecure)
            .putString(K_DEVICE, c.deviceId)
            .apply()
    }

    fun load(): Credentials? {
        val url = prefs.getString(K_URL, null) ?: return null
        val account = prefs.getString(K_ACCOUNT, null) ?: return null
        val enc = prefs.getString(K_PASSWORD, null) ?: return null
        val password = runCatching { decrypt(enc) }.getOrNull() ?: return null
        return Credentials(url, account, password, prefs.getBoolean(K_INSECURE, false), prefs.getString(K_DEVICE, null))
    }

    /** Dernières valeurs saisies, pour préremplir l'écran de connexion (sans mot de passe). */
    fun lastUrl(): String = prefs.getString(K_URL, "") ?: ""
    fun lastAccount(): String = prefs.getString(K_ACCOUNT, "") ?: ""
    fun lastInsecure(): Boolean = prefs.getBoolean(K_INSECURE, false)

    /** Oublie le mot de passe mais garde l'adresse, le compte et le device de confiance. */
    fun forgetPassword() {
        prefs.edit().remove(K_PASSWORD).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, IV_SIZE)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }
        return String(cipher.doFinal(raw, IV_SIZE, raw.size - IV_SIZE), Charsets.UTF_8)
    }

    private companion object {
        const val KEY_ALIAS = "sillon_credentials"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val K_URL = "url"
        const val K_ACCOUNT = "account"
        const val K_PASSWORD = "password"
        const val K_INSECURE = "insecure"
        const val K_DEVICE = "device_id"
    }
}
