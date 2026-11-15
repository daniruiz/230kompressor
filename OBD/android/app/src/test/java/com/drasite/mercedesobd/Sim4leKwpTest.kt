package com.drasite.mercedesobd

import org.junit.Assert.*
import org.junit.Test

class Sim4leKwpTest {
    private val payload = byteArrayOf(0x61, 0x01) + ByteArray(178) { it.toByte() }
    private fun frame(data: ByteArray = payload, target: Int = 0xF1, source: Int = 1): ByteArray {
        val header = if (data.size < 64) byteArrayOf((0x80 + data.size).toByte(), target.toByte(), source.toByte())
            else byteArrayOf(0x80.toByte(), target.toByte(), source.toByte(), data.size.toByte())
        val body = header + data
        return body + byteArrayOf((body.sumOf { it.u() } and 255).toByte())
    }
    private fun decode(raw: String) = Sim4leKwp.positive(raw, "2101", "6101", 180)

    @Test fun reassemblesWrappedFrameWithEchoAndElmMessage() {
        val text = "2101 1\rSEARCHING...\r" + frame().hex().chunked(40).joinToString("\r") + "\r>"
        assertArrayEquals(payload, decode(text))
    }

    @Test fun rejectsCorruptionTruncationAmbiguityAndWrongDestination() {
        val corrupt = frame().also { it[it.lastIndex] = (it.last().u() xor 1).toByte() }
        for (reply in listOf(corrupt.hex(), frame().dropLast(1).toByteArray().hex(),
            (frame() + frame()).hex(), frame(target = 0xF0).hex(), frame(source = 2).hex(),
            payload.hex(), "NO DATA", "BUFFER FULL", "not hexadecimal")) {
            try { decode(reply); fail("accepted invalid frame: ${reply.take(20)}") }
            catch (_: DiagnosticException) {}
        }
    }

    @Test fun acceptsPendingOnlyWhenFinalResponseIsPresent() {
        val pending = frame(byteArrayOf(0x7F, 0x21, 0x78))
        assertArrayEquals(payload, decode((pending + frame()).hex()))
        try { decode(pending.hex()); fail("pending is not a sample") }
        catch (e: NegativeResponse) { assertEquals(0x78, e.code) }
    }

    @Test fun propagatesNegativeEcuResponse() {
        try { decode(frame(byteArrayOf(0x7F, 0x21, 0x12)).hex()); fail("negative response") }
        catch (e: NegativeResponse) {
            assertEquals(0x21, e.service)
            assertEquals(0x12, e.code)
        }
    }

    @Test fun validatesEveryObdFrameAndPreservesSingleResponseContract() {
        val first = unhex("43063801060125")
        val second = unhex("43011601280000")
        val raw = "03\r" + frame(first).hex() + "\r" + frame(second).hex() + "\r>"
        val replies = Sim4leKwp.positives(raw, "03", "43", 7)
        assertEquals(2, replies.size)
        assertArrayEquals(first, replies[0])
        assertArrayEquals(second, replies[1])
        try { Sim4leKwp.positive(raw, "03", "43", 7); fail("single response accepted two frames") }
        catch (_: DiagnosticException) {}
        val corrupt = frame(second).also { it[it.lastIndex] = (it.last().u() xor 1).toByte() }
        for (last in listOf(corrupt, frame(second, source = 2), frame(second, target = 0xF0),
            frame(unhex("430116")), frame(unhex("47011601280000")))) {
            try {
                Sim4leKwp.positives((frame(first) + last).hex(), "03", "43", 7)
                fail("accepted incomplete or invalid continuation")
            } catch (_: DiagnosticException) {}
        }
    }
}
