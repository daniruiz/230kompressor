package com.drasite.mercedesobd

data class Sample(
    val rpm: Int,
    val injection: Int,
    val load: Int,
    val corrections: IntArray,
    val stft: Int = 32768,
    val ltft: Int = 32768,
    val coolant: Int = 130,
    val iat: Int = 78,
    val maf: Int = 0,
    val mafAdcWord: Int? = null,
    val mafGramsPerSecond: Double? = null,
    val mafCalibrationStatus: String = "unverified_software",
    val mafEcuRaw: Int? = null,
    val advance: Int = 60,
    val throttle: Int = 0,
    val oxygen: Int = 0,
    val diagnosticIds: List<Int> = emptyList(),
    val pairs: String = "",
    val at: Long = monoMs(),
    val acquisitionMs: Double = 0.0,
    val cycleMs: Long? = null,
    val adapterVoltage: Double? = null,
    val adapterVoltageAgeMs: Long? = null,
    val adapterVoltageStatus: String = "pending",
) {
    // ATRV measures the adapter supply, not the ECU's F734 byte for dead time.
    val injectionMetrics = Injection.metrics(injection, rpm, null)

    val injectionMs
        get() = injectionMetrics.totalMs

    val duty
        get() = injectionMetrics.duty

    val retard
        get() = (corrections.maxOrNull() ?: 0) * .75

    val highRetard
        get() = retard > Decoder.KNOCK_ALERT_THRESHOLD_DEG
}

