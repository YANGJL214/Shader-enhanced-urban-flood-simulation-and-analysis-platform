#! /usr/bin/env python
import numpy as np
import anuga
from anuga.operators.base_operator import Operator


class HortonInfiltrationOperator(Operator):
    """Horton infiltration sink for ANUGA centroid quantities.

    The operator removes water and the corresponding horizontal momentum from
    each cell, so the remaining overland water keeps the same velocity after
    vertical infiltration losses.
    """

    def __init__(self, domain, max_rate, min_rate, decay,
                 drying_time_days=7.0, max_infiltration=0.0,
                 min_depth=1.0e-9, label='horton_infiltration'):
        Operator.__init__(self, domain, label=label)

        self.n = len(self.stage_c)
        self.max_rate = self._as_array(max_rate, 'max_rate')
        self.min_rate = self._as_array(min_rate, 'min_rate')
        self.decay = self._as_array(decay, 'decay')
        self.max_infiltration = self._as_array(
            max_infiltration, 'max_infiltration')
        self.min_depth = float(min_depth)

        if np.any(self.max_rate < 0.0):
            raise ValueError('Horton max_rate values must be non-negative')
        if np.any(self.min_rate < 0.0):
            raise ValueError('Horton min_rate values must be non-negative')
        if np.any(self.decay < 0.0):
            raise ValueError('Horton decay values must be non-negative')
        if np.any(self.max_infiltration < 0.0):
            raise ValueError('Horton max_infiltration values must be non-negative')
        if np.any(self.max_rate < self.min_rate):
            raise ValueError('Horton max_rate must be >= min_rate')

        dry_days = self._as_array(drying_time_days, 'drying_time_days')
        if np.any(dry_days <= 0.0):
            raise ValueError('Horton drying_time_days values must be positive')
        self.regen = -np.log(1.0 - 0.98) / (dry_days * 86400.0)

        self.tp = np.zeros(self.n, dtype=float)
        self.capacity_used = np.zeros(self.n, dtype=float)
        self.local_influx = 0.0

        self._ensure_quantity('infiltration_rate')
        self._ensure_quantity('cumulative_infiltration')
        self.rate_c = self.domain.quantities[
            'infiltration_rate'].centroid_values
        self.cumulative_c = self.domain.quantities[
            'cumulative_infiltration'].centroid_values

    def _as_array(self, value, name):
        arr = np.asarray(value, dtype=float)
        if arr.ndim == 0:
            return np.full(self.n, float(arr), dtype=float)
        arr = arr.reshape(-1)
        if arr.size != self.n:
            raise ValueError(
                'Horton parameter %s length %s does not match ANUGA cell '
                'count %s' % (name, arr.size, self.n))
        return arr

    def _ensure_quantity(self, name):
        if name not in self.domain.quantities:
            anuga.Quantity(self.domain, name=name, register=True)
        self.domain.set_quantity(name, 0.0, location='centroids')

    @staticmethod
    def _curve_volume(t, max_rate, min_rate, decay):
        tlim = 16.0 / decay
        return np.where(
            t >= tlim,
            min_rate * t + (max_rate - min_rate) / decay,
            min_rate * t
            + (max_rate - min_rate) / decay * (1.0 - np.exp(-decay * t))
        )

    def __call__(self):
        dt = float(self.domain.get_timestep())
        if dt <= 0.0:
            return

        h0 = np.maximum(self.stage_c - self.elev_c, 0.0)
        available_rate = h0 / dt
        actual_rate = np.zeros(self.n, dtype=float)

        delta_rate = self.max_rate - self.min_rate
        active = available_rate > 0.0
        constant = active & ((delta_rate == 0.0) | (self.decay == 0.0))
        curved = active & ~constant

        actual_rate[constant] = np.minimum(
            self.max_rate[constant], available_rate[constant])

        if np.any(curved):
            idx = np.where(curved)[0]
            tp0 = self.tp[idx]
            t1 = tp0 + dt
            fp0 = self._curve_volume(
                tp0, self.max_rate[idx], self.min_rate[idx], self.decay[idx])
            fp1 = self._curve_volume(
                t1, self.max_rate[idx], self.min_rate[idx], self.decay[idx])
            potential = np.maximum((fp1 - fp0) / dt, self.min_rate[idx])
            potential = np.minimum(potential, available_rate[idx])

            full_capacity = potential < available_rate[idx]
            self.tp[idx[full_capacity]] = t1[full_capacity]

            water_limited = ~full_capacity
            if np.any(water_limited):
                j = idx[water_limited]
                target = fp0[water_limited] + potential[water_limited] * dt
                tp = tp0[water_limited] + 0.5 * dt
                for _ in range(20):
                    exp_term = np.exp(-np.minimum(60.0, self.decay[j] * tp))
                    residual = (
                        self.min_rate[j] * tp
                        + delta_rate[j] / self.decay[j] * (1.0 - exp_term)
                        - target
                    )
                    derivative = self.min_rate[j] + delta_rate[j] * exp_term
                    step = residual / np.maximum(derivative, 1.0e-30)
                    tp -= step
                    if np.max(np.abs(step)) <= 0.001 * dt:
                        break
                self.tp[j] = np.maximum(tp, 0.0)

            actual_rate[idx] = potential

        capacity_limited = self.max_infiltration > 0.0
        if np.any(capacity_limited):
            remaining = np.maximum(
                self.max_infiltration - self.capacity_used, 0.0)
            actual_rate[capacity_limited] = np.minimum(
                actual_rate[capacity_limited],
                remaining[capacity_limited] / dt
            )

        dry = (~active) & (self.regen > 0.0) & (self.decay > 0.0)
        if np.any(dry):
            recover = np.exp(-self.regen[dry] * dt)
            z = 1.0 - np.exp(-self.decay[dry] * self.tp[dry])
            self.tp[dry] = -np.log(
                np.maximum(1.0e-30, 1.0 - recover * z)) / self.decay[dry]
            if np.any(self.max_infiltration[dry] > 0.0):
                self.capacity_used[dry] = self._curve_volume(
                    self.tp[dry],
                    self.max_rate[dry],
                    self.min_rate[dry],
                    self.decay[dry]
                )
                self.capacity_used[dry] = np.minimum(
                    self.capacity_used[dry], self.max_infiltration[dry])

        actual_rate = np.minimum(actual_rate, available_rate)
        actual_rate[h0 <= self.min_depth] = 0.0

        dh = actual_rate * dt
        h1 = np.maximum(h0 - dh, 0.0)

        momentum_ratio = np.zeros_like(h0)
        wet = h0 > self.min_depth
        momentum_ratio[wet] = h1[wet] / h0[wet]

        self.stage_c[:] = self.elev_c + h1
        self.xmom_c[:] *= momentum_ratio
        self.ymom_c[:] *= momentum_ratio

        if 'height' in self.domain.quantities:
            self.domain.quantities['height'].set_values(
                h1, location='centroids')

        self.rate_c[:] = actual_rate
        self.cumulative_c[:] += dh
        self.capacity_used[:] += dh

        full_flag = getattr(self.domain, 'tri_full_flag', np.ones(self.n))
        volume_removed = np.sum(dh * self.areas * full_flag)
        self.local_influx = -volume_removed
        self.domain.fractional_step_volume_integral += self.local_influx

    def parallel_safe(self):
        return False

    def statistics(self):
        return self.label + ': HortonInfiltrationOperator'

    def timestepping_statistics(self):
        from anuga import indent

        total_rate = float(np.sum(self.rate_c * self.areas))
        return (
            indent + self.label
            + ': Horton infiltration, time %.3f, Q %.6g m3/s'
            % (self.get_time(), total_rate)
        )
