package com.drasite.mercedesobd

import java.util.Locale

/** Exactly one fixed 21 01 request per polling cycle; no auxiliary PID/RAM reads. */
class Sim4leReader(
    private val wire: Wire,
    private val log: Events,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = ::monoMs,
) : EcuReader {
    override val ecuProfile: EcuProfile? = null
    override var ecuSoftware: String? = null
        private set
    override var ecuIdentificationStatus = "pending"
        private set
    override val mode = "SIM4LE · 2101"
    override val transportProfile get() = "KWP · ${gap} ms"
    override val faultScope = "confirmed_obd"
    private var gap = 60L
    private var lastBus: Long? = null
    private var suffix = true

    private fun command(value: String, timeout: Long = 6000): String {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val started = now()
        val bus = !value.startsWith("AT")
        val wait = if (bus) lastBus?.let { (gap - (started - it)).coerceAtLeast(0) } ?: 0 else 0
        if (wait > 0) pause(wait)
        val reply = wire.exchange(value, timeout, ExchangeTiming(started, now() - started))
        if (bus) lastBus = now()
        return reply
    }

    private fun at(value: String, optional: Boolean = false) {
        val reply = command(value)
        if (!reply.uppercase(Locale.ROOT).split(Regex("\\s+")).contains("OK") && !optional)
            throw DiagnosticException("$value: ${reply.take(120)}")
    }

    private fun query(request: String, prefix: String, length: Int): ByteArray {
        var reply = command(request + if (suffix) " 1" else "")
        if (reply.trim() == "?" && suffix) {
            suffix = false
            reply = command(request)
        }
        return Sim4leKwp.positive(reply, request, prefix, length)
    }

    override fun identify(notice: (String) -> Unit) {
        at("ATPC")
        notice(tr("Probando SIM4LE · cerrando sesión anterior · 6 s",
            "Trying SIM4LE · closing previous session · 6 s"))
        pause(6000)
        lastBus = null
        gap = 60
        for (cmd in listOf("ATD", "ATE0", "ATL0", "ATS0", "ATH1", "ATM0", "ATSP5", "ATIIA01",
            "ATSH8101F1", "ATKW0", "ATSW00", "ATAT1", "ATST80"))
            at(cmd, cmd in listOf("ATM0", "ATIIA01", "ATKW0", "ATSW00"))
        at("ATAL")
        command("ATFI", 20000) // Some clones initiate automatically on the following request.
        val identity = query("1A94", "5A94", 14).copyOfRange(2, 14).toString(Charsets.US_ASCII)
        if (!identity.matches(Regex("657781\\d{4}[-\\w]{2}")))
            throw DiagnosticException("SIM4LE: unrecognised software family $identity")
        ecuSoftware = identity
        ecuIdentificationStatus = "fixed_layout_checked_5122_5126"
        log.event("ecu_identification", "ecu_family" to "SIM4LE", "software" to identity,
            "status" to ecuIdentificationStatus,
            "group_read_policy" to "2101 fixed 178 bytes; no auxiliary reads",
            "injection_basis" to "base_4us_nominal_720deg", "voltage_source" to "ECU 2101 offset 73")
        notice("SIM4LE · $identity")
    }

    override fun start(tuning: Boolean, publish: (Sample) -> Unit, notice: (String) -> Unit) {
        gap = if (tuning) 0 else 60
        publish(read())
    }

    override fun read(): Sample {
        val start = now()
        val payload = query("2101", "6101", 180)
        val end = now()
        return Sim4leDecoder.decode(payload, end, (end - start).toDouble())
    }

    override fun recover(error: DiagnosticException, consecutive: Int): Int {
        if (consecutive >= 5) throw DiagnosticException(tr("Cinco ciclos SIM4LE sin datos válidos: ",
            "Five SIM4LE cycles without valid data: ") + error.message)
        return consecutive
    }

    override fun readFuelCorrections(): FuelCorrections {
        val values = read().ecuFields
        return FuelCorrections.fromMeasurements(CorrectionMeasurements(
            lowPercent = values["ltft_low_pct"] as Double,
            highPercent = values["ltft_high_pct"] as Double,
            additiveRaw = values["additive_raw"] as Int))
    }

    override fun readFaults(): List<FaultCode> {
        log.event("dtc_read_request", "ecu_family" to "SIM4LE", "service" to "03", "scope" to faultScope)
        // No ELM response-count suffix: the ECU emits every continuation before the prompt.
        val payloads = Sim4leKwp.positives(command("03"), "03", "43", 7)
        val faults = payloads.flatMap { payload ->
            (1..5 step 2).mapNotNull { offset ->
                val code = (payload[offset].u() shl 8) or payload[offset + 1].u()
                if (code == 0 || code == 0xFFFF) null // Padding, or no SAE mapping.
                else FaultCode("%c%01X%03X".format(Locale.ROOT, "PCBU"[code shr 14],
                    (code shr 12) and 3, code and 0xFFF), null, payload.hex(), faultScope)
            }
        }.distinctBy { it.code }
        log.event("dtc_read", "ecu_family" to "SIM4LE", "scope" to faultScope,
            "codes" to faults.map { it.code }, "records" to payloads.map { it.hex() })
        return faults
    }

    override fun clearFaults() {
        log.event("dtc_clear_request", "ecu_family" to "SIM4LE", "service" to "04")
        // Never retry an erase command when its reply is missing or rejected.
        Sim4leKwp.positive(command("04"), "04", "44", 1)
        log.event("dtc_clear", "ecu_family" to "SIM4LE", "acknowledged" to true)
    }
}
