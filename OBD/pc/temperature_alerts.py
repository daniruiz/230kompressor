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
        """Keep warning until valid readings confirm both temperatures recovered."""
        self._hot = tuple(previous if t is None else self.is_hot(t, limit)
                          for t, limit, previous in zip(values.temperatures_c,
                              (self.MOTOR_LIMIT, self.INTAKE_LIMIT), self._hot))
        return any(self._hot)
