package com.drasite.mercedesobd

import org.junit.Assert.*
import org.junit.Test

class Sim4leReaderTest {
    private val log = object : Events { override fun event(type: String, vararg values: Pair<String, Any?>) {} }
    private class Adapter(private val rejectSuffix: Boolean = false) : Wire {
        val commands = ArrayList<String>()
        var clock = 0L
        override fun close() {}
        override fun exchange(command: String, timeoutMs: Long, timing: ExchangeTiming): String {
            commands.add(command)
            clock += 5
            if (command.startsWith("AT")) return "OK"
            if (rejectSuffix && command.endsWith(" 1")) return "?"
            val payload = when (command.removeSuffix(" 1")) {
                "1A94" -> byteArrayOf(0x5A, 0x94.toByte()) + "6577815116--".toByteArray()
                "2101" -> byteArrayOf(0x61, 1) + ByteArray(178).also {
                    it[8] = 0xB8.toByte(); it[9] = 0x0B
                    it[102] = 0; it[103] = 0x10; it[104] = 0; it[105] = 0xF0.toByte()
                    it[47] = 0xFF.toByte(); it[48] = 0xFF.toByte()
                }
                else -> error("Unexpected request: $command")
            }
            val header = if (payload.size < 64) byteArrayOf((0x80 + payload.size).toByte(), 0xF1.toByte(), 1)
                else byteArrayOf(0x80.toByte(), 0xF1.toByte(), 1, payload.size.toByte())
            val body = header + payload
            return (body + byteArrayOf((body.sumOf { it.u() } and 255).toByte())).hex()
        }
    }

    @Test fun unknown5116IsReadInOneFixedQueryPerCycle() {
        val adapter = Adapter()
        val reader = Sim4leReader(adapter, log, { adapter.clock += it }, { adapter.clock })
        reader.identify {}
        assertEquals("6577815116--", reader.ecuSoftware)
        val samples = ArrayList<Sample>()
        val before = adapter.commands.size
        reader.start(true, samples::add, {})
        samples.add(reader.read())
        assertEquals(listOf("2101 1", "2101 1"), adapter.commands.drop(before))
        assertEquals(2, samples.size)
        assertEquals(3000, samples.last().values.rpm)
        assertEquals(5.0, samples.last().acquisitionMs, 0.0)
        assertFalse(adapter.commands.any { it == "ATRV" || it.startsWith("23") || it.startsWith("83") })
    }

    @Test fun unsupportedResponseSuffixFallsBackAndCorrectionsAreOnDemand() {
        val adapter = Adapter(rejectSuffix = true)
        val reader = Sim4leReader(adapter, log, { adapter.clock += it }, { adapter.clock })
        reader.identify {}
        assertEquals(listOf("1A94 1", "1A94"), adapter.commands.takeLast(2))
        reader.start(false, {}, {})
        val before = adapter.commands.size
        val values = reader.readFuelCorrections().values
        assertEquals(listOf("2101"), adapter.commands.drop(before))
        assertEquals(-6.25, values.lowPercent!!, 0.0)
        assertEquals(6.25, values.highPercent!!, 0.0)
        assertEquals(-1, values.additiveRaw)
        assertNull(values.additiveMs)
        val after = adapter.commands.size
        try { reader.readFaults(); fail("unsupported fault service") } catch (_: DiagnosticException) {}
        try { reader.clearFaults(); fail("unsupported fault clear") } catch (_: DiagnosticException) {}
        assertEquals(after, adapter.commands.size)
    }

    private class StubReader(private val error: DiagnosticException? = null) : EcuReader {
        var identifications = 0
        override val ecuProfile: EcuProfile? = null
        override val ecuSoftware = "stub"
        override val ecuIdentificationStatus = "test"
        override val mode = "test"
        override val transportProfile = "test"
        override fun identify(notice: (String) -> Unit) { identifications++; error?.let { throw it } }
        override fun start(tuning: Boolean, publish: (Sample) -> Unit, notice: (String) -> Unit) {}
        override fun read() = Sample.fromMeasurements(Measurements())
        override fun recover(error: DiagnosticException, consecutive: Int) = consecutive
        override fun readFaults() = emptyList<FaultCode>()
        override fun clearFaults() {}
        override fun readFuelCorrections() = FuelCorrections.fromMeasurements(CorrectionMeasurements())
    }

    @Test fun autodetectionKeepsMe21AndTriesSimOnlyAfterSynchronizedRejection() {
        for (error in listOf(null, DiagnosticException("not ME2.1"), LinkTimeout("no prompt"))) {
            val me21 = StubReader(error)
            val sim = StubReader()
            val reader = AutoEcuReader(Adapter(), log, { _, _ -> me21 }, { _, _ -> sim })
            try { reader.identify {} } catch (e: LinkTimeout) { assertSame(error, e) }
            assertEquals(1, me21.identifications)
            assertEquals(if (error != null && error !is LinkTimeout) 1 else 0, sim.identifications)
        }
    }
}
