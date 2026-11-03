package ai.opencode.android.data.repo

import ai.opencode.android.data.model.ProviderInfo
import ai.opencode.android.data.remote.ServerCredentials
import ai.opencode.android.data.remote.ServerHttp
import ai.opencode.android.server.LocalConfigFile
import ai.opencode.android.server.provision.EnvPaths
import ai.opencode.android.settings.AuthMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Provider removal against a fake 1.x server: the key goes, and a custom provider's config too. */
class ProviderRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<Pair<String, String>>()
    private var globalConfig = """{"provider":{}}"""

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.substringBefore('?')
                requests += "${request.method} $path" to request.body.readUtf8()
                return when ("${request.method} $path") {
                    "GET /provider" -> json("""{"all":[],"connected":[]}""")
                    "GET /global/config" -> json(globalConfig)
                    // 1.x serves its web UI for unknown GET paths.
                    "GET /api/credential" -> MockResponse().setBody("<!doctype html><html></html>").setHeader("Content-Type", "text/html")
                    else -> json("true")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    private fun http() = ServerHttp(server.url("/").toString().trimEnd('/'), ServerCredentials(AuthMode.NONE, "opencode"), allowPrivateNetworkCleartext = true)

    private fun configFile(): File = File(temp.root, "rootfs/root/.config/opencode/opencode.jsonc")

    private fun repository(local: Boolean): ProviderRepository {
        val http = http()
        return ProviderRepository({ http }, LocalConfigFile(EnvPaths(temp.root))) { local }
    }

    private fun patches(): List<List<String>> = requests.filter { it.first == "PATCH /global/config" }.map { (_, body) ->
        (Json.parseToJsonElement(body) as JsonObject)["disabled_providers"]!!.jsonArray.map { it.jsonPrimitive.content }
    }

    private val custom = ProviderInfo(id = "my-llm", name = "My LLM", connected = true, source = "config")

    @Test
    fun `a custom provider is removed from the local config file, comments kept, and nothing stays disabled`() = runBlocking {
        globalConfig = """{"disabled_providers":["foo"],"provider":{"my-llm":{"npm":"@ai-sdk/openai-compatible","options":{"baseURL":"http://10.0.0.2:8080/v1"}}}}"""
        configFile().apply { parentFile!!.mkdirs() }.writeText(
            """
            {
              "${'$'}schema": "https://opencode.ai/config.json",
              // my models
              "provider": {
                "my-llm": {
                  "npm": "@ai-sdk/openai-compatible",
                  "options": { "baseURL": "http://10.0.0.2:8080/v1" }
                }
              },
              "disabled_providers": ["foo"]
            }
            """.trimIndent(),
        )
        assertTrue(repository(local = true).disconnect(custom) is ApiResult.Ok)
        val text = configFile().readText()
        assertFalse(text, text.contains("my-llm"))
        assertTrue(text.contains("// my models"))
        assertTrue(text.contains("https://opencode.ai/config.json"))
        // First PATCH makes the server re-read the edited file, the second one restores the list.
        assertEquals(listOf(listOf("foo", "my-llm"), listOf("foo")), patches())
        assertTrue(requests.any { it.first == "DELETE /auth/my-llm" })
    }

    @Test
    fun `on a remote server a custom provider is hidden, since the api cannot delete keys`() = runBlocking {
        globalConfig = """{"provider":{"my-llm":{"npm":"@ai-sdk/openai-compatible"}}}"""
        assertTrue(repository(local = false).disconnect(custom) is ApiResult.Ok)
        assertEquals(listOf(listOf("my-llm")), patches())
    }

    @Test
    fun `a built-in provider only loses its key`() = runBlocking {
        globalConfig = """{"provider":{"anthropic":{"options":{"timeout":600000}}}}"""
        val anthropic = ProviderInfo(id = "anthropic", name = "Anthropic", connected = true, source = "api")
        assertTrue(repository(local = true).disconnect(anthropic) is ApiResult.Ok)
        assertTrue(patches().isEmpty())
        assertTrue(requests.any { it.first == "DELETE /auth/anthropic" })
        assertTrue(requests.any { it.first == "POST /global/dispose" })
    }

    @Test
    fun `connecting a hidden provider again takes it off the disabled list`() = runBlocking {
        globalConfig = """{"disabled_providers":["my-llm","foo"]}"""
        assertTrue(repository(local = false).setApiKey("my-llm", " sk-test ") is ApiResult.Ok)
        assertEquals(listOf(listOf("foo")), patches())
        assertTrue(requests.any { it.first == "PUT /auth/my-llm" && it.second.contains("sk-test") && !it.second.contains(" sk-test") })
    }
}
