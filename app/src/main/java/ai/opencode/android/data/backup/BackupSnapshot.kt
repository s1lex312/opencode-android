package ai.opencode.android.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Keys, passwords and provider sign-ins: never written to a backup in the clear. */
@Serializable
data class BackupSecrets(
    /** [ai.opencode.android.security.SecretStore.exportAll]. */
    val store: Map<String, String> = emptyMap(),
    /** rootfs-relative path → content (`auth.json`, `mcp-auth.json`). */
    val server: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = store.isEmpty() && server.isEmpty()
}

/** [BackupSecrets] sealed with AES-256-GCM under a PBKDF2 key derived from the backup password. */
@Serializable
data class EncryptedBlob(
    val kdf: String = KDF,
    val iterations: Int = ITERATIONS,
    val salt: String,
    val iv: String,
    val data: String,
) {
    companion object {
        const val KDF = "PBKDF2WithHmacSHA256"
        const val ITERATIONS = 210_000
    }
}

/**
 * Everything needed to bring the app back after an uninstall: preferences, hooks/snippets/budget,
 * the in-app server's opencode config and (sealed) secrets. Chat history is not part of it.
 */
@Serializable
data class BackupSnapshot(
    val format: String = BackupCodec.FORMAT,
    val version: Int = BackupCodec.VERSION,
    val appVersion: String = "",
    val createdAt: Long = 0L,
    /** Preference name → `{"t": type, "v": value}`. */
    val settings: JsonObject = JsonObject(emptyMap()),
    /** App files by name (`workbench.json`). */
    val files: Map<String, String> = emptyMap(),
    /** Non-secret opencode config of the in-app server, rootfs-relative path → content. */
    val server: Map<String, String> = emptyMap(),
    /** Only in device-only copies (Android's end-to-end encrypted cloud backup). */
    val secrets: BackupSecrets? = null,
    /** Secrets sealed with the user's backup password (the file in Downloads). */
    val encryptedSecrets: EncryptedBlob? = null,
) {
    val needsPassword: Boolean get() = secrets == null && encryptedSecrets != null
}

class WrongBackupPassword : Exception("Wrong backup password")

object BackupCodec {
    const val FORMAT = "opencode-android-settings"
    const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(snapshot: BackupSnapshot): String = json.encodeToString(BackupSnapshot.serializer(), snapshot)

    /** Throws [IllegalArgumentException] for anything that is not one of our backups. */
    fun decode(text: String): BackupSnapshot {
        val snapshot = runCatching { json.decodeFromString(BackupSnapshot.serializer(), text) }
            .getOrElse { throw IllegalArgumentException("Not an opencode settings backup", it) }
        require(snapshot.format == FORMAT) { "Not an opencode settings backup" }
        require(snapshot.version <= VERSION) { "Backup was made by a newer app version (${snapshot.appVersion})" }
        return snapshot
    }

    // ------------------------------------------------------------------ preferences

    fun encodePrefs(values: Map<String, Any>): JsonObject = buildJsonObject {
        values.toSortedMap().forEach { (name, value) ->
            val typed: Pair<String, JsonElement>? = when (value) {
                is String -> "s" to JsonPrimitive(value)
                is Boolean -> "b" to JsonPrimitive(value)
                is Int -> "i" to JsonPrimitive(value)
                is Long -> "l" to JsonPrimitive(value)
                is Float -> "f" to JsonPrimitive(value)
                is Double -> "d" to JsonPrimitive(value)
                is Set<*> -> "ss" to JsonArray(value.filterIsInstance<String>().sorted().map(::JsonPrimitive))
                else -> null
            }
            if (typed != null) {
                put(name, buildJsonObject {
                    put("t", typed.first)
                    put("v", typed.second)
                })
            }
        }
    }

    fun decodePrefs(obj: JsonObject): Map<String, Any> = buildMap {
        obj.forEach { (name, element) ->
            val entry = element as? JsonObject ?: return@forEach
            val type = (entry["t"] as? JsonPrimitive)?.content ?: return@forEach
            val raw = entry["v"] ?: return@forEach
            val value: Any? = when (type) {
                "s" -> (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
                "b" -> (raw as? JsonPrimitive)?.booleanOrNull
                "i" -> (raw as? JsonPrimitive)?.intOrNull
                "l" -> (raw as? JsonPrimitive)?.longOrNull
                "f" -> (raw as? JsonPrimitive)?.doubleOrNull?.toFloat()
                "d" -> (raw as? JsonPrimitive)?.doubleOrNull
                "ss" -> (raw as? JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }?.toSet()
                else -> null
            }
            if (value != null) put(name, value)
        }
    }

    // ------------------------------------------------------------------ secrets

    private val secretsJson = Json { ignoreUnknownKeys = true }

    fun seal(secrets: BackupSecrets, password: String, random: SecureRandom = SecureRandom()): EncryptedBlob {
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, EncryptedBlob.ITERATIONS), GCMParameterSpec(128, iv))
        cipher.updateAAD(FORMAT.toByteArray())
        val plain = secretsJson.encodeToString(BackupSecrets.serializer(), secrets).toByteArray()
        return EncryptedBlob(salt = b64(salt), iv = b64(iv), data = b64(cipher.doFinal(plain)))
    }

    /** Throws [WrongBackupPassword] when [password] does not open [blob]. */
    fun open(blob: EncryptedBlob, password: String): BackupSecrets {
        require(blob.kdf == EncryptedBlob.KDF) { "Unsupported key derivation ${blob.kdf}" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, unb64(blob.salt), blob.iterations), GCMParameterSpec(128, unb64(blob.iv)))
        cipher.updateAAD(FORMAT.toByteArray())
        val plain = try {
            cipher.doFinal(unb64(blob.data))
        } catch (_: AEADBadTagException) {
            throw WrongBackupPassword()
        }
        return secretsJson.decodeFromString(BackupSecrets.serializer(), String(plain))
    }

    private fun key(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance(EncryptedBlob.KDF).generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    /**
     * Identity of the content, independent of time and of the random salt/IV: an unchanged
     * configuration is not rewritten every time the app goes to the background.
     */
    fun fingerprint(snapshot: BackupSnapshot, secrets: BackupSecrets?, passwordStamp: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(encode(snapshot.copy(createdAt = 0L, secrets = null, encryptedSecrets = null)).toByteArray())
        if (secrets != null) digest.update(secretsJson.encodeToString(BackupSecrets.serializer(), secrets).toByteArray())
        // A random stamp that changes with the password, never the password itself.
        digest.update((passwordStamp ?: "-").toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)
}
