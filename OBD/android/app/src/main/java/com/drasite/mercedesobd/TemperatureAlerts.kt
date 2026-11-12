package com.drasite.mercedesobd

/** One audible warning on entry, rearmed when the corresponding temperature recovers. */
internal class TemperatureAlerts {
    private var motorHot = false
    private var intakeHot = false

    fun accept(sample: Sample): Boolean {
        val motor = isHot(sample.values.coolantC, MOTOR_LIMIT)
        val intake = isHot(sample.values.iatC, INTAKE_LIMIT)
        val raised = (motor && !motorHot) || (intake && !intakeHot)
        motorHot = motor
        intakeHot = intake
        return raised
    }

    companion object {
        const val MOTOR_LIMIT = 110
        const val INTAKE_LIMIT = 70

        fun isHot(temperature: Int?, limit: Int) = temperature != null && temperature > limit
    }
}
