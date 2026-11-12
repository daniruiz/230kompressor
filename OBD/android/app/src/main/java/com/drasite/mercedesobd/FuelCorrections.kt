package com.drasite.mercedesobd

data class CorrectionMeasurements(
    val lowPercent: Double? = null,
    val highPercent: Double? = null,
    val additiveMs: Double? = null,
    val additiveRaw: Int? = null,
)

/** One complete on-demand read, never a cached live dashboard sample. */
data class FuelCorrections(val low: Int, val high: Int, val additive: Int,
    val measurements: CorrectionMeasurements? = null) {
    companion object {
        fun fromMeasurements(values: CorrectionMeasurements) = FuelCorrections(0, 0, 0, values)
    }

    // SW404: 025942 reads learned adaptation at 9020; 02595C..02597A clamp
    // it with signed comparisons. 025AAA..025AC8 applies it to injection.
    // The signed diagnostic export is also present in all ten originals;
    // SW643 uses 9220. Time conversion shares the provisional injection scale.
    val additiveSigned: Int get() = additive.toShort().toInt()
    val values = measurements ?: CorrectionMeasurements(Decoder.trim(low), Decoder.trim(high),
        additiveSigned * Decoder.TICK_US / 1000, additiveSigned)
    val additiveMs: Double? get() = values.additiveMs

    fun logValues(): Array<Pair<String, Any?>> = arrayOf(
        "ltft_low_raw" to low.takeIf { measurements == null },
        "ltft_high_raw" to high.takeIf { measurements == null },
        "ltft_low_pct" to values.lowPercent, "ltft_high_pct" to values.highPercent,
        "additive_raw" to additive.takeIf { measurements == null },
        "additive_signed_raw" to values.additiveRaw,
        "additive_ms_est" to values.additiveMs,
        "additive_tick_us_assumed" to Decoder.TICK_US.takeIf { measurements == null },
    )
}
