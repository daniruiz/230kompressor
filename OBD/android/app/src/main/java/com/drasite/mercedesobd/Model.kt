package com.drasite.mercedesobd

import java.util.ArrayDeque

data class Display(
    val sample: Sample?,
    val previous: Sample?,
    val count: Int,
    val alertUntil: Long,
    val lastAlertAt: Long?,
    val lastAlertChannels: List<Int>,
    val status: String,
    val mode: String,
    val profile: String,
    val errors: Int,
    val failed: Boolean,
    val ecuProfile: EcuProfile?,
    val ecuSoftware: String?,
    val ecuIdentificationStatus: String,
    val rpm: DoubleArray,
    val load: DoubleArray,
    val duty: DoubleArray,
    val knock: DoubleArray,
    val hz: Double,
)

class Model {
    private var sample: Sample? = null
    private var previous: Sample? = null
    private var count = 0
    private var alertUntil = 0L
    private var lastAlertAt: Long? = null
    private var lastAlertChannels = emptyList<Int>()
    private var status = tr("Selecciona Demo o un adaptador emparejado", "Select Demo or a paired adapter")
    private var mode = "PARADO"
    private var profile = ""
    private var errors = 0
    private var failed = false
    private var ecuProfile: EcuProfile? = null
    private var ecuSoftware: String? = null
    private var ecuIdentificationStatus = "pending"
    private val rpm = ArrayDeque<Double>()
    private val load = ArrayDeque<Double>()
    private val duty = ArrayDeque<Double>()
    private val knock = ArrayList<Double>()
    private val times = ArrayDeque<Long>()

    @Synchronized
    fun clear() {
        sample = null
        previous = null
        count = 0
        alertUntil = 0
        lastAlertAt = null
        lastAlertChannels = emptyList()
        status = tr("Preparando sesión", "Preparing session")
        mode = "INICIANDO"
        profile = ""
        errors = 0
        failed = false
        ecuProfile = null
        ecuSoftware = null
        ecuIdentificationStatus = "pending"
        rpm.clear()
        load.clear()
        duty.clear()
        knock.clear()
        times.clear()
    }

    @Synchronized
    fun notice(s: String) {
        status = s
    }

    @Synchronized
    fun mode(s: String, p: String = "") {
        mode = s
        profile = p
    }

    @Synchronized
    fun reject(s: String) {
        errors++
        failed = true
        previous = null
        status = s
    }

    @Synchronized
    fun stop() {
        failed = true
        previous = null
        mode = "PARADO"
        status = if (ecuIdentificationStatus == "demo") tr("Demo detenida", "Demo stopped")
            else tr("Sesión detenida · registros guardados", "Session stopped · logs saved")
    }

    @Synchronized
    fun setEcu(selected: EcuProfile?, software: String?, identificationStatus: String) {
        ecuProfile = selected
        ecuSoftware = software
        ecuIdentificationStatus = identificationStatus
    }

    @Synchronized
    fun accept(input: Sample): Pair<Sample, Boolean> {
        val s = input.copy(cycleMs = sample?.let { input.at - it.at })
        val old = previous
        val values = s.values
        val oldValues = old?.values
        val raisedChannels =
            if (old != null && oldValues != null && s.at - old.at in 1..2000 &&
                (oldValues.rpm ?: 0) >= 400 && (values.rpm ?: 0) >= 400)
                values.retardDegrees.take(4).mapIndexedNotNull { i, value ->
                    if (i < oldValues.retardDegrees.size && value > oldValues.retardDegrees[i]) i + 1 else null
                }
            else emptyList()
        val raised = raisedChannels.isNotEmpty()
        if (raised) {
            count++
            alertUntil = s.at + 3000
            lastAlertAt = s.at
            lastAlertChannels = raisedChannels
        }
        previous = s
        sample = s
        failed = false
        status = if (raised) tr("Aumento de corrección observado", "Correction increase observed") else tr("Recibiendo datos", "Receiving data")
        fun add(q: ArrayDeque<Double>, v: Double?) {
            if (v == null) { q.clear(); return }
            q.add(v)
            if (q.size > 90) q.removeFirst()
        }
        add(rpm, values.rpm?.toDouble())
        add(load, values.load?.toDouble())
        add(duty, values.dutyPercent)
        values.retard?.let { if (knock.isNotEmpty() || it > 0) knock.add(it) }
        times.add(s.at)
        if (times.size > 40) times.removeFirst()
        return s to raised
    }

    @Synchronized
    fun snapshot(bins: Int = 800): Display {
        val history =
            if (bins <= 0) doubleArrayOf()
            else if (knock.size <= bins) knock.toDoubleArray()
            else
                DoubleArray(bins) { i ->
                    var peak = 0.0
                    for (j in i * knock.size / bins until (i + 1) * knock.size / bins) peak =
                        maxOf(peak, knock[j])
                    peak
                }
        val span = if (times.size > 1) times.last - times.first else 0
        return Display(
            sample,
            previous,
            count,
            alertUntil,
            lastAlertAt,
            lastAlertChannels,
            status,
            mode,
            profile,
            errors,
            failed,
            ecuProfile,
            ecuSoftware,
            ecuIdentificationStatus,
            rpm.toDoubleArray(),
            load.toDoubleArray(),
            duty.toDoubleArray(),
            history,
            if (span > 0) (times.size - 1) * 1000.0 / span else 0.0,
        )
    }
}
