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


def measurements(sample, tick_us=8/3):
    """Project legacy ME2.1 bytes into units shared by all dashboard readers."""
    airflow = ecu_maf_g_s(sample.maf_ecu_raw)
    return Measurements(
        rpm=sample.rpm, load=sample.load,
        injection_ms=sample.injection * tick_us / 1000,
        injection_raw=sample.injection,
        duty_percent=duty_percent(sample.injection, sample.rpm, tick_us),
        retard_degrees=tuple(n * .75 for n in sample.corrections),
        temperatures_c=tuple(n - 40 for n in sample.temperatures),
        trims_percent=tuple(trim_percent(n) for n in sample.trims),
        throttle_percent=sample.throttle_raw * 100 / 255 if sample.throttle_raw is not None else None,
        # 118A exports a normalized command, not a measured angle or boost pressure.
        bypass_percent=sample.bypass_raw * 100 / 255 if sample.bypass_raw is not None else None,
        maf_g_s=airflow,
        maf_at_limit=sample.maf_ecu_raw == 255 if sample.maf_ecu_raw is not None else None,
        ecu_maf_g_s=airflow,
        advance_degrees=advance_degrees(sample.advance_raw) if sample.advance_raw is not None else None,
        oxygen_v=oxygen_voltage(sample.oxygen_raw),
    )


def ecu_maf_g_s(raw: int | None) -> float | None:
    """F74C = min(255, floor(processed flow / 40)); one flow unit is 0.02777 g/s.

    SW404 0x11088..0x110A0 and PID10 at 0x3A3CE. This is a quantized
    diagnostic representation after load selection/filtering/limiting,
    not the independent sensor ADC conversion. At 255 this gives a lower bound.
    """
    return None if raw is None else raw * 11108 / 10000


def advance_degrees(raw):
    # ID 110B exports F759. SW404 010A82..010B1E / 0089E2..0089F8
    # schedule larger raw values later; knock retard is added at 010874.
    # Normalize to positive before TDC, retaining raw 88 as the zero reference.
    return 66 - raw * 0.75


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
        base_only = values.injection_basis.startswith('base_')
        return dict(injection_ms=values.injection_ms,
                    injector_duty_pct=values.duty_percent,
                    duty_over_100=values.duty_percent > 100 if values.duty_percent is not None and not base_only else None,
                    injection_breakdown_status=values.injection_basis if base_only else 'reader_values')
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
    return dict(F746_raw=raw, injection_ms=total_ms,
                injector_duty_pct=duty, duty_over_100=duty > 100,
                injection_cycle_720_ms=120000/sample.rpm if sample.rpm else None,
                injection_cycle_margin_ms=(120000/sample.rpm-total_ms) if sample.rpm else None,
                F8C6_calc_raw=dead,
                injector_deadtime_ms=dead*args.tick_us/1000 if dead is not None else None,
                injection_before_deadtime_raw=net,
                injection_before_deadtime_ms=net*args.tick_us/1000 if net is not None else None,
                injector_deadtime_fraction_pct=100*dead/raw if dead is not None and raw and net is not None else None,
                injection_breakdown_status=status,
                injection_F746_saturated=raw == 65535)
