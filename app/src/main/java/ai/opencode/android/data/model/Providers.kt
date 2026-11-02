package ai.opencode.android.data.model

/**
 * An AI provider (Anthropic, OpenAI, OpenCode Go, …) as the connected opencode server sees it.
 * The server's provider list comes from models.dev, so every provider opencode supports shows up
 * here; [connected] says whether it has a credential (stored key, OAuth, env var or config).
 */
data class ProviderInfo(
    val id: String,
    val name: String,
    val connected: Boolean,
    /** How the provider got its credential: `api` (stored key/OAuth), `env`, `config`, `custom`. */
    val source: String? = null,
    /** Environment variables opencode reads the key from, e.g. `ANTHROPIC_API_KEY`. */
    val env: List<String> = emptyList(),
    val modelCount: Int = 0,
    /** Ways to sign in. Providers without an auth plugin accept a plain API key. */
    val authMethods: List<ProviderAuthMethod> = listOf(ProviderAuthMethod.ApiKey()),
    /** v2 servers identify stored credentials; deleting them disconnects the provider. */
    val credentialIds: List<String> = emptyList(),
) {
    /** A key in the server environment cannot be removed from the app. */
    val managedByEnvironment: Boolean get() = connected && source == "env"
}

/** One question an OAuth method asks before it can start (region, enterprise URL, …). */
data class AuthPrompt(
    val key: String,
    val message: String,
    val placeholder: String? = null,
    /** Non-empty for a choice; the value sent is [AuthOption.value]. */
    val options: List<AuthOption> = emptyList(),
)

data class AuthOption(val label: String, val value: String, val hint: String? = null)

sealed interface ProviderAuthMethod {
    val label: String?

    data class ApiKey(override val label: String? = null) : ProviderAuthMethod

    /**
     * Browser sign-in. [index] is the method's position in the 1.x `/provider/auth` list; [methodId]
     * identifies it on 2.x servers.
     */
    data class OAuth(
        override val label: String?,
        val index: Int,
        val methodId: String? = null,
        val prompts: List<AuthPrompt> = emptyList(),
    ) : ProviderAuthMethod
}

/** A started browser sign-in: open [url]; for [needsCode] the user pastes the code shown there. */
data class OAuthSession(
    val providerId: String,
    val method: ProviderAuthMethod.OAuth,
    val url: String,
    val instructions: String?,
    val needsCode: Boolean,
    /** 2.x attempt id, polled until the sign-in completes. */
    val attemptId: String? = null,
)

/** An OpenAI-compatible endpoint (LM Studio, vLLM, a proxy, …) the user adds by hand. */
data class CustomProviderDraft(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val models: List<String>,
)

/** Providers worth showing first, in this order; the rest are sorted by name. */
object FeaturedProviders {
    const val OPENCODE_GO = "opencode-go"
    const val OPENCODE_ZEN = "opencode"

    /** Where OpenCode Go/Zen keys are created. */
    const val CONSOLE_URL = "https://opencode.ai/console"

    val popular = listOf(
        OPENCODE_GO, OPENCODE_ZEN, "anthropic", "openai", "google", "openrouter", "deepseek",
        "xai", "github-copilot", "groq", "mistral", "moonshotai", "zai",
    )
}
