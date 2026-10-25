package com.drasite.mercedesobd

import org.junit.Assert.*
import org.junit.Test

class ElmTransportTest {
    private class Rig(fast: Boolean = true) : Wire, Events {
        data class Call(val command: String, val timing: ExchangeTiming)

        var time = 0L
        var reads = 0
        val calls = ArrayList<Call>()
        val events = ArrayList<Pair<String, Map<String, Any?>>>()
        val samples = ArrayList<Sample>()
        var latency: (Int) -> Long = { 100L }
        var reply: (String, Int) -> String = { cmd, _ -> defaultReply(cmd) }
        val elm = Elm(this, this, fast, pause = { time += it }, now = { time })

        fun defaultReply(command: String): String = when {
            command.startsWith("2A") -> "6A01" + "00".repeat(24)
            command.startsWith("2C") -> "6C01D2"
            command.startsWith("221104") -> "6211040000"
            command == "STI" -> "?"
            command == "ATI" -> "ELM327"
            else -> "OK"
        }

        override fun exchange(command: String, timeoutMs: Long, timing: ExchangeTiming): String {
            calls.add(Call(command, timing))
            if (command.startsWith("2A")) {
                reads++
                time += latency(reads)
            } else time += 10
            return reply(command, reads)
        }

        override fun close() {}

        override fun event(type: String, vararg values: Pair<String, Any?>) {
            events.add(type to values.toMap())
        }

        fun prime() { elm.acquire(true) }
        fun optimize(group: Boolean = true) { elm.optimize(group, { samples.add(it) }, {}) }
        fun result() = events.last { it.first == "elm_transport_benchmark" }.second

        // One priming read, then 7/7/7/4 reads. Values are wire latency before host waits.
        fun phaseLatency(base: Long, trial20: Long, trial0: Long, control: Long = base) {
            latency = { read ->
                when (read) {
                    in 9..15 -> trial20
                    in 16..22 -> trial0
                    in 23..26 -> control
                    else -> base
                }
            }
        }
    }

    @Test fun selectsZeroWhenBothReductionsAreWorthwhileAndDoesNotRetest() {
        val r = Rig()
        r.prime()
        r.optimize()
        assertEquals("ELM host 0 ms", r.elm.profile)
        assertEquals(0L, r.result()["selected_host_gap_ms"])
        assertEquals(List(6) { 160.0 }, r.result()["baseline_ms"])
        assertEquals(List(6) { 120.0 }, r.result()["trial_20_ms"])
        assertEquals(List(6) { 100.0 }, r.result()["trial_0_ms"])
        assertEquals(List(3) { 160.0 }, r.result()["control_ms"])
        assertEquals(25, r.samples.size)
        assertTrue(r.calls.all { it.command == "2A0101 1" })
        val count = r.calls.size
        r.optimize()
        assertEquals(count, r.calls.size)
        r.elm.acquire(true)
        assertEquals(0L, r.calls.last().timing.hostGapWaitMs)
    }

    @Test fun prefersTwentyWhenZeroAddsLessThanEightMilliseconds() {
        val r = Rig()
        r.phaseLatency(100, 100, 115)
        r.prime()
        r.optimize()
        assertEquals("ELM host 20 ms", r.elm.profile)
        assertEquals(20L, r.result()["selected_host_gap_ms"])
        r.elm.acquire(true)
        assertEquals(20L, r.calls.last().timing.hostGapWaitMs)
    }

    @Test fun keepsSixtyIfThereIsNoBenefitAndDoesNotRetest() {
        val r = Rig()
        r.phaseLatency(100, 140, 160)
        r.prime()
        r.optimize()
        assertEquals("ELM compatible", r.elm.profile)
        assertEquals(60L, r.result()["selected_host_gap_ms"])
        assertEquals(false, r.result()["failed"])
        val count = r.calls.size
        r.optimize()
        assertEquals(count, r.calls.size)
        r.elm.acquire(true)
        assertEquals(60L, r.calls.last().timing.hostGapWaitMs)
    }

