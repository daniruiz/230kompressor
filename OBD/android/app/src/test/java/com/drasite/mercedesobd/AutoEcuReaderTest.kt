package com.drasite.mercedesobd

import org.junit.Assert.*
import org.junit.Test

class AutoEcuReaderTest {
    private class Harness(preferred: String? = null, val failures: List<Exception?> = emptyList(),
        allowReconnect: Boolean = false, reopenError: Exception? = null) {
        val attempts = ArrayList<Triple<String, Long, Boolean>>()
        val events = ArrayList<String>()
        val notices = ArrayList<String>()
        val remembered = ArrayList<String>()
        val transportEvents = ArrayList<String>()
        var reconnects = 0
        var reads = 0
        var sampleError: DiagnosticException? = null
        var storageError: RuntimeException? = null
        var publishAtStart = true
        private fun transport(name: String) = object : Wire {
            override fun close() { transportEvents.add("close:$name") }
            override fun exchange(command: String, timeoutMs: Long, timing: ExchangeTiming): String =
                error("The policy must leave wire operations to its readers")
        }
        private val wire = transport("old")
        private val log = object : Events {
            override fun event(type: String, vararg values: Pair<String, Any?>) { events.add(type) }
        }
        private fun candidate(family: String, transport: Wire) = object : EcuReader {
            override val ecuProfile: EcuProfile? = null
            override val ecuSoftware = family
            override val ecuIdentificationStatus = "test"
            override val mode = family
            override val transportProfile = "test"
            override fun identify(notice: (String) -> Unit) = error("Expected explicit timing policy")
            override fun identify(notice: (String) -> Unit, initialWaitMs: Long, retry: Boolean) {
                transportEvents.add("identify:${if (transport === wire) "old" else "new"}:$initialWaitMs")
                attempts.add(Triple(family, initialWaitMs, retry))
                failures.getOrNull(attempts.lastIndex)?.let { throw it }
            }
            override fun start(tuning: Boolean, publish: (Sample) -> Unit, notice: (String) -> Unit) {
                if (publishAtStart) publish(read())
            }
            override fun read(): Sample {
                reads++
                sampleError?.let { throw it }
                return Sample.fromMeasurements(Measurements())
            }
            override fun recover(error: DiagnosticException, consecutive: Int) = consecutive
            override fun readFaults() = emptyList<FaultCode>()
            override fun clearFaults() {}
            override fun readFuelCorrections() = FuelCorrections.fromMeasurements(CorrectionMeasurements())
        }
        val reader = AutoEcuReader(wire, log, { w, _ -> candidate("ME2.1", w) },
            { w, _ -> candidate("SIM4LE", w) }, preferred, { family ->
                remembered.add(family)
                storageError?.let { throw it }
            }, reconnect = if (allowReconnect) ({
                reconnects++
                transportEvents.add("open:new")
                reopenError?.let { throw it }
                transport("new")
            }) else null)
        fun identify() = reader.identify(notices::add)
    }

    @Test fun rememberedFamilyRetriesThenDetectsBothFamiliesConservatively() {
        for (preferred in listOf(null, "ME2.1", "SIM4LE", "invalid")) {
            val first = if (preferred == "SIM4LE") "SIM4LE" else "ME2.1"
            val other = if (first == "ME2.1") "SIM4LE" else "ME2.1"
            val plan = listOf(Triple(first, 0L, false), Triple(first, 3000L, false),
                Triple(other, 6000L, true), Triple(first, 6000L, true))
            for (rejections in 0..3) {
                val h = Harness(preferred, List(rejections) { DiagnosticException("rejected") })
                h.identify()
                assertEquals(plan.take(rejections + 1), h.attempts)
                assertEquals(plan[rejections].first, h.reader.mode)
                assertEquals(rejections, h.events.count { it == "ecu_family_attempt_failed" })
                assertTrue(h.notices.isEmpty())
                assertEquals(0, h.reads)
                val samples = ArrayList<Sample>()
                h.reader.start(true, samples::add, {})
                samples.add(h.reader.read())
                assertEquals(2, h.reads)
                assertEquals(2, samples.size)
            }
        }
    }

    @Test fun fatalOrCancelledAttemptStopsImmediatelyAtEveryStage() {
        for (fatal in listOf(LinkTimeout("lost prompt"), InterruptedException())) {
            for (prior in 0..3) {
                val h = Harness("SIM4LE", List(prior) { DiagnosticException("rejected") } + fatal)
                try { h.identify(); fail("fatal failure accepted") }
                catch (e: Exception) { assertSame(fatal, e) }
                assertEquals(prior + 1, h.attempts.size)
                assertEquals(0, h.reads)
                assertEquals("INICIANDO", h.reader.mode)
            }
        }
    }

