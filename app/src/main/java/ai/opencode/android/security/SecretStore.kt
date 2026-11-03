package ai.opencode.android.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Storage for the opencode server password / bearer token.
 *
 * Values never touch plain SharedPreferences, DataStore, the database or logs. The backing
 * EncryptedSharedPreferences file is encrypted with an AES-256-GCM key held by the Android
 * Keystore (hardware-backed where the device provides a TEE/StrongBox).
 *
 * If the Keystore is unusable (rare, e.g. a corrupted keystore after a restore) we degrade to an
 * in-memory-only store: the user is told that the secret will not survive a restart instead of
 * silently falling back to plaintext.
 */
interface SecretStore {
    val persistent: Boolean

    fun password(): String?

    fun token(): String?

    /** Password generated for the in-app server; independent from the remote server password. */
    fun localPassword(): String?

    fun setPassword(value: String?)

    fun setToken(value: String?)

    fun setLocalPassword(value: String?)

    fun hasPassword(): Boolean = !password().isNullOrEmpty()

    fun hasToken(): Boolean = !token().isNullOrEmpty()

    fun hasLocalPassword(): Boolean = !localPassword().isNullOrEmpty()

    /** Secrets of saved server profiles, addressed by [ai.opencode.android.settings.ServerProfile.secretKey]. */
    fun profileSecret(key: String): String? = null

    fun setProfileSecret(key: String, value: String?) = Unit

    /** Every stored secret, for an (encrypted) settings backup. */
    fun exportAll(): Map<String, String> = buildMap {
        password()?.let { put(KEY_PASSWORD, it) }
        token()?.let { put(KEY_TOKEN, it) }
        localPassword()?.let { put(KEY_LOCAL_PASSWORD, it) }
    }

    /** Writes back [exportAll] output; keys absent from [values] are left alone. */
    fun importAll(values: Map<String, String>) {
        values.forEach { (key, value) ->
            when (key) {
                KEY_PASSWORD -> setPassword(value)
                KEY_TOKEN -> setToken(value)
                KEY_LOCAL_PASSWORD -> setLocalPassword(value)
                else -> setProfileSecret(key, value)
            }
        }
    }

    fun clear()

    companion object {
        const val KEY_PASSWORD = "server_password"
        const val KEY_TOKEN = "server_token"
        const val KEY_LOCAL_PASSWORD = "local_server_password"

        /** Password that encrypts the keys inside the backup file; never itself backed up. */
        const val KEY_BACKUP_PASSWORD = "backup_file_password"
    }
}

class EncryptedSecretStore(context: Context) : SecretStore {

    private val appContext = context.applicationContext
    private val lock = Any()
    private var memoryPassword: String? = null
    private var memoryToken: String? = null
    private var memoryLocalPassword: String? = null
    private var prefs: SharedPreferences? = null

    override val persistent: Boolean get() = prefs != null

    init {
        prefs = try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (t: Throwable) {
            // Never log the exception message verbatim in production builds of this class:
            // keystore errors can embed key aliases. Type + a stable marker is enough.
            Log.w(TAG, "Encrypted preferences unavailable (${t.javaClass.simpleName}); falling back to memory-only")
            null
        }
    }

    override fun password(): String? = synchronized(lock) {
        prefs?.getString(KEY_PASSWORD, null) ?: memoryPassword
    }

    override fun token(): String? = synchronized(lock) {
        prefs?.getString(KEY_TOKEN, null) ?: memoryToken
    }

    override fun localPassword(): String? = synchronized(lock) {
        prefs?.getString(KEY_LOCAL_PASSWORD, null) ?: memoryLocalPassword
    }

    override fun setLocalPassword(value: String?) = synchronized(lock) {
        val stored = value?.takeIf { it.isNotEmpty() }
        val editor = prefs?.edit()
        if (editor != null) {
            if (stored == null) editor.remove(KEY_LOCAL_PASSWORD) else editor.putString(KEY_LOCAL_PASSWORD, stored)
            editor.apply()
        } else {
            memoryLocalPassword = stored
        }
    }

