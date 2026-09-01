package ee.local.go3tvplus.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import ee.local.go3tvplus.domain.AuthTokens
import ee.local.go3tvplus.domain.TokenStore
import org.json.JSONObject
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreTokenStore(context: Context) : TokenStore {
    private val preferences = context.getSharedPreferences("secure_tokens", Context.MODE_PRIVATE)

    override fun hasStoredPayload(): Boolean = preferences.contains(PAYLOAD)

    override fun load(): AuthTokens? = runCatching {
        val encoded = preferences.getString(PAYLOAD, null) ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val ivLength = bytes.first().toInt()
        val iv = bytes.copyOfRange(1, 1 + ivLength)
        val ciphertext = bytes.copyOfRange(1 + ivLength, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val key = existingSecretKey() ?: error("Secure key is temporarily unavailable")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        AuthTokens(
            accessToken = json.getString("accessToken"),
            refreshToken = json.optString("refreshToken").takeIf(String::isNotBlank),
            expiresAt = Instant.ofEpochMilli(json.getLong("expiresAt")),
        )
    }.getOrElse { error ->
        // Never log credentials or encrypted payloads. The exception type is
        // sufficient to diagnose vendor Keystore failures after app updates.
        Log.w(TAG, "Stored account could not be restored: ${error.javaClass.simpleName}")
        null
    }

    override fun save(tokens: AuthTokens) {
        val json = JSONObject()
            .put("accessToken", tokens.accessToken)
            .put("refreshToken", tokens.refreshToken.orEmpty())
            .put("expiresAt", tokens.expiresAt.toEpochMilli())
            .toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(json)
        val payload = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted
        check(
            preferences.edit()
                .putString(PAYLOAD, Base64.encodeToString(payload, Base64.NO_WRAP))
                .commit(),
        ) { "Konto turvaline salvestamine ebaõnnestus" }
    }

    override fun clear() {
        preferences.edit().clear().commit()
    }

    private fun existingSecretKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return keyStore.getKey(ALIAS, null) as? SecretKey
    }

    private fun getOrCreateSecretKey(): SecretKey {
        existingSecretKey()?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val TAG = "Go3TokenStore"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "go3_tv_auth_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PAYLOAD = "payload"
    }
}
