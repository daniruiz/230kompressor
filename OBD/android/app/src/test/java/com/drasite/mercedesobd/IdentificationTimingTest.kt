package com.drasite.mercedesobd

import org.junit.Assert.*
import org.junit.Test

class IdentificationTimingTest {
    private val log = object : Events { override fun event(type: String, vararg values: Pair<String, Any?>) {} }
    private class Adapter : Wire {
        val commands = ArrayList<String>()
        val pauses = ArrayList<Long>()
        var clock = 0L
        var rejectInit = false
        var timeout: LinkTimeout? = null
        override fun close() {}
        fun pause(ms: Long) { pauses.add(ms); clock += ms }
        override fun exchange(command: String, timeoutMs: Long, timing: ExchangeTiming): String {
            commands.add(command)
            return when {
                command == "ATSI" -> {
                    timeout?.let { throw it }
                    if (rejectInit) "ERROR" else "OK"
                }
                command.startsWith("221104") -> "6211040000"
                command == "ATRV" -> "12.6V"
                command.startsWith("1A94") -> {
                    val payload = byteArrayOf(0x5A, 0x94.toByte()) + "6577815116--".toByteArray()
                    val body = byteArrayOf(0x8E.toByte(), 0xF1.toByte(), 1) + payload
                    (body + byteArrayOf((body.sumOf { it.u() } and 255).toByte())).hex()
                }
                else -> "OK"
            }
        }
    }

    @Test fun bothFamiliesAcceptImmediateShortAndOriginalWaits() {
        for (wait in listOf(0L, 3000L, 6000L)) {
            val bosch = Adapter()
            Elm(bosch, log, pause = bosch::pause, now = { bosch.clock }).setup({}, wait, false)
            assertEquals(wait, bosch.pauses.sum())
            assertEquals(1, bosch.commands.count { it == "ATSI" })
            val sim = Adapter()
            Sim4leReader(sim, log, sim::pause, { sim.clock }).identify({}, wait, false)
            assertEquals(wait, sim.pauses.sum())
            assertEquals("ATPC", sim.commands.first())
            assertEquals(1, sim.commands.count { it == "ATFI" })
        }
    }

    @Test fun originalBoschRetryRemainsOptionalAndNeverRetriesTimeout() {
        for (retry in listOf(false, true)) {
            val adapter = Adapter().also { it.rejectInit = true }
            val elm = Elm(adapter, log, pause = adapter::pause, now = { adapter.clock })
            try { elm.setup({}, 0, retry); fail("rejected init must fail") }
            catch (_: DiagnosticException) {}
            assertEquals(if (retry) 2 else 1, adapter.commands.count { it == "ATSI" })
            assertEquals(if (retry) 6000L else 0L, adapter.pauses.sum())
        }
        val timeout = LinkTimeout("lost prompt")
        val adapter = Adapter().also { it.timeout = timeout }
        try { Elm(adapter, log, pause = adapter::pause, now = { adapter.clock }).setup({}); fail("timeout") }
        catch (e: LinkTimeout) { assertSame(timeout, e) }
        assertEquals(6000L, adapter.pauses.sum())
        assertEquals("ATSI", adapter.commands.last())
        assertEquals(1, adapter.commands.count { it == "ATSI" })
    }
}