    override fun setPassword(value: String?) = synchronized(lock) {
        val stored = value?.takeIf { it.isNotEmpty() }
        val editor = prefs?.edit()
        if (editor != null) {
            if (stored == null) editor.remove(KEY_PASSWORD) else editor.putString(KEY_PASSWORD, stored)
            editor.apply()
        } else {
            memoryPassword = stored
        }
    }

    override fun setToken(value: String?) = synchronized(lock) {
        val stored = value?.takeIf { it.isNotEmpty() }
        val editor = prefs?.edit()
        if (editor != null) {
            if (stored == null) editor.remove(KEY_TOKEN) else editor.putString(KEY_TOKEN, stored)
            editor.apply()
        } else {
            memoryToken = stored
        }
    }

    private val memoryProfiles = mutableMapOf<String, String>()

    override fun profileSecret(key: String): String? = synchronized(lock) {
        prefs?.getString(key, null) ?: memoryProfiles[key]
    }

    override fun setProfileSecret(key: String, value: String?) = synchronized(lock) {
        val stored = value?.takeIf { it.isNotEmpty() }
        val editor = prefs?.edit()
        if (editor != null) {
            if (stored == null) editor.remove(key) else editor.putString(key, stored)
            editor.apply()
        } else if (stored == null) {
            memoryProfiles.remove(key)
        } else {
            memoryProfiles[key] = stored
        }
        Unit
    }

    override fun exportAll(): Map<String, String> = synchronized(lock) {
        val stored = prefs?.all.orEmpty().mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
        val memory = buildMap {
            memoryPassword?.let { put(KEY_PASSWORD, it) }
            memoryToken?.let { put(KEY_TOKEN, it) }
            memoryLocalPassword?.let { put(KEY_LOCAL_PASSWORD, it) }
            putAll(memoryProfiles)
        }
        (memory + stored) - SecretStore.KEY_BACKUP_PASSWORD
    }

    override fun clear() = synchronized(lock) {
        memoryProfiles.clear()
        prefs?.edit()?.clear()?.apply()
        memoryPassword = null
        memoryToken = null
        memoryLocalPassword = null
    }

    private companion object {
        const val TAG = "SecretStore"
        const val FILE_NAME = "opencode_secrets"
        const val KEY_PASSWORD = SecretStore.KEY_PASSWORD
        const val KEY_TOKEN = SecretStore.KEY_TOKEN
        const val KEY_LOCAL_PASSWORD = SecretStore.KEY_LOCAL_PASSWORD
    }
}

/**
 * Helpers that make accidental secret leakage into logs impossible.
 * Everything that logs a URL or a header value must go through these functions.
 */
object Redaction {

    private const val MASK = "***"

    private val SENSITIVE_QUERY_KEYS = setOf("token", "auth_token", "password", "api_key", "apikey", "key")

    fun url(rawUrl: String): String {
        val queryIndex = rawUrl.indexOf('?')
        if (queryIndex < 0) return rawUrl
        val base = rawUrl.substring(0, queryIndex)
        val query = rawUrl.substring(queryIndex + 1)
        val cleaned = query.split('&').joinToString("&") { pair ->
            val name = pair.substringBefore('=')
            if (name.lowercase() in SENSITIVE_QUERY_KEYS) "$name=$MASK" else pair
        }
        return "$base?$cleaned"
    }

    fun secret(value: String?): String = if (value.isNullOrEmpty()) "<unset>" else MASK

    fun headers(headers: Map<String, String>): String = headers.entries.joinToString(", ") { (key, value) ->
        if (key.equals("Authorization", ignoreCase = true) ||
            key.equals("Cookie", ignoreCase = true) ||
            key.equals("X-Api-Key", ignoreCase = true)
        ) {
            "$key: $MASK"
        } else {
            "$key: $value"
        }
    }

    /** Trims an error body so that a huge HTML error page cannot flood the UI. */
    fun preview(text: String?, limit: Int = 400): String {
        val value = text?.trim().orEmpty()
        if (value.length <= limit) return value
        return value.take(limit) + "…"
    }
}
