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
    val mafEcuRaw: Int? = null,
    val bypassRaw: Int? = null,
    val bypassLimitRaw: Int? = null,
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
    val ecuFamily: String = "ME2.1",
    val ecuRaw: ByteArray = byteArrayOf(),
    val ecuFields: Map<String, Any?> = emptyMap(),
) {
    companion object {
        /** Construct a sample without inventing ME2.1 raw readings for another ECU. */
        fun fromMeasurements(values: Measurements, at: Long = monoMs(), acquisitionMs: Double = 0.0,
            ecuFamily: String = "ME2.1", ecuRaw: ByteArray = byteArrayOf(),
            ecuFields: Map<String, Any?> = emptyMap()): Sample =
            Sample(rpm = values.rpm ?: 0, injection = 0, load = values.load ?: 0,
                corrections = intArrayOf(), at = at, acquisitionMs = acquisitionMs, measurements = values,
                ecuFamily = ecuFamily, ecuRaw = ecuRaw.copyOf(), ecuFields = ecuFields)
    }

    // ATRV measures the adapter supply, not the ECU's F734 byte for dead time.
    val injectionMetrics: InjectionMetrics? =
        if (measurements == null) Injection.metrics(injection, rpm, null) else null

    val values: Measurements = measurements ?: Me21Measurements.from(this, requireNotNull(injectionMetrics))
}
