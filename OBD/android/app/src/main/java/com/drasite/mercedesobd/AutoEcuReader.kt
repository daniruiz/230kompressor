package com.drasite.mercedesobd

/** Try the last working family first; only synchronized failures permit retries. */
class AutoEcuReader(
    private val wire: Wire,
    private val log: Events,
    private val me21Factory: (Wire, Events) -> EcuReader = ::Me21Reader,
    private val sim4leFactory: (Wire, Events) -> EcuReader = { w, e -> Sim4leReader(w, e) },
    private val preferredFamily: String? = null,
    private val recordFamily: (String) -> Unit = {},
) : EcuReader {
    private var selected: EcuReader? = null
    private var selectedFamily: String? = null
    private var recorded = false
    private val reader get() = checkNotNull(selected) { "ECU reader has not been identified" }
    override val ecuProfile get() = selected?.ecuProfile
    override val ecuSoftware get() = selected?.ecuSoftware
    override val ecuIdentificationStatus get() = selected?.ecuIdentificationStatus ?: "pending"
    override val mode get() = selected?.mode ?: "INICIANDO"
    override val transportProfile get() = selected?.transportProfile ?: ""
    override val faultScope get() = selected?.faultScope

    override fun identify(notice: (String) -> Unit) {
        selected = null
        selectedFamily = null
        recorded = false
        val preferred = if (preferredFamily == "SIM4LE") "SIM4LE" else "ME2.1"
        val other = if (preferred == "ME2.1") "SIM4LE" else "ME2.1"
        val attempts = listOf(Triple(preferred, 0L, false), Triple(preferred, 3000L, false),
            Triple(other, 6000L, true), Triple(preferred, 6000L, true))
        var lastError: DiagnosticException? = null
        for ((family, wait, retry) in attempts) {
            val candidate = if (family == "ME2.1") me21Factory(wire, log) else sim4leFactory(wire, log)
            log.event("ecu_family_attempt", "family" to family, "initial_wait_ms" to wait,
                "retry" to retry)
            try {
                candidate.identify(notice, wait, retry)
                selected = candidate
                selectedFamily = family
                return
            } catch (e: LinkTimeout) {
                throw e // Lost framing requires a new connection, never another ECU request.
            } catch (e: DiagnosticException) {
                lastError = e
                log.event("ecu_family_attempt_failed", "family" to family, "reason" to e.toString())
            }
        }
        throw checkNotNull(lastError)
    }

    private fun rememberAfterSample() {
        if (recorded) return
        recorded = true
        try {
            recordFamily(checkNotNull(selectedFamily))
        } catch (e: RuntimeException) {
            log.event("ecu_family_cache_error", "reason" to e.toString())
        }
    }

    override fun start(tuning: Boolean, publish: (Sample) -> Unit, notice: (String) -> Unit) =
        reader.start(tuning, { sample -> rememberAfterSample(); publish(sample) }, notice)
    override fun read(): Sample = reader.read().also { rememberAfterSample() }
    override fun recover(error: DiagnosticException, consecutive: Int) = reader.recover(error, consecutive)
    override fun readFaults() = reader.readFaults()
    override fun clearFaults() = reader.clearFaults()
    override fun readFuelCorrections() = reader.readFuelCorrections()
}
