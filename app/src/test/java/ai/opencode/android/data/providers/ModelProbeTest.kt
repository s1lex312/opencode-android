package ai.opencode.android.data.providers

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ModelProbeTest {

    private val server = MockWebServer()
    private val probe = ModelProbe(OkHttpClient())

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != "Bearer sk-1") {
                    return MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid API key"}}""")
                }
                return when (request.path) {
                    "/v1/models" -> MockResponse().setBody(
                        """{"object":"list","data":[{"id":"glm-5.3","owned_by":"zhipu"},{"id":"claude-opus-5-5","context_length":200000},{"id":"broken"}]}""",
                    )
                    "/v1/chat/completions" -> {
                        val body = request.body.readUtf8()
                        when {
                            "\"broken\"" in body -> MockResponse().setResponseCode(404).setBody("""{"error":"model not found"}""")
                            "\"thinker\"" in body -> MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"","reasoning_content":"The user wants ok"}}]}""")
                            else -> MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":" ok\n"}}]}""")
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun base() = server.url("/v1/").toString()

    @Test
    fun `lists models sorted, with owner and context`() = runBlocking {
        val models = probe.listModels(base(), " sk-1 ")
        assertEquals(listOf("broken", "claude-opus-5-5", "glm-5.3"), models.map { it.id })
        assertEquals(200_000L, models[1].contextLength)
        assertEquals("zhipu", models[2].ownedBy)
    }

    @Test
    fun `a wrong key reports the server's message`() = runBlocking {
        try {
            probe.listModels(base(), "nope")
            fail("expected an error")
        } catch (e: IOException) {
            assertEquals("HTTP 401: Invalid API key", e.message)
        }
    }

    @Test
    fun `a test tells answering models from broken ones`() = runBlocking {
        val ok = probe.test(base(), "sk-1", "glm-5.3")
        assertTrue(ok.ok)
        assertEquals("ok", ok.reply)
        assertEquals("The user wants ok", probe.test(base(), "sk-1", "thinker").reply)
        val broken = probe.test(base(), "sk-1", "broken")
        assertFalse(broken.ok)
        assertEquals("HTTP 404: model not found", broken.error)
    }

    @Test
    fun `other list shapes are understood`() {
        assertEquals(listOf("a", "b"), ModelProbe.parseModels("""{"models":[{"name":"b"},{"model":"a"}]}""").map { it.id })
        assertEquals(listOf("x"), ModelProbe.parseModels("""["x"]""").map { it.id })
        assertEquals("http://h/v1/models", ModelProbe.endpoint("http://h/v1//", "models"))
    }
}