    @Test fun exhaustiveRejectionReportsOnlyTheLastError() {
        val errors = List(4) { DiagnosticException("rejection $it") }
        val h = Harness(failures = errors)
        try { h.identify(); fail("all rejected") }
        catch (e: DiagnosticException) { assertSame(errors.last(), e) }
        assertEquals(4, h.attempts.size)
        assertEquals(4, h.events.count { it == "ecu_family_attempt_failed" })
        assertTrue(h.notices.isEmpty())
        assertTrue(h.remembered.isEmpty())
    }

    @Test fun onlyFirstValidSampleRecordsTheActuallyDetectedFamily() {
        for (initialPublish in listOf(true, false)) {
            val h = Harness("SIM4LE", List(2) { DiagnosticException("different car") })
            h.publishAtStart = initialPublish
            h.identify()
            assertTrue(h.remembered.isEmpty())
            h.sampleError = DiagnosticException("invalid frame")
            try { h.reader.read(); fail("invalid sample") } catch (_: DiagnosticException) {}
            assertTrue(h.remembered.isEmpty())
            h.sampleError = null
            h.reader.start(true, {}, {})
            assertEquals(if (initialPublish) listOf("ME2.1") else emptyList<String>(), h.remembered)
            repeat(3) { h.reader.read() }
            assertEquals(listOf("ME2.1"), h.remembered)
        }
    }

    @Test fun unavailablePreferenceStorageDoesNotInterruptPollingOrRetryWrites() {
        val h = Harness("SIM4LE")
        h.storageError = IllegalStateException("preferences unavailable")
        h.identify()
        h.reader.start(true, {}, {})
        h.reader.read()
        assertEquals(2, h.reads)
        assertEquals(listOf("SIM4LE"), h.remembered)
        assertEquals(1, h.events.count { it == "ecu_family_cache_error" })
    }

    @Test fun firstTimeoutClosesOldWireBeforeReopeningAndRetryingSameFamily() {
        val h = Harness("SIM4LE", listOf(LinkTimeout("lost prompt")), allowReconnect = true)
        h.identify()
        assertEquals(listOf("identify:old:0", "close:old", "open:new", "identify:new:3000"), h.transportEvents)
        assertEquals(listOf(Triple("SIM4LE", 0L, false), Triple("SIM4LE", 3000L, false)), h.attempts)
        assertEquals(1, h.reconnects)
        assertTrue(h.notices.isEmpty())
        assertEquals(0, h.reads)
        assertTrue(h.remembered.isEmpty())
    }

    @Test fun reopeningFailureOrAnyLaterTimeoutAbortsWithoutAnotherConnection() {
        val reopenError = IllegalStateException("Bluetooth connect failed")
        val failed = Harness(failures = listOf(LinkTimeout("lost")), allowReconnect = true,
            reopenError = reopenError)
        try { failed.identify(); fail("reopen failure") } catch (e: Exception) { assertSame(reopenError, e) }
        assertEquals(listOf("identify:old:0", "close:old", "open:new"), failed.transportEvents)
        for (prior in 1..3) {
            val timeout = LinkTimeout("later timeout")
            val h = Harness(failures = List(prior) { DiagnosticException("rejected") } + timeout,
                allowReconnect = true)
            try { h.identify(); fail("later timeout") } catch (e: LinkTimeout) { assertSame(timeout, e) }
            assertEquals(0, h.reconnects)
            assertEquals(prior + 1, h.attempts.size)
        }
        val h = Harness(failures = List(2) { LinkTimeout("lost") }, allowReconnect = true)
        try { h.identify(); fail("second timeout") } catch (_: LinkTimeout) {}
        assertEquals(1, h.reconnects)
        assertEquals(2, h.attempts.size)
    }

    @Test fun timeoutAfterValidSampleNeverReopensDuringPolling() {
        val h = Harness(allowReconnect = true)
        h.identify()
        h.reader.start(true, {}, {})
        val timeout = LinkTimeout("lost after connection")
        h.sampleError = timeout
        try { h.reader.read(); fail("polling timeout") } catch (e: LinkTimeout) { assertSame(timeout, e) }
        assertEquals(0, h.reconnects)
        assertEquals(1, h.attempts.size)
    }
}
