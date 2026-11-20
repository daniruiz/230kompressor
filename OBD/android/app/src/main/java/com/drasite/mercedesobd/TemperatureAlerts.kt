package com.drasite.mercedesobd

/** Keep warning until valid readings confirm that both temperatures have recovered. */
internal class TemperatureAlerts {
    private var motorHot = false
    private var intakeHot = false

    fun accept(sample: Sample): Boolean {
        sample.values.coolantC?.let { motorHot = isHot(it, MOTOR_LIMIT) }
        sample.values.iatC?.let { intakeHot = isHot(it, INTAKE_LIMIT) }
        return motorHot || intakeHot
    }

    companion object {
        const val MOTOR_LIMIT = 110
        const val INTAKE_LIMIT = 70

        fun isHot(temperature: Int?, limit: Int) = temperature != null && temperature > limit
    }
}
