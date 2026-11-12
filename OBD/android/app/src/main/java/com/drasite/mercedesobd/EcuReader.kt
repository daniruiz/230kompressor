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
        log.event(
            "reader_configuration",
            "reader" to "ME2.1",
            "tick_us_assumed" to Decoder.TICK_US,
            "injection_tick_conversion_verified" to false,
            "injection_duty_basis" to "F746 common request; one pulse per 720 degrees assumed; not measured output duty",
            "injector_deadtime_source" to Injection.calibration.source,
            "injector_calibration_sha256" to Injection.calibration.sha256,
            "injector_deadtime_curve" to Injection.calibration.nodes,
            "injector_deadtime_is_direct_ram_read" to false,
            "injector_minimum_ticks" to Injection.calibration.minimumTicks,
            "voltage_scale_V_per_raw_assumed" to null,
            "voltage_conversion_verified" to false,
            "ecu_voltage_acquired" to false,
            "adapter_voltage_source" to "ATRV",
            "adapter_voltage_interval_ms" to 5000,
            "correction_deg_per_raw_nominal" to 0.75,
            "detector" to "observed_correction_increase_NOT_ECU_knock_counter",
            "transport_revision" to "native_spp_stn_p3_and_elm_host_gap_v3",
            "adaptive" to "0",
            "response_timeout_ms" to 512,
            "request_gap_ms" to 60,
            "exchange_timing_basis" to "host_receive_batches_not_kline",
            "diagnostic_ids" to Decoder.ids.map { "11%02X".format(it) },
            "decoder_revision" to "12_ids_sensor_maf_1143_ecu_maf_load_1107_fixed_ltft_v6",
            "group_read_policy" to "fixed 12 identifiers; one grouped request per acquisition",
            "maf_source" to "grouped_1143_adc",
            "maf_calibration_source" to MafCalibration.SOURCE,
            "maf_curve_sha256" to MafCalibration.SHA256,
            "maf_conversion_basis" to "instantaneous stock curve; excludes ECU averaging and conditional correction",
            "maf_calibration_policy" to "original common curve also assumed for unverified software; modified curves require their own calibration",
            "maf_ecu_source" to "grouped_1107_processed",
            "maf_ecu_scale_g_s_per_raw" to 1.1108,
            "maf_ecu_conversion_basis" to "quantized processed airflow after load selection/filtering/limiting; capped at raw 255",
            "ecu_identification_policy" to "ROM software reference; once at connection",
            "supported_ecu_software" to EcuProfiles.all.map { it.software },
        )
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
