package com.drasite.mercedesobd

import org.junit.Assert.*
import org.junit.Test

class Sim4leDecoderTest {
    private fun payload(): ByteArray = byteArrayOf(0x61, 0x01) + ByteArray(178)
    private fun ByteArray.byte(offset: Int, value: Int) { this[offset + 2] = value.toByte() }
    private fun ByteArray.word(offset: Int, value: Int) {
        byte(offset, value and 255); byte(offset + 1, (value ushr 8) and 255)
    }

    @Test fun snapshotUsesOwnScalesAndPreservesAllRawBytes() {
        val input = payload().apply {
            byte(0, 160); byte(1, 100); word(2, 0xC31); word(4, 12000)
            word(8, 3000); word(10, 65535); word(13, 4000); word(17, 1000)
            byte(30, 100); byte(37, 120); byte(38, 125); byte(39, 124); byte(40, 255)
            word(45, 0xF000); word(51, 0x1000); byte(73, 137); word(74, 100)
        }
        val sample = Sim4leDecoder.decode(input, at = 100, acquisitionMs = 222.0)
        val v = sample.values
        assertEquals("SIM4LE", sample.ecuFamily)
        assertEquals(178, sample.ecuRaw.size)
        assertArrayEquals(input.copyOfRange(2, 180), sample.ecuRaw)
        assertEquals(3000, v.rpm)
        assertEquals(4000, v.load)
        assertEquals(72, v.coolantC)
        assertEquals(27, v.iatC)
        assertEquals(100.0, v.throttlePercent!!, 0.0)
        assertEquals(568.88, v.mafGramsPerSecond!!, 0.0)
        assertEquals(99.0, v.mapKpa!!, 0.0)
        assertEquals(13.7, v.ecuVoltageV!!, 0.0)
        assertEquals(13.875, v.advanceDegrees!!, 0.0)
        assertEquals(listOf(3.0, 1.5, 0.0, 1.125), v.retardDegrees)
        assertEquals(6.25, v.stftPercent!!, 0.0)
        assertEquals(-6.25, v.ltftPercent!!, 0.0)
        assertEquals(4.0, v.injectionMs!!, 0.0)
        assertEquals(10.0, v.dutyPercent!!, 0.0)
        assertEquals("base_4us_nominal_720deg", v.injectionBasis)
        assertNull(sample.injectionMetrics)
        assertNull(sample.adapterVoltage)
        input.byte(73, 0)
        assertEquals(137, sample.ecuRaw[73].u())
    }

    @Test fun wrongPrefixAndLengthNeverDecodeAsMe21() {
        for (bad in listOf(byteArrayOf(), payload().dropLast(1).toByteArray(),
            payload() + byteArrayOf(0), payload().also { it[1] = 0xF0.toByte() })) {
            try { Sim4leDecoder.decode(bad); fail("accepted invalid 2101") }
            catch (_: DiagnosticException) {}
        }
    }

    @Test fun signedTrimsAndCorrectionsHaveIndependentMeanings() {
        val input = payload().apply {
            word(45, 0x8000); word(51, 0x7FFF); word(47, 0xFFFF)
            byte(35, 0xFF); byte(36, 0x80); word(102, 0x1000); word(104, 0xF000)
            word(147, 850); word(149, 12345); word(171, 54321); word(173, 43210)
        }
        val sample = Sim4leDecoder.decode(input)
        assertEquals(-50.0, sample.values.ltftPercent!!, 0.0)
        assertEquals(49.99847412109375, sample.values.stftPercent!!, 0.0)
        assertEquals(-1, sample.ecuFields["additive_raw"])
        assertEquals(-.375, sample.ecuFields["ignition_temperature_correction_deg"])
        assertEquals(-48.0, sample.ecuFields["ignition_other_correction_deg"])
        assertEquals(6.25, sample.ecuFields["ltft_high_pct"])
        assertEquals(-6.25, sample.ecuFields["ltft_low_pct"])
        assertEquals(850, sample.ecuFields["idle_target_rpm"])
        assertEquals(54321, sample.ecuFields["bypass_pot1_raw"])
    }
}
