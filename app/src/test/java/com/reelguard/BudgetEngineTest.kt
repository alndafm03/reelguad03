package com.reelguard

import com.reelguard.core.budget.BudgetEngine
import com.reelguard.core.budget.BudgetEngine.ConsumeResult
import org.junit.Assert.*
import org.junit.Test

class BudgetEngineTest {
    private fun rig(limit: Int = 30): Triple<BudgetEngine, com.reelguard.core.state.StateManager, FakeTime> {
        val time = FakeTime()
        val sm = com.reelguard.core.state.StateManager(FakeRepo())
        val b = BudgetEngine(sm, time)
        b.setConfiguredLimit(limit)
        return Triple(b, sm, time)
    }

    @Test fun consume_decrements_and_stops_at_zero() {
        val (b, _, _) = rig(2)
        assertEquals(ConsumeResult.COUNTED, b.consume(null))
        assertEquals(ConsumeResult.COUNTED, b.consume(null))
        assertEquals(ConsumeResult.BALANCE_EMPTY, b.consume(null))
        assertEquals(0, b.remaining)
    }

    @Test fun duplicate_identity_not_counted_but_null_identity_always_counts() {
        val (b, _, _) = rig(5)
        assertEquals(ConsumeResult.COUNTED, b.consume("sig:a"))
        assertEquals(ConsumeResult.DUPLICATE, b.consume("sig:a"))
        assertEquals(ConsumeResult.COUNTED, b.consume(null))
        assertEquals(ConsumeResult.COUNTED, b.consume(null))
        assertEquals(2, b.remaining)
    }

    @Test fun limit_change_applies_immediately_only_if_cycle_untouched() {
        val (b, sm, _) = rig(30)
        b.setConfiguredLimit(40)
        assertEquals(40, b.remaining)
        b.consume(null)
        b.setConfiguredLimit(60)
        assertEquals(39, b.remaining); assertEquals(40, b.limit); assertEquals(60, sm.state.configuredLimit)
        b.startNewCycle()
        assertEquals(60, b.remaining)
    }

    @Test fun any_limit_is_supported() {
        val (b, _, _) = rig(7)
        assertEquals(7, b.remaining)
        b.setConfiguredLimit(BudgetEngine.MAX_LIMIT); assertEquals(999, b.remaining)
        b.setConfiguredLimit(1); assertEquals(1, b.remaining)
    }

    @Test fun lowering_the_limit_mid_cycle_applies_immediately() {
        val (b, sm, _) = rig(30)
        repeat(5) { b.consume(null) }                     // remaining 25
        b.setConfiguredLimit(10)
        assertEquals(10, b.limit); assertEquals(10, b.remaining)
        b.setConfiguredLimit(8)
        assertEquals(8, b.remaining); assertEquals(8, sm.state.configuredLimit)
    }

    @Test fun daily_and_weekly_counters_and_trimming() {
        val (b, sm, time) = rig(999)
        repeat(3) { b.consume(null) }
        assertEquals(3, b.todayCount())
        time.advance(24 * 3600_000L); repeat(2) { b.consume(null) }
        assertEquals(2, b.todayCount()); assertEquals(5, b.weekCount())
        time.advance(24 * 3600_000L * 20); b.consume(null)
        assertTrue(sm.state.stats.daily.size <= BudgetEngine.MAX_DAILY_KEYS)
    }

    @Test fun new_cycle_clears_counted_ids_and_allowed_content() {
        val (b, sm, _) = rig(3)
        b.consume("x"); b.setAllowedContent("x"); b.startNewCycle()
        assertTrue(sm.state.countedIds.isEmpty()); assertNull(sm.state.allowedContentKey)
        assertEquals(1, sm.state.stats.cycles)
    }
}
