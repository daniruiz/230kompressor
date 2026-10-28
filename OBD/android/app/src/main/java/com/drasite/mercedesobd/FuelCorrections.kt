package com.drasite.mercedesobd

/** One complete on-demand read, never a cached live dashboard sample. */
data class FuelCorrections(val low: Int, val high: Int, val additive: Int) {
    // SW404: 025942 reads learned adaptation at 9020; 02595C..02597A clamp
    // it with signed comparisons. 025AAA..025AC8 applies it to injection.
    // The signed diagnostic export is also present in all ten originals;
    // SW643 uses 9220. Time conversion shares the provisional injection scale.
    val additiveSigned: Int get() = additive.toShort().toInt()
    val additiveMs: Double get() = additiveSigned * Decoder.TICK_US / 1000

    fun logValues(): Array<Pair<String, Any?>> = arrayOf(
        "ltft_low_raw" to low, "ltft_high_raw" to high,
        "ltft_low_pct" to Decoder.trim(low), "ltft_high_pct" to Decoder.trim(high),
        "additive_raw" to additive, "additive_signed_raw" to additiveSigned,
        "additive_ms_est" to additiveMs, "additive_tick_us_assumed" to Decoder.TICK_US,
    )
}
