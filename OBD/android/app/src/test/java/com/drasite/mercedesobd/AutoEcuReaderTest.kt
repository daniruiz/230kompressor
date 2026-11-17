package com.drasite.mercedesobd

import org.junit.Assert.*
import org.junit.Test

class AutoEcuReaderTest {
    private class Harness(preferred: String? = null, val failures: List<Exception?> = emptyList()) {
        val attempts = ArrayList<Triple<String, Long, Boolean>>()
        val events = ArrayList<String>()
        val notices = ArrayList<String>()
        val remembered = ArrayList<String>()
        var reads = 0
        var sampleError: DiagnosticException? = null
        var storageError: RuntimeException? = null
        var publishAtStart = true
        private val wire = object : Wire {
            override fun close() {}
            override fun exchange(command: String, timeoutMs: Long, timing: ExchangeTiming): String =
                error("The policy must leave wire operations to its readers")
        }
        private val log = object : Events {
            override fun event(type: String, vararg values: Pair<String, Any?>) { events.add(type) }
        }
        private fun candidate(family: String) = object : EcuReader {
            override val ecuProfile: EcuProfile? = null
            override val ecuSoftware = family
            override val ecuIdentificationStatus = "test"
            override val mode = family
            override val transportProfile = "test"
            override fun identify(notice: (String) -> Unit) = error("Expected explicit timing policy")
            override fun identify(notice: (String) -> Unit, initialWaitMs: Long, retry: Boolean) {
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
        val reader = AutoEcuReader(wire, log, { _, _ -> candidate("ME2.1") },
            { _, _ -> candidate("SIM4LE") }, preferred, { family ->
                remembered.add(family)
                storageError?.let { throw it }
            })
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
}
