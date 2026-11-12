package com.drasite.mercedesobd

/** Reader-supplied engineering values. Null means unavailable, never a zero reading. */
data class Measurements(
    val rpm: Int? = null,
    val load: Int? = null,
    val loadMaximum: Double = 255.0,
    val loadUnit: String = "",
    val injectionMs: Double? = null,
    val injectionRaw: Int? = null,
    val dutyPercent: Double? = null,
    val retardDegrees: List<Double> = emptyList(),
    val coolantC: Int? = null,
    val iatC: Int? = null,
    val throttlePercent: Double? = null,
    val stftPercent: Double? = null,
    val ltftPercent: Double? = null,
    val mafGramsPerSecond: Double? = null,
    val mafAtLimit: Boolean? = null,
    val ecuMafGramsPerSecond: Double? = null,
    val advanceDegrees: Double? = null,
    val oxygenVolts: Double? = null,
    val oxygenRich: Boolean? = null,
) {
    val retard: Double? get() = retardDegrees.maxOrNull()
    val highRetard: Boolean get() = (retard ?: 0.0) > 3.0
}

/** The legacy ME2.1 conversions live here, rather than in dashboard rendering. */
internal object Me21Measurements {
    fun from(sample: Sample): Measurements {
        val injection = Injection.metrics(sample.injection, sample.rpm, null)
        return Measurements(
            rpm = sample.rpm,
            load = sample.load,
            injectionMs = injection.totalMs,
            injectionRaw = sample.injection,
            dutyPercent = injection.duty,
            retardDegrees = sample.corrections.map { it * .75 },
            coolantC = sample.coolant - 40,
            iatC = sample.iat - 40,
            throttlePercent = sample.throttle * 100.0 / 255,
            stftPercent = Decoder.trim(sample.stft),
            ltftPercent = Decoder.trim(sample.ltft),
            mafGramsPerSecond = sample.mafGramsPerSecond,
            mafAtLimit = sample.maf == 1023,
            ecuMafGramsPerSecond = Decoder.ecuMaf(sample.mafEcuRaw),
            advanceDegrees = Decoder.advance(sample.advance),
            oxygenVolts = Decoder.oxygen(sample.oxygen),
            oxygenRich = sample.oxygen > 0x2456,
        )
    }
}
