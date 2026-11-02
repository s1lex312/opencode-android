package ai.opencode.android.data.remote

import ai.opencode.android.data.model.AgentDetail
import ai.opencode.android.data.model.BehaviourConfig
import ai.opencode.android.data.model.McpConfig
import ai.opencode.android.data.model.ModelRef
import ai.opencode.android.data.model.PermissionAction
import ai.opencode.android.server.LocalConfigFile
import ai.opencode.android.server.provision.EnvPaths
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ConfigJsonTest {

    private fun json(text: String) = OpencodeJson.parseToJsonElement(text) as JsonObject

    @Test
    fun `mcp configs round-trip`() {
        val local = McpConfig.Local(listOf("npx", "-y", "pkg"), mapOf("KEY" to "v"), "/root", enabled = false, timeoutMs = 5000)
        assertEquals(local, ConfigJson.decodeMcp(ConfigJson.encodeMcp(local)))
        val remote = McpConfig.Remote("https://x/mcp", mapOf("Authorization" to "Bearer t"), oauth = false)
        assertEquals(remote, ConfigJson.decodeMcp(ConfigJson.encodeMcp(remote)))
        assertNull(ConfigJson.decodeMcp(json("""{"enabled":false}""")))
    }

    @Test
    fun `v2 mcp encoding uses disabled and phase timeouts`() {
        val encoded = ConfigJson.encodeMcpV2(McpConfig.Remote("https://x", enabled = false, timeoutMs = 100))
        assertEquals("true", encoded.str("disabled"))
        assertEquals("100", encoded.str("timeout", "execution"))
        assertNull(encoded["enabled"])
    }

    @Test
    fun `permissions in every shape`() {
        assertEquals(PermissionAction.ALLOW, ConfigJson.decodePermissions(JsonPrimitive("allow"))["bash"])
        val mixed = ConfigJson.decodePermissions(json("""{"edit":"deny","bash":{"*":"ask","git *":"allow"},"junk":"x"}"""))
        assertEquals(mapOf("edit" to PermissionAction.DENY, "bash" to PermissionAction.ASK), mixed)
    }

    @Test
    fun `agent override wins over effective values`() {
        val effective = json("""{"name":"build","mode":"primary","native":true,"temperature":0.7,"model":{"providerID":"a","modelID":"b"}}""")
        val override = json("""{"model":"x/y","temperature":0.2,"tools":{"bash":false},"permission":{"edit":"ask"},"disable":true}""")
        val agent = ConfigJson.decodeAgent("build", effective, override)
        assertEquals(ModelRef("x", "y"), agent.model)
        assertEquals(0.2, agent.temperature!!, 1e-9)
        assertEquals(mapOf("bash" to false), agent.tools)
        assertEquals(mapOf("edit" to PermissionAction.ASK), agent.permissions)
        assertTrue(agent.native)
        assertTrue(agent.disabled)
        assertEquals(ModelRef("a", "b"), ConfigJson.decodeAgent("build", effective, null).model)
    }

    @Test
    fun `agent encoding round-trips through decoding`() {
        val agent = AgentDetail(
            name = "reviewer",
            description = "reviews",
            mode = "subagent",
            model = ModelRef("p", "m"),
            temperature = 0.1,
            topP = 0.9,
            steps = 12,
            prompt = "be strict",
            color = "warning",
            tools = mapOf("write" to false),
            permissions = mapOf("edit" to PermissionAction.DENY),
        )
        val decoded = ConfigJson.decodeAgent("reviewer", null, ConfigJson.encodeAgent(agent))
        assertEquals(agent, decoded)
    }

    @Test
    fun `behaviour encoding only emits set fields`() {
        assertEquals(JsonObject(emptyMap()), ConfigJson.encodeBehaviour(BehaviourConfig()))
        val behaviour = BehaviourConfig(
            permissions = mapOf("bash" to PermissionAction.ALLOW),
            autoCompact = false,
            reservedTokens = 8000,
            share = "disabled",
            smallModel = "p/small",
            instructions = listOf("AGENTS.md"),
        )
        val decoded = ConfigJson.decodeBehaviour(ConfigJson.encodeBehaviour(behaviour))
        assertEquals(behaviour.permissions, decoded.permissions)
        assertEquals(false, decoded.autoCompact)
        assertEquals(8000, decoded.reservedTokens)
        assertEquals("disabled", decoded.share)
        assertEquals("p/small", decoded.smallModel)
        assertEquals(listOf("AGENTS.md"), decoded.instructions)
    }

    @Test
    fun `model limit patch shape`() {
        val patch = ConfigJson.encodeModelLimit(ModelRef("anthropic", "claude-x"), 200_000, 32_000)
        assertEquals("200000", patch.str("provider", "anthropic", "models", "claude-x", "limit", "context"))
        assertEquals("32000", patch.str("provider", "anthropic", "models", "claude-x", "limit", "output"))
    }

    @Test
    fun `model refs keep slashes in the model id`() {
        assertEquals(ModelRef("openrouter", "meta/llama"), ConfigJson.parseModel("openrouter/meta/llama"))
        assertNull(ConfigJson.parseModel("nomodel"))
        assertNull(ConfigJson.parseModel("/x"))
    }

    @Test
    fun `local config file removes keys and leaves the rest`() = runBlocking {
        val root = Files.createTempDirectory("env").toFile()
        try {
            val dir = File(root, "rootfs/root/.config/opencode").apply { mkdirs() }
            val file = File(dir, "opencode.json")
            file.writeText("""{"mcp":{"a":{"type":"remote","url":"u"},"b":{"type":"remote","url":"v"}},"model":"x/y"}""")
            val config = LocalConfigFile(EnvPaths(root = root, nativeLibDir = root))
            assertTrue(config.remove(listOf("mcp", "a")))
            val after = json(file.readText())
            assertNull(after.obj("mcp")?.get("a"))
            assertEquals("v", after.str("mcp", "b", "url"))
            assertEquals("x/y", after.str("model"))
            assertFalse(config.remove(listOf("mcp", "missing")))
            assertFalse(config.remove(listOf("agent", "x")))
        } finally {
            root.deleteRecursively()
        }
    }
}
