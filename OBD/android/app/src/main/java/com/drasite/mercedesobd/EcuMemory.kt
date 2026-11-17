package com.drasite.mercedesobd

/** Optional preference only: the reader still verifies the actual ECU at every connection. */
class EcuMemory(
    private val load: (String) -> String?,
    private val save: (String, String) -> Unit,
) {
    fun preferred(): String? {
        return try { load(KEY)?.takeIf { it == "ME2.1" || it == "SIM4LE" } }
        catch (_: RuntimeException) { null } // Corrupt/unavailable preferences must not block connection.
    }

    fun remember(family: String) {
        if (family == "ME2.1" || family == "SIM4LE") save(KEY, family)
    }

    private companion object {
        const val KEY = "last_ecu_family"
    }
}
