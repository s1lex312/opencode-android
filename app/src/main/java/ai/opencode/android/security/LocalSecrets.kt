package ai.opencode.android.security

import java.security.SecureRandom

/**
 * Generates the password that protects the in-app opencode server.
 *
 * The server binds to 127.0.0.1 only, but a password still matters: any app on the device can
 * reach loopback, and `opencode serve` refuses to expose its API without credentials when a
 * password is configured. 32 URL-safe characters give ~192 bits of entropy.
 */
object LocalSecrets {

    private const val ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val LENGTH = 32

    fun generatePassword(random: SecureRandom = SecureRandom()): String {
        val builder = StringBuilder(LENGTH)
        repeat(LENGTH) {
            builder.append(ALPHABET[random.nextInt(ALPHABET.length)])
        }
        return builder.toString()
    }

    /** Returns the stored local-server password, creating and persisting one if needed. */
    fun ensurePassword(store: SecretStore): String {
        store.localPassword()?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = generatePassword()
        store.setLocalPassword(generated)
        return generated
    }
}
