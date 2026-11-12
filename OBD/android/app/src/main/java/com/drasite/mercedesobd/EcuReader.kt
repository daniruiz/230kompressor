package com.drasite.mercedesobd

/** Acquisition-thread boundary; implementations own ECU requests and recovery.
 *
 * Inject an implementation through Session.readerFactory; Bluetooth lifetime
 * remains in Session. All reads and menu diagnostics are serialized there.
 * A future SIM4LE reader returns Sample.fromMeasurements with unsupported units
 * left null, and FuelCorrections.fromMeasurements for on-demand results.
 * Do not encode other ECU values as ME2.1 bytes. Family-specific raw logs/CSV
 * columns need their own exporter extension. This API adds no ECU autodetection.
 */
interface EcuReader {
    val ecuProfile: EcuProfile?
    val ecuSoftware: String?
    val ecuIdentificationStatus: String
    val mode: String
    val transportProfile: String
    fun identify(notice: (String) -> Unit)
    fun start(tuning: Boolean, publish: (Sample) -> Unit, notice: (String) -> Unit)
    fun read(): Sample
    fun recover(error: DiagnosticException, consecutive: Int): Int
    fun readFaults(): List<FaultCode>
    fun clearFaults()
    fun readFuelCorrections(): FuelCorrections
}

/** Keeps the existing ME2.1 protocol and timing behaviour behind the reader API. */
class Me21Reader(wire: Wire, private val log: Events) : EcuReader {
    private val elm = Elm(wire, log)
    private var group = true
    override val ecuProfile get() = elm.ecuProfile
    override val ecuSoftware get() = elm.ecuSoftware
    override val ecuIdentificationStatus get() = elm.ecuIdentificationStatus
    override val mode get() = if (group) "AGRUPADO" else "INDIVIDUAL"
    override val transportProfile get() = elm.profile

    override fun identify(notice: (String) -> Unit) {
        elm.setup(notice)
        elm.identifyEcu(notice)
    }

    override fun start(tuning: Boolean, publish: (Sample) -> Unit, notice: (String) -> Unit) {
        try {
            publish(elm.defineGroup())
        } catch (e: LinkTimeout) {
            throw e
        } catch (e: DiagnosticException) {
            log.event("fallback", "reason" to e.toString())
            notice(tr("Grupo rechazado; lecturas individuales", "Group rejected; individual reads"))
            group = false
        }
        if (tuning) elm.optimize(group, publish, notice)
        elm.enableAdapterVoltagePolling()
    }

    override fun read(): Sample = elm.acquire(group)

    override fun recover(error: DiagnosticException, consecutive: Int): Int {
        if (elm.rollback(error.message ?: "Error")) return consecutive
        if (group && consecutive >= 3) {
            elm.verifyLink()
            group = false
            log.event("fallback", "reason" to tr("3 errores de grupo; RPM individual válida", "3 errors de grupo; RPM individual válida"))
            return 0
        }
        if (consecutive >= 5)
            throw DiagnosticException(tr("Cinco ciclos sin datos válidos", "Five cycles without valid data"))
        return consecutive
    }

    override fun readFaults() = elm.readFaults()
    override fun clearFaults() = elm.clearFaults()
    override fun readFuelCorrections() = elm.readFuelCorrections()
}