    @Test fun finalControlPreventsAcceptingAnUnrelatedSpeedup() {
        val r = Rig()
        r.phaseLatency(100, 100, 100, control = 40)
        r.prime()
        r.optimize()
        assertEquals(100.0, r.result()["reference_median_ms"])
        assertEquals(60L, r.result()["selected_host_gap_ms"])
    }

    @Test fun requiresBothAbsoluteAndRelativeSavings() {
        for ((base, trial20, trial0) in listOf(Triple(10L, 43L, 63L), Triple(1000L, 1020L, 1040L))) {
            val r = Rig()
            r.phaseLatency(base, trial20, trial0)
            r.prime()
            r.optimize()
            assertEquals(60L, r.result()["selected_host_gap_ms"])
        }
    }

    @Test fun discardsFirstCycleOfEachPhaseButStillPublishesIt() {
        val r = Rig()
        r.latency = { if (it in listOf(2, 9, 16, 23)) 9000L else 100L }
        r.prime()
        r.optimize()
        assertEquals(25, r.samples.size)
        assertEquals(4, r.samples.count { it.acquisitionMs >= 9000 })
        assertEquals(List(6) { 160.0 }, r.result()["baseline_ms"])
        assertEquals(List(6) { 120.0 }, r.result()["trial_20_ms"])
        assertEquals(List(6) { 100.0 }, r.result()["trial_0_ms"])
        assertEquals(List(3) { 160.0 }, r.result()["control_ms"])
    }

    @Test fun skipsIndividualAndConservativeModes() {
        for (r in listOf(Rig(), Rig(fast = false))) {
            r.optimize(group = !r.elm.fast)
            assertTrue(r.calls.isEmpty())
            assertTrue(r.events.isEmpty())
        }
    }

    @Test fun stnUsesItsExistingAdapterTimingCommands() {
        val r = Rig()
        r.reply = { cmd, _ -> if (cmd == "STI") "STN1170" else r.defaultReply(cmd) }
        r.elm.setup {}
        r.prime()
        r.optimize()
        assertTrue(r.calls.any { it.command == "STPTRQ 60" })
        assertTrue(r.calls.any { it.command == "STIP4 0" })
        assertTrue(r.events.none { it.first == "elm_transport_benchmark" })
    }

    @Test fun noDataDuringTrialRetriesAtSixtyAndDisablesFurtherProbes() {
        val r = Rig()
        r.reply = { cmd, read -> if (read == 9) "NO DATA" else r.defaultReply(cmd) }
        r.prime()
        r.optimize()
        assertEquals(true, r.result()["failed"])
        assertEquals(true, r.result()["disabled"])
        assertEquals("ELM compatible", r.elm.profile)
        assertEquals(60L, r.calls.last().timing.hostGapWaitMs)
        assertEquals(1, r.events.count { it.first == "transport_fallback" })
        val count = r.calls.size
        r.optimize()
        assertEquals(count, r.calls.size)
    }

    @Test fun invalidFrameDuringTrialRestoresSixty() {
        val r = Rig()
        r.reply = { cmd, read -> if (read == 9) "6A0100" else r.defaultReply(cmd) }
        r.prime()
        r.optimize()
        assertEquals(true, r.result()["failed"])
        assertEquals(true, r.result()["disabled"])
        r.elm.acquire(true)
        assertEquals(60L, r.calls.last().timing.hostGapWaitMs)
    }

    @Test fun invalidAcquisitionDurationCancelsTrial() {
        val r = Rig()
        r.latency = { if (it == 16) 0L else 100L }
        r.prime()
        r.optimize()
        assertEquals(true, r.result()["failed"])
        assertEquals(true, r.result()["disabled"])
        r.elm.acquire(true)
        assertEquals(60L, r.calls.last().timing.hostGapWaitMs)
    }

