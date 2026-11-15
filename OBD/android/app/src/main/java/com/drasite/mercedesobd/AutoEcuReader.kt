package com.drasite.mercedesobd

/** Preserve the ME2.1 startup; only a synchronized rejection tries SIM4LE. */
class AutoEcuReader(
    private val wire: Wire,
    private val log: Events,
    private val me21Factory: (Wire, Events) -> EcuReader = ::Me21Reader,
    private val sim4leFactory: (Wire, Events) -> EcuReader = { w, e -> Sim4leReader(w, e) },
) : EcuReader {
    private var selected: EcuReader? = null
    private val reader get() = checkNotNull(selected) { "ECU reader has not been identified" }
    override val ecuProfile get() = selected?.ecuProfile
    override val ecuSoftware get() = selected?.ecuSoftware
    override val ecuIdentificationStatus get() = selected?.ecuIdentificationStatus ?: "pending"
    override val mode get() = selected?.mode ?: "INICIANDO"
    override val transportProfile get() = selected?.transportProfile ?: ""
    override val faultScope get() = selected?.faultScope

    override fun identify(notice: (String) -> Unit) {
        val me21 = me21Factory(wire, log)
        try {
            me21.identify(notice)
            selected = me21
        } catch (e: LinkTimeout) {
            throw e // A lost prompt needs a new connection, not another ECU request.
        } catch (e: DiagnosticException) {
            log.event("reader_fallback", "from" to "ME2.1", "to" to "SIM4LE", "reason" to e.toString())
            selected = sim4leFactory(wire, log).also { it.identify(notice) }
        }
    }

    override fun start(tuning: Boolean, publish: (Sample) -> Unit, notice: (String) -> Unit) =
        reader.start(tuning, publish, notice)
    override fun read(): Sample = reader.read()
    override fun recover(error: DiagnosticException, consecutive: Int) = reader.recover(error, consecutive)
    override fun readFaults() = reader.readFaults()
    override fun clearFaults() = reader.clearFaults()
    override fun readFuelCorrections() = reader.readFuelCorrections()
}
