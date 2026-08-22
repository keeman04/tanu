package com.mai.app.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.mai.app.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Per-device MAI authentication.
 *
 * No shared backend credential is compiled into the APK. Each installation creates a
 * non-exportable P-256 signing key in Android Keystore. An administrator supplies a
 * one-time activation code; after enrollment the phone proves possession of the private
 * key to obtain short-lived server session tokens.
 */
class MaiDeviceAuth(context: Context) {
    companion object {
        private const val KEY_ALIAS = "mai_device_auth_v1"
        private const val PREFS = "mai_device_auth"
        private const val DEVICE_ID = "device_id"
        private const val ACTIVATED = "activated"
        private const val SESSION_SKEW_SECONDS = 30L

        @Volatile private var cachedToken: String? = null
        @Volatile private var cachedExpiry: Long = 0L

        @Synchronized
        fun invalidateSession() {
            cachedToken = null
            cachedExpiry = 0L
        }
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun isBackendConfigured(): Boolean = BuildConfig.MAI_BACKEND_URL.isNotBlank()

    fun isActivated(): Boolean = !isBackendConfigured() || prefs.getBoolean(ACTIVATED, false)

    fun deviceId(): String {
        prefs.getString(DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(DEVICE_ID, id).apply()
        return id
    }

    fun enroll(activationCode: String): Result<Unit> = runCatching {
        val base = BuildConfig.MAI_BACKEND_URL.trim().trimEnd('/')
        require(base.isNotBlank()) { "MAI backend is not configured in this build." }
        val code = activationCode.trim()
        require(code.length >= 6) { "Enter the MAI device activation code." }

        val pair = ensureKeyPair()
        val payload = JSONObject()
            .put("device_id", deviceId())
            .put("public_key", Base64.encodeToString(pair.second, Base64.NO_WRAP))
            .put("activation_code", code)
            .put("label", android.os.Build.MODEL.take(80))
            .toString()
        val request = Request.Builder()
            .url("$base/v1/auth/enroll")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                throw IllegalStateException(detail ?: "Device activation failed (${response.code}).")
            }
        }
        prefs.edit().putBoolean(ACTIVATED, true).apply()
        invalidateSession()
    }

    fun sessionToken(forceRefresh: Boolean = false): String {
        val base = BuildConfig.MAI_BACKEND_URL.trim().trimEnd('/')
        require(base.isNotBlank()) { "MAI backend is not configured." }
        require(prefs.getBoolean(ACTIVATED, false)) { "This device has not been activated for MAI." }

        val now = System.currentTimeMillis() / 1000L
        if (!forceRefresh) {
            val token = cachedToken
            if (!token.isNullOrBlank() && cachedExpiry - SESSION_SKEW_SECONDS > now) return token
        }

        synchronized(MaiDeviceAuth::class.java) {
            val insideNow = System.currentTimeMillis() / 1000L
            if (!forceRefresh) {
                val token = cachedToken
                if (!token.isNullOrBlank() && cachedExpiry - SESSION_SKEW_SECONDS > insideNow) return token
            }

            val nonce = UUID.randomUUID().toString().replace("-", "")
            val canonical = "MAI1\n${deviceId()}\n$insideNow\n$nonce"
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(privateKey())
                update(canonical.toByteArray(Charsets.UTF_8))
                sign()
            }
            val payload = JSONObject()
                .put("device_id", deviceId())
                .put("timestamp", insideNow)
                .put("nonce", nonce)
                .put("signature", Base64.encodeToString(signature, Base64.NO_WRAP))
                .toString()
            val request = Request.Builder()
                .url("$base/v1/auth/session")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
                        ?.takeIf { it.isNotBlank() }
                    if (response.code == 401 || response.code == 403) {
                        prefs.edit().putBoolean(ACTIVATED, false).apply()
                    }
                    invalidateSession()
                    throw IllegalStateException(detail ?: "MAI device authentication failed (${response.code}).")
                }
                val json = JSONObject(body)
                val token = json.optString("access_token").trim()
                val expiry = json.optLong("expires_at", 0L)
                require(token.isNotBlank() && expiry > insideNow) { "MAI authentication response was incomplete." }
                cachedToken = token
                cachedExpiry = expiry
                return token
            }
        }
    }

    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun ensureKeyPair(): Pair<PrivateKey, ByteArray> {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existingPrivate = store.getKey(KEY_ALIAS, null) as? PrivateKey
        val existingPublic = store.getCertificate(KEY_ALIAS)?.publicKey?.encoded
        if (existingPrivate != null && existingPublic != null) return existingPrivate to existingPublic

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        generator.generateKeyPair()
        val privateKey = store.getKey(KEY_ALIAS, null) as PrivateKey
        val publicKey = store.getCertificate(KEY_ALIAS).publicKey.encoded
        return privateKey to publicKey
    }

    private fun privateKey(): PrivateKey {
        ensureKeyPair()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(KEY_ALIAS, null) as? PrivateKey
            ?: throw IllegalStateException("MAI device key is unavailable.")
    }
}