    @Test fun rejectedSuffixDuringTrialRestoresSixtyBeforeRetrying() {
        val r = Rig()
        r.reply = { cmd, read -> if (read == 9) "?" else r.defaultReply(cmd) }
        r.prime()
        r.optimize()
        assertFalse(r.elm.fast)
        assertEquals("2A0101", r.calls.last().command)
        assertEquals(60L, r.calls.last().timing.hostGapWaitMs)
        assertEquals(true, r.result()["disabled"])
    }

    @Test fun errorsAfterSuccessfulTuningAlsoRestoreSixty() {
        for (error in listOf("NO DATA", "?")) {
            val r = Rig()
            r.prime()
            r.optimize()
            val failedRead = r.reads + 1
            r.reply = { cmd, read -> if (read == failedRead) error else r.defaultReply(cmd) }
            r.elm.acquire(true)
            assertEquals("ELM compatible", r.elm.profile)
            assertEquals(60L, r.calls.last().timing.hostGapWaitMs)
            assertEquals(1, r.events.count { it.first == "transport_fallback" })
            assertFalse(r.elm.rollback("Already restored"))
            val count = r.calls.size
            r.optimize()
            assertEquals(count, r.calls.size)
        }
    }

    @Test fun lostPromptIsFatalAndNeverSendsRecoveryCommands() {
        val r = Rig()
        r.reply = { cmd, read ->
            if (read == 9) throw LinkTimeout("Missing prompt")
            r.defaultReply(cmd)
        }
        r.prime()
        assertThrows(LinkTimeout::class.java) { r.optimize() }
        assertEquals(9, r.calls.size)
        assertEquals(true, r.result()["failed"])
        assertEquals(true, r.result()["disabled"])
        assertTrue(r.events.none { it.first == "transport_fallback" })
    }

    @Test fun interruptedTrialStopsImmediatelyAndRecordsFailure() {
        val r = Rig()
        r.reply = { cmd, read ->
            if (read == 9) throw InterruptedException()
            r.defaultReply(cmd)
        }
        r.prime()
        assertThrows(InterruptedException::class.java) { r.optimize() }
        assertEquals(9, r.calls.size)
        assertEquals(true, r.result()["failed"])
        assertTrue(r.events.none { it.first == "transport_fallback" })
    }

    @Test fun groupDefinitionsUseSuffixAndFirstReadChecksFullResponse() {
        val r = Rig()
        r.elm.defineGroup()
        assertEquals(13, r.calls.size)
        assertEquals(
            Decoder.ids.mapIndexed { i, id -> Decoder.definition(i + 1, id) + " 1" },
            r.calls.take(12).map { it.command },
        )
        assertEquals("2A0101", r.calls.last().command)
    }

    @Test fun definitionsNeverRetryNoData() {
        val r = Rig()
        r.reply = { _, _ -> "NO DATA" }
        assertThrows(DiagnosticException::class.java) { r.elm.defineGroup() }
        assertEquals(1, r.calls.size)
    }

    @Test fun olderClonesCanRejectTheSuffixWhileDefiningGroups() {
        val r = Rig()
        r.reply = { cmd, _ -> if (cmd.endsWith(" 1")) "?" else r.defaultReply(cmd) }
        r.elm.defineGroup()
        assertFalse(r.elm.fast)
        assertEquals(14, r.calls.size)
        assertEquals(Decoder.definition(1, Decoder.ids[0]), r.calls[1].command)
        assertTrue(r.calls.drop(1).none { it.command.endsWith(" 1") })
        val count = r.calls.size
        r.optimize()
        assertEquals(count, r.calls.size)
    }

    @Test fun passesActualHostWaitAndStartTimeToWireAndDoesNotDelayLocalCommands() {
        val r = Rig()
        r.prime()
        r.time += 10
        val start = r.time
        r.elm.acquire(true)
        assertEquals(ExchangeTiming(start, 50), r.calls.last().timing)
        r.elm.command("ATI")
        assertEquals(0L, r.calls.last().timing.hostGapWaitMs)
    }
}
