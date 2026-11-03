package ai.opencode.android.data.stats

import ai.opencode.android.data.model.ChatMessage
import ai.opencode.android.data.model.Role
import ai.opencode.android.data.model.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class UsageStatsTest {

    private val utc = ZoneOffset.UTC
    private fun at(day: String, hour: Int = 12) = LocalDate.parse(day).atStartOfDay(utc).plusHours(hour.toLong()).toInstant().toEpochMilli()

    private fun turn(id: String, day: String, cost: Double, model: String = "m1", tokens: Long = 100) =
        ChatMessage(id, Role.ASSISTANT, created = at(day), providerId = "p", model = model, cost = cost, tokens = TokenUsage(input = tokens))

    @Test
    fun `aggregate by day and model, users ignored`() {
        val entries = UsageStats.aggregate(
            listOf(
                turn("1", "2026-11-01", 0.5),
                turn("2", "2026-11-01", 0.25),
                turn("3", "2026-11-01", 1.0, model = "m2"),
                turn("4", "2026-11-02", 2.0),
                ChatMessage("u", Role.USER, created = at("2026-11-02")),
            ),
            utc,
        ).sortedWith(compareBy({ it.day }, { it.model }))
        assertEquals(3, entries.size)
        assertEquals(CostEntry("2026-11-01", "p/m1", 0.75, input = 200, turns = 2), entries[0])
        assertEquals("p/m2", entries[1].model)
        assertEquals(2.0, entries[2].cost, 1e-9)
    }

    @Test
    fun `summary windows`() {
        val today = LocalDate.parse("2026-11-03")
        val entries = listOf(
            CostEntry("2026-11-03", "a", 1.0, turns = 1, input = 10),
            CostEntry("2026-11-01", "b", 2.0, turns = 2),
            CostEntry("2026-10-28", "a", 4.0, turns = 1),
            CostEntry("2026-10-01", "a", 8.0, turns = 1),
            CostEntry("garbage", "a", 100.0),
        )
        val summary = UsageStats.summarize(entries, today)
        assertEquals(1.0, summary.today, 1e-9)
        assertEquals(7.0, summary.week, 1e-9)
        assertEquals(3.0, summary.month, 1e-9)
        assertEquals(15.0, summary.total, 1e-9)
        assertEquals(5, summary.turns)
        assertEquals(UsageStats.DAYS, summary.byDay.size)
        assertEquals(today, summary.byDay.last().first)
        assertEquals(1.0, summary.byDay.last().second, 1e-9)
        assertEquals(listOf("a", "b"), summary.byModel.map { it.model })
        assertEquals(13.0, summary.byModel.first().cost, 1e-9)
    }

    @Test
    fun `budget status`() {
        val none = BudgetStatus(5.0, 0.0, 50.0, 0.0, 80)
        assertFalse(none.hasLimit)
        assertFalse(none.warning)
        assertFalse(none.exceeded)
        val warn = BudgetStatus(4.0, 5.0, 10.0, 100.0, 80)
        assertTrue(warn.warning)
        assertFalse(warn.exceeded)
        val over = BudgetStatus(1.0, 5.0, 100.0, 100.0, 80)
        assertTrue(over.exceeded)
        assertFalse(over.warning)
    }
}
