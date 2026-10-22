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
    val low: Int?,
    val high: Int?,
    val trimsAt: Long?,
    val trimsStatus: String,
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
    private var status = "Selecciona Demo o un adaptador emparejado"
    private var mode = "PARADO"
    private var profile = ""
    private var errors = 0
    private var failed = false
    private var low: Int? = null
    private var high: Int? = null
    private var trimsAt: Long? = null
    private var trimsStatus = "pendiente"
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
        status = "Preparando sesión"
        mode = "INICIANDO"
        profile = ""
        errors = 0
        failed = false
        low = null
        high = null
        trimsAt = null
        trimsStatus = "pendiente"
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
        status = "Sesión detenida · registros guardados"
    }

    @Synchronized
    fun setEcu(selected: EcuProfile?, software: String?, identificationStatus: String) {
        ecuProfile = selected
        ecuSoftware = software
        ecuIdentificationStatus = identificationStatus
        low = null
        high = null
        trimsAt = null
        trimsStatus = if (selected == null && identificationStatus != "demo") "software sin verificar" else "pendiente"
    }

    @Synchronized
    fun trims(l: Int, h: Int, at: Long) {
        low = l
        high = h
        trimsAt = at
        trimsStatus = "ok"
    }

    @Synchronized
    fun trimError(s: String) {
        trimsStatus = s
    }

    @Synchronized
    fun accept(input: Sample): Pair<Sample, Boolean> {
        val s = input.copy(cycleMs = sample?.let { input.at - it.at })
        val old = previous
        val raisedChannels =
            if (old != null && s.at - old.at in 1..2000 && old.rpm >= 400 && s.rpm >= 400)
                s.corrections.take(4).mapIndexedNotNull { i, value ->
                    if (i < old.corrections.size && value > old.corrections[i]) i + 1 else null
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
        status = if (raised) "Aumento de corrección observado" else "Recibiendo datos"
        fun add(q: ArrayDeque<Double>, v: Double) {
            q.add(v)
            if (q.size > 90) q.removeFirst()
        }
        add(rpm, s.rpm.toDouble())
        add(load, s.load.toDouble())
        add(duty, s.duty)
        if (knock.isNotEmpty() || s.retard > 0) knock.add(s.retard)
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
            low,
            high,
            trimsAt,
            trimsStatus,
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
