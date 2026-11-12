"""Temperature warnings shared by demo and live readers; thresholds match Android."""


class TemperatureAlerts:
    MOTOR_LIMIT = 110
    INTAKE_LIMIT = 70

    def __init__(self):
        self._hot = (False, False)

    @staticmethod
    def is_hot(temperature, limit):
        return temperature is not None and temperature > limit

    def accept(self, values):
        """Warn once on entry; each temperature rearms when it recovers."""
        hot = tuple(self.is_hot(t, limit) for t, limit in zip(
            values.temperatures_c, (self.MOTOR_LIMIT, self.INTAKE_LIMIT)))
        raised = any(current and not previous for current, previous in zip(hot, self._hot))
        self._hot = hot
        return raised
