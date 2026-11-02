package ai.opencode.android.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class LocalSecretsTest {

    private class MemoryStore(initial: String? = null) : SecretStore {
        var local: String? = initial

        override val persistent: Boolean get() = false

        override fun password(): String? = null

        override fun token(): String? = null

        override fun localPassword(): String? = local

        override fun setPassword(value: String?) = Unit

        override fun setToken(value: String?) = Unit

        override fun setLocalPassword(value: String?) {
            local = value
        }

        override fun clear() {
            local = null
        }
    }

    @Test
    fun `generated passwords are long and url safe`() {
        val password = LocalSecrets.generatePassword()
        assertEquals(32, password.length)
        assertTrue(password.all { it.isLetterOrDigit() })
    }

    @Test
    fun `two passwords differ`() {
        assertNotEquals(LocalSecrets.generatePassword(SecureRandom()), LocalSecrets.generatePassword(SecureRandom()))
    }

    @Test
    fun `ensurePassword stores and reuses the value`() {
        val store = MemoryStore()
        val first = LocalSecrets.ensurePassword(store)
        val second = LocalSecrets.ensurePassword(store)

        assertEquals(first, second)
        assertEquals(first, store.local)
        assertTrue(store.hasLocalPassword())
    }

    @Test
    fun `ensurePassword keeps a pre-existing password`() {
        val store = MemoryStore("already-there")
        assertEquals("already-there", LocalSecrets.ensurePassword(store))
    }
}
