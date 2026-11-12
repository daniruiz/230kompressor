"""ME2.1 unit conversions, independent of rendering and adapter I/O."""
from dataclasses import dataclass
import math

if __package__:
    from .dashboard_i18n import tr
    from .measurements import Measurements
else:
    from dashboard_i18n import tr
    from measurements import Measurements

# Provisional scale, not established by disassembly. F734 is an ADC-derived byte;
# ROM 42A2 substitutes 149 below 47. 149 * 3/32 = 13.97 V is only plausible.
VOLTAGE_V_PER_RAW_EST = 3 / 32
# SW404 bank 1, shared by stock and the analysed 230k_ME21 tune.
# 47CA: pointer F734, offset 0, shift 4, count 17; nodes at 47D0..47E0.
DEADTIME_NODES_404 = (112,112,112,112,112,63,43,33,26,21,21,21,21,21,21,21,21)
# 111F -> 81C0; comparator at 159F0 uses ROM 41A8 = 0x2456.
O2_SWITCH_RAW = 0x2456


def measurements(sample, tick_us=8/3):
    """Project legacy ME2.1 bytes into units shared by all dashboard readers."""
    return Measurements(
        rpm=sample.rpm, load=sample.load,
        injection_ms=sample.injection * tick_us / 1000,
        injection_raw=sample.injection,
        duty_percent=duty_percent(sample.injection, sample.rpm, tick_us),
        retard_degrees=tuple(n * .75 for n in sample.corrections),
        temperatures_c=tuple(n - 40 for n in sample.temperatures),
        trims_percent=tuple(trim_percent(n) for n in sample.trims),
        throttle_percent=sample.throttle_raw * 100 / 255 if sample.throttle_raw is not None else None,
        maf_g_s=sample.maf_g_s_est, maf_at_limit=sample.maf_raw == 1023,
        ecu_maf_g_s=ecu_maf_g_s(sample.maf_ecu_raw),
        advance_degrees=advance_degrees(sample.advance_raw) if sample.advance_raw is not None else None,
        oxygen_v=oxygen_voltage(sample.oxygen_raw),
        oxygen_rich=sample.oxygen_raw > O2_SWITCH_RAW if sample.oxygen_raw is not None else None,
    )


def ecu_maf_g_s(raw: int | None) -> float | None:
    """F74C = min(255, floor(processed flow / 40)); one flow unit is 0.02777 g/s.

    SW404 0x11088..0x110A0 and PID10 at 0x3A3CE. This is a quantized
    diagnostic representation after load selection/filtering/limiting,
    not the independent sensor ADC conversion. At 255 this gives a lower bound.
    """
    return None if raw is None else raw * 11108 / 10000


def advance_degrees(raw):
    # 0x3A378..0x3A3A0 converts F759 to PID 0E as floor(3*N/2)-4,
    # clamped 0..255, then OBD decodes A/2-64. Keep native 0.75° precision.
    return raw * 0.75 - 66


def oxygen_voltage(raw):
    """81C0 -> 3B7E4..3B7FA -> 8CFE -> PID 14 at 3A474.

    DPP3=3; ROM E104 contains 0x0DB8 (3512). Subtract with a
    zero floor, clamp to 0x3FBE, shift left 2 and take high byte.
    PID 14 voltage is that byte / 200. No additional diagnostic request.
    """
    if raw is None:
        return None
    return (min(0x3FBE, max(0, raw - 3512)) >> 6) / 200


def trim_percent(raw):
    # Isolated multiplicative contribution in 15222–15276, neutral=0x8000.
    return ((16384 + (raw >> 1))/32768 - 1)*100


def ecu_voltage_est(raw: int | None, scale: float = VOLTAGE_V_PER_RAW_EST) -> float | None:
    """Processed ECU supply estimate, possibly substituted; not adapter ATRV."""
    return None if raw is None else raw * scale


