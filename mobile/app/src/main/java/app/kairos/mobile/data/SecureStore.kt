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
 * Le lien Mimir y est rangé de la même façon.
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

    /** Lien de partage Mimir (il porte un jeton) : chiffré à part, sous sa propre clé. */
    suspend fun saveMimir(link: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keystoreKey(MIMIR_ALIAS)) }
        val sealed = cipher.iv + cipher.doFinal(link.toByteArray())
        context.dataStore.edit { it[MIMIR_KEY] = Base64.getEncoder().encodeToString(sealed) }
    }

    /** null si aucun lien n'est enregistré ou s'il est illisible (clé perdue). */
    suspend fun loadMimir(): String? {
        val stored = context.dataStore.data.first()[MIMIR_KEY] ?: return null
        return runCatching {
            val sealed = Base64.getDecoder().decode(stored)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, keystoreKey(MIMIR_ALIAS), GCMParameterSpec(128, sealed, 0, IV_BYTES))
            }
            String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES))
        }.getOrNull()
    }

    suspend fun clearMimir() {
        context.dataStore.edit { it.remove(MIMIR_KEY) }
    }

    private companion object {
        const val ALIAS = "kairos-device"
        val KEY = stringPreferencesKey("device")
        const val MIMIR_ALIAS = "kairos-mimir"
        val MIMIR_KEY = stringPreferencesKey("mimir")
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
class CacheCodec(private val reset: () -> Unit = {}, private val key: () -> SecretKey) {
    /** Clé inutilisable (invalidée, perdue) : régénérée une fois, sinon plus aucun cache ne s'écrirait. */
    fun seal(plain: String): ByteArray = runCatching { sealWith(plain) }.getOrElse {
        if (it !is java.security.GeneralSecurityException) throw it
        reset()
        sealWith(plain)
    }

    private fun sealWith(plain: String): ByteArray {
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
        val Device = CacheCodec(
            key = { keystoreKey("kairos-cache") },
            reset = { KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry("kairos-cache") },
        )
    }
}
