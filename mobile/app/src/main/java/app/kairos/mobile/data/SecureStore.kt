package app.kairos.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore("kairos")

/**
 * Adresse et jeton de l'appareil, chiffrés (AES-GCM) par une clé de l'Android
 * Keystore qui ne quitte jamais le matériel ; seul le chiffré est écrit.
 */
class SecureStore(private val context: Context) {
    suspend fun save(credentials: Credentials) {
        val plain = "${credentials.baseUrl}\n${credentials.token}".toByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keystoreKey(ALIAS)) }
        val sealed = cipher.iv + cipher.doFinal(plain)
        context.dataStore.edit { it[KEY] = Base64.getEncoder().encodeToString(sealed) }
    }

    /** null si rien n'est enregistré ou si le chiffré est illisible (clé perdue). */
    suspend fun load(): Credentials? {
        val stored = context.dataStore.data.first()[KEY] ?: return null
        return runCatching {
            val sealed = Base64.getDecoder().decode(stored)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, keystoreKey(ALIAS), GCMParameterSpec(128, sealed, 0, IV_BYTES))
            }
            val (url, token) = String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)).split('\n', limit = 2)
            Credentials(url, token)
        }.getOrNull()
    }

    suspend fun clear() {
        context.dataStore.edit { it.remove(KEY) }
    }

    private companion object {
        const val ALIAS = "kairos-device"
        val KEY = stringPreferencesKey("device")
    }
}

private const val PROVIDER = "AndroidKeyStore"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val IV_BYTES = 12

/** Clé AES de l'Android Keystore sous [alias], créée au premier usage ; elle ne quitte jamais le matériel. */
fun keystoreKey(alias: String): SecretKey {
    val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
    (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
    val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
}

/** Chiffrement AES-GCM des caches hors ligne : IV puis chiffré. */
class CacheCodec(private val key: () -> SecretKey) {
    fun seal(plain: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain.toByteArray())
    }

    /** null si illisible : ancien cache en clair, contenu altéré ou clé perdue. */
    fun open(sealed: ByteArray): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        }
        String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES))
    }.getOrNull()

    companion object {
        /** Clé de l'appareil, distincte de celle du jeton. */
        val Device = CacheCodec { keystoreKey("kairos-cache") }
    }
}
