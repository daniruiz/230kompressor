package com.drasite.mercedesobd

import kotlin.math.roundToInt
import kotlin.math.sin

enum class DemoEcu(val family: String) {
    ME21("ME2.1"), SIM4LE("SIM4LE");

    companion object {
        fun fromName(value: String?): DemoEcu =
            entries.firstOrNull { it.name.equals(value, true) || it.family.equals(value, true) } ?: ME21
    }
}

/** Deterministic demo data, decoded through the same paths as live readings. */
internal object DemoSamples {
    fun corrections(ecu: DemoEcu): FuelCorrections = if (ecu == DemoEcu.ME21) {
        FuelCorrections(33768, 35168, 45)
    } else {
        FuelCorrections.fromMeasurements(CorrectionMeasurements(
            lowPercent = -1966 * 100.0 / 65536, highPercent = 1311 * 100.0 / 65536, additiveRaw = -35))
    }

    fun sample(ecu: DemoEcu, seconds: Double, at: Long, acquisitionMs: Double = 125.0): Sample {
        val phase = seconds % 8
        val load = (60 + 175 * (.5 + .5 * sin(seconds / 2.2))).toInt()
        val rpm = (900 + 5200 * (.5 + .5 * sin(seconds / 3 - 1.5))).toInt()
        val correction = if (phase >= 2) maxOf(0, 8 - ((phase - 2) * 2).toInt()) else 0
        val coolant = (140 + 18 * sin(seconds / 4 - 1.5)).toInt()
        val intake = (98 + 20 * sin(seconds / 4 - 2.5)).toInt()
        // Hold lean, boundary, neutral and rich readings long enough to see their labels.
        val oxygen = intArrayOf(36, 85, 86, 87, 88, 89, 164)[(seconds / 2).toInt() % 7]
        if (ecu == DemoEcu.ME21) {
            return Sample(
                rpm, 700 + (7500 * (.5 + .5 * sin(seconds / 3 - 1))).roundToInt(), load,
                intArrayOf(correction, maxOf(0, correction - 2), 0, 0),
                stft = (32768 + 2500 * sin(seconds)).toInt(), ltft = 34000,
                coolant = coolant, iat = intake,
                mafEcuRaw = (20 + 235 * (load / 255.0) * (rpm / 6500.0)).toInt().coerceIn(0, 255),
                bypassRaw = (255 * (.5 + .5 * sin(seconds / 3 - .5))).roundToInt(),
                bypassLimitRaw = 255,
                advance = ((-15 + 20 * sin(seconds / 4) + 66) / .75).toInt(),
                throttle = (255 * (.5 + .5 * sin(seconds / 3))).toInt(),
                oxygen = 3512 + oxygen * 64, diagnosticIds = Decoder.ids.toList(),
                at = at, acquisitionMs = acquisitionMs,
                adapterVoltage = 13.8 + .2 * sin(seconds / 2),
                adapterVoltageAgeMs = 0, adapterVoltageStatus = "ok",
            )
        }

        val payload = byteArrayOf(0x61, 0x01) + ByteArray(Sim4leDecoder.DATA_SIZE)
        fun byte(offset: Int, value: Int) { payload[offset + 2] = value.toByte() }
        fun word(offset: Int, value: Int) {
            byte(offset, value and 255)
            byte(offset + 1, (value ushr 8) and 255)
        }
        byte(0, ((coolant + 8) / .75).roundToInt())
        byte(1, ((intake + 8) / .75).roundToInt())
        word(2, (3100 * (.5 + .5 * sin(seconds / 3))).toInt())
        word(4, (6000 + load * 65))
        word(8, rpm)
        word(10, (2500 + 50000 * (load / 255.0) * (rpm / 6500.0)).toInt())
        byte(12, load)
        // Nominal 5126 demo relationship only; live decoding reads byte 12 directly.
        word(13, load * 254 + 127)
        word(17, 250 + (3500 * (.5 + .5 * sin(seconds / 3 - 1))).roundToInt())
        val bypassPosition = (Sim4leDecoder.BYPASS_OPEN_REFERENCE_RAW * (.5 - .5 * sin(seconds / 3 - .5))).roundToInt()
        word(25, bypassPosition)
        word(149, 672)
        word(171, 672 + bypassPosition)
        byte(30, ((-2 + 20 * sin(seconds / 4) + 23.625) / .375).roundToInt())
        listOf(37, 39, 40, 38).forEachIndexed { channel, offset ->
            byte(offset, 128 - if (channel < 2) maxOf(0, correction - channel * 2) * 2 else 0)
        }
        word(45, -2048)
        word(51, (4096 * sin(seconds)).toInt())
        byte(73, (138 + 2 * sin(seconds / 2)).roundToInt())
        word(74, oxygen)
        return Sim4leDecoder.decode(payload, at, acquisitionMs)
    }
}
