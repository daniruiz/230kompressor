package com.drasite.mercedesobd

/** One audible warning on entry, rearmed when the corresponding temperature recovers. */
internal class TemperatureAlerts {
    private var motorHot = false
    private var intakeHot = false

    fun accept(sample: Sample): Boolean {
        val motor = sample.coolant - 40 > MOTOR_LIMIT
        val intake = sample.iat - 40 > INTAKE_LIMIT
        val raised = (motor && !motorHot) || (intake && !intakeHot)
        motorHot = motor
        intakeHot = intake
        return raised
    }

    companion object {
        const val MOTOR_LIMIT = 110
        const val INTAKE_LIMIT = 70
    }
}