def duty_percent(injection_raw: int, rpm: int, tick_us: float = 8/3) -> float:
    """Estimate duty of the common request; include dead-time compensation.

    tick_us and one pulse per 720 crank degrees remain assumptions to validate.
    F746 is upstream of per-output corrections, scheduling and cuts. It does
    not establish the actual electrical pulse or injector effective flow time.
    """
    if injection_raw < 0 or rpm < 0 or not math.isfinite(tick_us) or tick_us <= 0:
        raise ValueError(tr('Valores inválidos para estimar el duty', 'Invalid values for estimating duty cycle'))
    pulse_ms = injection_raw * tick_us / 1000
    return pulse_ms * rpm / 1200


@dataclass(frozen=True)
class InjectorCalibration:
    nodes: tuple[int, ...] = DEADTIME_NODES_404
    minimum_ticks: int = 187
    source: str = '404 banco 1, constantes fijas'
    sha256: str | None = None


def deadtime_ticks(voltage_raw: int | None, calibration: InjectorCalibration) -> int | None:
    """Reproduce 3C504 + 3C4DA/3C4EE, followed by multiplication by 15.

    ROR by 4 yields the index in RL5 and fraction in RH5. SHR #8 feeds
    carry into ADDCB/SUBCB: round the magnitude of the delta, ties upward,
    then add/subtract it. Python round() would disagree at half steps.
    C166 ISA: https://www.keil.com/dd/docs/datashts/infineon/c166ism.pdf
    No physical battery-voltage or injector-clock assumption enters this.
    """
    if voltage_raw is None:
        return None
    if not isinstance(voltage_raw, int) or not 0 <= voltage_raw <= 255:
        raise ValueError(tr('F734 debe ser un byte', 'F734 must be a byte'))
    if len(calibration.nodes) != 17 or any(not 0 <= x <= 255 for x in calibration.nodes):
        raise ValueError(tr('Se requieren 17 nodos de un byte', '17 one-byte nodes are required'))
    index, fraction = voltage_raw >> 4, (voltage_raw & 15) << 4
    a, b = calibration.nodes[index:index+2]
    delta = (abs(b-a)*fraction+128) >> 8
    return 15*(a+delta if b >= a else a-delta)


def injection_metrics(sample, args) -> dict:
    """Same calculation for UI, CSV and JSONL; no additional ECU requests."""
    if sample.measurements is not None:
        values = sample.measurements
        return dict(injection_ms_est=values.injection_ms,
                    injector_duty_est_pct=values.duty_percent,
                    duty_over_100_est=values.duty_percent > 100 if values.duty_percent is not None else None,
                    injection_breakdown_status='reader_values')
    cal = args.injector_calibration
    raw = sample.injection
    total_ms = raw*args.tick_us/1000
    duty = duty_percent(raw, sample.rpm, args.tick_us)
    dead = deadtime_ticks(sample.voltage_raw, cal)
    net = None
    if sample.rpm == 0:
        status = 'engine_stopped_request_may_be_retained'
    elif raw == 65535:
        status = 'F746_saturated_cannot_reverse'
    elif dead is None:
        status = 'missing_F734'
    elif sample.rpm < 600:
        status = 'low_rpm_start_path_not_excluded'
    elif raw-dead < cal.minimum_ticks:
        status = 'below_running_minimum_path_not_applicable'
    else:
        # A mathematical decomposition of the normal running path, not a
        # measurement of effective injector flow or proof that fuel is on.
        net = raw-dead
        status = 'running_path_assumed'
    return dict(F746_raw=raw, injection_ms_est=total_ms,
                injector_duty_est_pct=duty, duty_over_100_est=duty > 100,
                injection_cycle_720_ms=120000/sample.rpm if sample.rpm else None,
                injection_cycle_margin_ms_est=(120000/sample.rpm-total_ms) if sample.rpm else None,
                F8C6_calc_raw=dead,
                injector_deadtime_ms_est=dead*args.tick_us/1000 if dead is not None else None,
                injection_before_deadtime_raw=net,
                injection_before_deadtime_ms_est=net*args.tick_us/1000 if net is not None else None,
                injector_deadtime_fraction_pct=100*dead/raw if dead is not None and raw and net is not None else None,
                injection_breakdown_status=status,
                injection_F746_saturated=raw == 65535)
