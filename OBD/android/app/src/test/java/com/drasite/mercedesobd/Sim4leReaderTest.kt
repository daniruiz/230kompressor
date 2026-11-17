package com.drasite.mercedesobd

import org.junit.Assert.*
import org.junit.Test

class Sim4leReaderTest {
    private val log = object : Events { override fun event(type: String, vararg values: Pair<String, Any?>) {} }
    private companion object {
        fun frame(payload: ByteArray): String {
            val header = if (payload.size < 64) byteArrayOf((0x80 + payload.size).toByte(), 0xF1.toByte(), 1)
                else byteArrayOf(0x80.toByte(), 0xF1.toByte(), 1, payload.size.toByte())
            val body = header + payload
            return (body + byteArrayOf((body.sumOf { it.u() } and 255).toByte())).hex()
        }
    }
    private class Adapter(private val rejectSuffix: Boolean = false) : Wire {
        val commands = ArrayList<String>()
        val replies = HashMap<String, String>()
        val errors = HashMap<String, DiagnosticException>()
        var clock = 0L
        override fun close() {}
        override fun exchange(command: String, timeoutMs: Long, timing: ExchangeTiming): String {
            commands.add(command)
            clock += 5
            errors[command]?.let { throw it }
            replies[command]?.let { return it }
            if (command.startsWith("AT")) return "OK"
            if (rejectSuffix && command.endsWith(" 1")) return "?"
            val payload = when (command.removeSuffix(" 1")) {
                "1A94" -> byteArrayOf(0x5A, 0x94.toByte()) + "6577815116--".toByteArray()
                "2101" -> byteArrayOf(0x61, 1) + ByteArray(178).also {
                    it[8] = 0xB8.toByte(); it[9] = 0x0B
                    it[102] = 0; it[103] = 0x10; it[104] = 0; it[105] = 0xF0.toByte()
                    it[47] = 0xFF.toByte(); it[48] = 0xFF.toByte()
                }
                "03" -> unhex("43000000000000")
                "04" -> unhex("44")
                else -> error("Unexpected request: $command")
            }
            return frame(payload)
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
        assertTrue(reader.readFaults().isEmpty())
        reader.clearFaults()
        assertEquals(listOf("03", "04"), adapter.commands.drop(after))
    }

    @Test fun obdReadsEveryFrameDecodesFamiliesAndOmitsUnmappedAndPaddingCodes() {
        val adapter = Adapter()
        adapter.replies["03"] = "03\r" + listOf("43063801060125", "430116412882AF", "43FABCFFFF0000", "43063800000000")
            .joinToString("\r") { frame(unhex(it)).chunked(10).joinToString("\r") } + "\r>"
        val reader = Sim4leReader(adapter, log, {}, { adapter.clock })
        val faults = reader.readFaults()
        assertEquals(listOf("P0638", "P0106", "P0125", "P0116", "C0128", "B02AF", "U3ABC"), faults.map { it.code })
        assertTrue(faults.all { it.status == null && it.scope == "confirmed_obd" })
        assertEquals("confirmed_obd", reader.faultScope)
        assertEquals(listOf("03"), adapter.commands)
        assertTrue(faults.all { it.statusLabel() == tr("Confirmado OBD", "Confirmed OBD") })
        assertEquals("P0300 · " + tr("estado", "status") + " 60", FaultCode("P0300", 0x60, "ME21").label())
    }

    @Test fun readsEmptyConfirmedListAndResumesFixedPollingAfterClearingWithoutSetup() {
        val adapter = Adapter()
        val reader = Sim4leReader(adapter, log, { adapter.clock += it }, { adapter.clock })
        reader.identify {}
        reader.start(true, {}, {})
        val before = adapter.commands.size
        assertTrue(reader.readFaults().isEmpty())
        reader.clearFaults()
        assertEquals(3000, reader.read().values.rpm)
        assertEquals(listOf("03", "04", "2101 1"), adapter.commands.drop(before))
    }

    private fun failure(action: () -> Unit): DiagnosticException {
        try { action() } catch (e: DiagnosticException) { return e }
        throw AssertionError("Expected a diagnostic failure")
    }

    @Test fun neitherReadNorClearTreatsNoDataNegativeOrTimeoutAsSuccess() {
        for (command in listOf("03", "04")) {
            for (reply in listOf("NO DATA", "?", "", frame(unhex("7F${command}22")), frame(unhex("7F${command}78")))) {
                val adapter = Adapter().also { it.replies[command] = reply }
                val reader = Sim4leReader(adapter, log, {}, { adapter.clock })
                val error = failure { if (command == "03") reader.readFaults() else reader.clearFaults() }
                if (reply.startsWith("83")) {
                    assertTrue(error is NegativeResponse)
                    assertEquals(command.toInt(16), (error as NegativeResponse).service)
                }
                assertEquals(listOf(command), adapter.commands)
            }
            val timeout = LinkTimeout("no prompt")
            val adapter = Adapter().also { it.errors[command] = timeout }
            val reader = Sim4leReader(adapter, log, {}, { adapter.clock })
            assertSame(timeout, failure { if (command == "03") reader.readFaults() else reader.clearFaults() })
            assertEquals(listOf(command), adapter.commands)
        }
    }

    @Test fun clearRequiresOneExactValidAcknowledgementAndDoesNotRetry() {
        val valid = frame(unhex("44"))
        val corrupt = unhex(valid).also { it[it.lastIndex] = (it.last().u() xor 1).toByte() }.hex()
        for (reply in listOf(frame(unhex("4400")), frame(unhex("54")), valid + valid, corrupt)) {
            val adapter = Adapter().also { it.replies["04"] = reply }
            val events = ArrayList<String>()
            val recorder = object : Events {
                override fun event(type: String, vararg values: Pair<String, Any?>) { events.add(type) }
            }
            val reader = Sim4leReader(adapter, recorder, {}, { adapter.clock })
            failure { reader.clearFaults() }
            assertEquals(listOf("04"), adapter.commands)
            assertEquals(listOf("dtc_clear_request"), events)
        }
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
            assertEquals(if (error != null && error !is LinkTimeout) 2 else 1, me21.identifications)
            assertEquals(if (error != null && error !is LinkTimeout) 1 else 0, sim.identifications)
        }
    }
}
