package ai.opencode.android.ui.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomProviderValidationTest {

    @Test
    fun `slug turns a display name into a provider id`() {
        assertEquals("my-local-llm", CustomProviderViewModel.slug("My Local LLM"))
        assertEquals("lm-studio", CustomProviderViewModel.slug("  LM Studio!! "))
        assertEquals("", CustomProviderViewModel.slug("—"))
    }

    @Test
    fun `provider ids are lowercase, dashed and bounded`() {
        assertTrue(CustomProviderViewModel.ID_PATTERN.matches("lmstudio"))
        assertTrue(CustomProviderViewModel.ID_PATTERN.matches("my-proxy-2"))
        assertFalse(CustomProviderViewModel.ID_PATTERN.matches("a"))
        assertFalse(CustomProviderViewModel.ID_PATTERN.matches("My-Proxy"))
        assertFalse(CustomProviderViewModel.ID_PATTERN.matches("-leading"))
        assertFalse(CustomProviderViewModel.ID_PATTERN.matches("x".repeat(41)))
    }
}
