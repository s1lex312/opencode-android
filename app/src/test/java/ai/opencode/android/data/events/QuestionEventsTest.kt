package ai.opencode.android.data.events

import ai.opencode.android.data.model.ApiFlavor
import ai.opencode.android.data.model.MessagePart
import ai.opencode.android.data.model.ToolCall
import ai.opencode.android.data.model.ToolStatus
import ai.opencode.android.data.remote.OpencodeJson
import ai.opencode.android.data.remote.questionAnswers
import ai.opencode.android.ui.chat.ChatViewModel
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionEventsTest {

    private fun legacy(json: String) = EventMapper.map(ApiFlavor.LEGACY, OpencodeJson.parseToJsonElement(json) as JsonObject)

    private fun v2(json: String) = EventMapper.map(ApiFlavor.V2, OpencodeJson.parseToJsonElement(json) as JsonObject)

    @Test
    fun `legacy question asked carries every question and option`() {
        val event = legacy(
            """{"type":"question.asked","properties":{"id":"que_1","sessionID":"ses_1",
               "questions":[{"question":"Which DB?","header":"DB","options":[{"label":"Postgres","description":"relational"},{"label":"SQLite","description":"file"}]},
                            {"question":"Name?","header":"Name","options":[],"multiple":true,"custom":false}],
               "tool":{"messageID":"msg_1","callID":"call_1"}}}""",
        ) as OpencodeEvent.QuestionAsked
        val request = event.request
        assertEquals("que_1", request.id)
        assertEquals("ses_1", event.sessionId)
        assertEquals("call_1", request.callId)
        assertEquals(2, request.questions.size)
        assertEquals(listOf("Postgres", "SQLite"), request.questions[0].options.map { it.label })
        assertEquals("relational", request.questions[0].options[0].description)
        assertFalse(request.questions[0].multiple)
        assertTrue(request.questions[0].custom)
        assertTrue(request.questions[1].multiple)
        assertFalse(request.questions[1].custom)
    }

    @Test
    fun `replied and rejected resolve the question`() {
        val replied = legacy("""{"type":"question.replied","properties":{"sessionID":"ses_1","requestID":"que_1","answers":[["A"]]}}""")
        assertEquals(OpencodeEvent.QuestionResolved("ses_1", "que_1"), replied)
        val rejected = legacy("""{"type":"question.rejected","properties":{"sessionID":"ses_1","requestID":"que_2"}}""")
        assertEquals(OpencodeEvent.QuestionResolved("ses_1", "que_2"), rejected)
    }

    @Test
    fun `v2 question events`() {
        val asked = v2("""{"id":"evt_1","type":"question.v2.asked","data":{"id":"que_9","sessionID":"ses_2","questions":[{"question":"Go?","header":"Go","options":[{"label":"Yes","description":""}]}]}}""")
        assertEquals("que_9", (asked as OpencodeEvent.QuestionAsked).request.id)
        val replied = v2("""{"id":"evt_2","type":"question.replied","data":{"sessionID":"ses_2","requestID":"que_9","answers":[]}}""")
        assertEquals(OpencodeEvent.QuestionResolved("ses_2", "que_9"), replied)
    }

    @Test
    fun `a question without questions is ignored`() {
        assertNull(EventMapper.question(OpencodeJson.parseToJsonElement("""{"id":"que_1","sessionID":"s","questions":[]}""") as JsonObject))
        assertEquals(OpencodeEvent.Ignored, legacy("""{"type":"question.asked","properties":{"id":"que_1"}}"""))
    }

    @Test
    fun `answers are encoded as arrays of labels`() {
        assertEquals("""{"answers":[["A","B"],["typed"]]}""", questionAnswers(listOf(listOf("A", "B"), listOf("typed"))).toString())
    }

    @Test
    fun `only streaming events mark the chat busy`() {
        assertTrue(ChatViewModel.isLiveOutput(OpencodeEvent.TextDelta("s", "m", 0, "x")))
        assertTrue(ChatViewModel.isLiveOutput(OpencodeEvent.ToolStarted("s", "m", "t", "bash")))
        val running = MessagePart.Tool("p", ToolCall("t", "bash", ToolStatus.RUNNING))
        val done = MessagePart.Tool("p", ToolCall("t", "bash", ToolStatus.COMPLETED))
        assertTrue(ChatViewModel.isLiveOutput(OpencodeEvent.PartUpserted("s", "m", running)))
        assertFalse(ChatViewModel.isLiveOutput(OpencodeEvent.PartUpserted("s", "m", done)))
        assertTrue(ChatViewModel.isLiveOutput(OpencodeEvent.PartUpserted("s", "m", MessagePart.Text("p", "hi"), delta = "hi")))
        assertFalse(ChatViewModel.isLiveOutput(OpencodeEvent.PartUpserted("s", "m", MessagePart.Text("p", "hi"))))
        assertFalse(ChatViewModel.isLiveOutput(OpencodeEvent.ToolSucceeded("s", "m", "t", "ok")))
        assertFalse(
            ChatViewModel.isLiveOutput(
                OpencodeEvent.MessageUpserted("s", ai.opencode.android.data.model.ChatMessage("m", ai.opencode.android.data.model.Role.ASSISTANT)),
            ),
        )
    }
}
