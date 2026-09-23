package com.ridelink.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals

class LogRetentionTest {
    @Test
    fun `production diagnostics retain only the most recent 1024 events`() {
        val sink = InMemoryLogSink()
        repeat(100_000) { index -> sink.emit(LogEvent(index.toLong(), LogLevel.INFO, "stress", "$index")) }
        val events = sink.events
        assertEquals(1_024, events.size)
        assertEquals((98_976L..99_999L).toList(), events.map { it.monotonicTimestampUs })
        sink.emit(LogEvent(100_000, LogLevel.INFO, "stress", "next"))
        assertEquals(99_999, events.last().monotonicTimestampUs, "readers receive immutable snapshots")
        assertEquals(100_000, sink.events.last().monotonicTimestampUs)
    }
}
