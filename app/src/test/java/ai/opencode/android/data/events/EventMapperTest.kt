package ai.opencode.android.data.events

import ai.opencode.android.data.model.ApiFlavor
import ai.opencode.android.data.model.PermissionDecision
import ai.opencode.android.data.model.ToolStatus
import ai.opencode.android.data.remote.OpencodeJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventMapperTest {

    private fun v2(json: String): OpencodeEvent =
        EventMapper.map(ApiFlavor.V2, OpencodeJson.parseToJsonElement(json) as JsonObject)

    private fun legacy(json: String): OpencodeEvent =
        EventMapper.map(ApiFlavor.LEGACY, OpencodeJson.parseToJsonElement(json) as JsonObject)

    @Test
    fun `maps v2 text delta`() {
        val event = v2(
            """
            {"id":"evt_1","created":1730000000000,"type":"session.text.delta",
             "data":{"sessionID":"ses_1","assistantMessageID":"msg_1","ordinal":0,"delta":"Hello"}}
            """.trimIndent(),
        )

        assertTrue(event is OpencodeEvent.TextDelta)
        event as OpencodeEvent.TextDelta
        assertEquals("ses_1", event.sessionId)
        assertEquals("msg_1", event.messageId)
        assertEquals(0, event.ordinal)
        assertEquals("Hello", event.delta)
    }

    @Test
    fun `maps v2 tool lifecycle events`() {
        val started = v2(
            """
            {"id":"e","created":1,"type":"session.tool.input.started",
             "data":{"sessionID":"ses_1","assistantMessageID":"msg_1","id":"call_1","name":"bash"}}
            """.trimIndent(),
        ) as OpencodeEvent.ToolStarted
        assertEquals("bash", started.name)

        val called = v2(
            """
            {"id":"e","created":1,"type":"session.tool.called",
             "data":{"sessionID":"ses_1","assistantMessageID":"msg_1","id":"call_1",
                     "input":{"command":"ls -la"},"executed":false}}
            """.trimIndent(),
        ) as OpencodeEvent.ToolCalled
        assertTrue(called.input!!.contains("ls -la"))

        val success = v2(
            """
            {"id":"e","created":1,"type":"session.tool.success",
             "data":{"sessionID":"ses_1","assistantMessageID":"msg_1","id":"call_1",
                     "content":[{"type":"text","text":"file1\nfile2"}],"executed":true,
                     "metadata":{"title":"Listed files"}}}
            """.trimIndent(),
        ) as OpencodeEvent.ToolSucceeded
        assertEquals("file1\nfile2", success.output)
        assertEquals("Listed files", success.title)

        val failed = v2(
            """
            {"id":"e","created":1,"type":"session.tool.failed",
             "data":{"sessionID":"ses_1","assistantMessageID":"msg_1","id":"call_1",
                     "error":{"type":"api","message":"permission denied"},"executed":true}}
            """.trimIndent(),
        ) as OpencodeEvent.ToolFailed
        assertEquals("permission denied", failed.error)
    }

    @Test
    fun `maps v2 permission request`() {
        val event = v2(
            """
            {"id":"e","created":1,"type":"permission.asked",
             "data":{"id":"per_1","sessionID":"ses_1","action":"bash",
                     "resources":["git status","git diff"],"metadata":{"command":"git status"}}}
            """.trimIndent(),
        ) as OpencodeEvent.PermissionAsked

        assertEquals("per_1", event.request.id)
        assertEquals("bash", event.request.action)
        assertEquals(listOf("git status", "git diff"), event.request.resources)
        assertTrue(event.request.metadataJson!!.contains("git status"))
    }

    @Test
    fun `maps v2 status and idle`() {
        val busy = v2(
            """
            {"id":"e","created":1,"type":"session.status",
             "data":{"sessionID":"ses_1","status":{"type":"busy"}}}
            """.trimIndent(),
        ) as OpencodeEvent.Status
        assertTrue(busy.busy)

        val retry = v2(
            """
            {"id":"e","created":1,"type":"session.status",
             "data":{"sessionID":"ses_1","status":{"type":"retry","message":"rate limited","attempt":1,"next":1000}}}
            """.trimIndent(),
        ) as OpencodeEvent.Status
        assertTrue(retry.busy)
        assertEquals("rate limited", retry.retryMessage)

        val idle = v2("""{"id":"e","created":1,"type":"session.idle","data":{"sessionID":"ses_1"}}""")
        assertEquals("ses_1", (idle as OpencodeEvent.Idle).sessionId)
    }

    @Test
    fun `maps v2 session lifecycle to sessions changed`() {
        val event = v2("""{"id":"e","created":1,"type":"session.renamed","data":{"sessionID":"ses_1"}}""")
        assertTrue(event is OpencodeEvent.SessionsChanged)
    }

    @Test
    fun `unknown v2 events are ignored`() {
        val event = v2("""{"id":"e","created":1,"type":"tui.toast.show","data":{}}""")
        assertTrue(event is OpencodeEvent.Ignored)
    }

    @Test
    fun `maps legacy message and part updates`() {
        val messageEvent = legacy(
            """
            {"type":"message.updated","properties":{"info":{
              "id":"msg_1","sessionID":"ses_1","role":"assistant","modelID":"gpt-5",
              "time":{"created":1730000000000}}}}
            """.trimIndent(),
        ) as OpencodeEvent.MessageUpserted
        assertEquals("ses_1", messageEvent.sessionId)
        assertEquals("gpt-5", messageEvent.message.model)

        val partEvent = legacy(
            """
            {"type":"message.part.updated","properties":{
              "part":{"id":"prt_1","sessionID":"ses_1","messageID":"msg_1","type":"text","text":"Hi"},
              "delta":"Hi"}}
            """.trimIndent(),
        ) as OpencodeEvent.PartUpserted
        assertEquals("Hi", partEvent.delta)
        assertTrue(partEvent.part is ai.opencode.android.data.model.MessagePart.Text)
    }

    @Test
    fun `maps legacy tool part states`() {
        val event = legacy(
            """
            {"type":"message.part.updated","properties":{"part":{
              "id":"prt_2","sessionID":"ses_1","messageID":"msg_1","type":"tool",
              "callID":"call_1","tool":"read",
              "state":{"status":"completed","input":{"path":"README.md"},
                       "output":"# title","title":"README.md","time":{"start":1,"end":2}}}}}
            """.trimIndent(),
        ) as OpencodeEvent.PartUpserted

        val tool = event.part as ai.opencode.android.data.model.MessagePart.Tool
        assertEquals("read", tool.call.name)
        assertEquals(ToolStatus.COMPLETED, tool.call.status)
        assertEquals("# title", tool.call.output)
        assertTrue(tool.call.input!!.contains("README.md"))
    }

    @Test
    fun `maps legacy permission and session error`() {
        val permission = legacy(
            """
            {"type":"permission.updated","properties":{"id":"per_1","sessionID":"ses_1",
              "type":"edit","title":"Edit src/app.kt","pattern":["src/app.kt"],"metadata":{"diff":"..."}}}
            """.trimIndent(),
        ) as OpencodeEvent.PermissionAsked
        assertEquals("Edit src/app.kt", permission.request.title)
        assertEquals(listOf("src/app.kt"), permission.request.resources)

        val error = legacy(
            """
            {"type":"session.error","properties":{"sessionID":"ses_1",
              "error":{"name":"ProviderAuthError","data":{"providerID":"openai","message":"bad key"}}}}
            """.trimIndent(),
        ) as OpencodeEvent.SessionFailed
        assertEquals("bad key", error.message)
    }

    @Test
    fun `unwraps global event envelopes`() {
        val event = legacy(
            """
            {"directory":"/repo","payload":{"type":"session.idle","properties":{"sessionID":"ses_1"}}}
            """.trimIndent(),
        )
        assertTrue(event is OpencodeEvent.Idle)
    }

    @Test
    fun `permission decision wire values match the server contract`() {
        assertEquals("once", PermissionDecision.ONCE.wire)
        assertEquals("always", PermissionDecision.ALWAYS.wire)
        assertEquals("reject", PermissionDecision.REJECT.wire)
    }

    @Test
    fun `maps 1x permission asked and replied`() {
        val asked = legacy(
            """
            {"id":"evt_1","type":"permission.asked","properties":{"id":"per_1","sessionID":"ses_1",
             "permission":"bash","patterns":["git push"],"metadata":{},"always":["git *"]}}
            """.trimIndent(),
        )
        assertTrue(asked is OpencodeEvent.PermissionAsked)
        asked as OpencodeEvent.PermissionAsked
        assertEquals("per_1", asked.request.id)
        assertEquals("bash", asked.request.action)
        assertEquals("bash: git push", asked.request.title)

        val replied = legacy(
            """{"id":"evt_2","type":"permission.replied","properties":{"sessionID":"ses_1","requestID":"per_1","reply":"once"}}""",
        )
        assertEquals(OpencodeEvent.PermissionResolved("ses_1", "per_1"), replied)
    }

    @Test
    fun `legacy message carries tokens and cost`() {
        val event = legacy(
            """
            {"type":"message.updated","properties":{"info":{"id":"msg_1","sessionID":"ses_1","role":"assistant",
             "time":{"created":1},"modelID":"m","providerID":"p","cost":0.5,
             "tokens":{"input":10,"output":20,"reasoning":0,"cache":{"read":30,"write":0}}}}}
            """.trimIndent(),
        ) as OpencodeEvent.MessageUpserted
        assertEquals(60L, event.message.tokens?.contextTokens)
        assertEquals("p", event.message.providerId)
        assertEquals(0.5, event.message.cost!!, 1e-9)
    }
}
