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
    // Other ECU readers supply their own values, leaving unsupported quantities null.
    val measurements: Measurements? = null,
) {
    val values: Measurements = measurements ?: Me21Measurements.from(this)

    // ATRV measures the adapter supply, not the ECU's F734 byte for dead time.
    val injectionMetrics: InjectionMetrics?
        get() = if (measurements == null) Injection.metrics(injection, rpm, null) else null

    val injectionMs
        get() = values.injectionMs

    val duty
        get() = values.dutyPercent

    val retard
        get() = values.retard

    val highRetard
        get() = values.highRetard
}
