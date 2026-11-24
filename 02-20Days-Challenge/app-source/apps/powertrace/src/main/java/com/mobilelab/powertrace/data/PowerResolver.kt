package com.mobilelab.powertrace.data

/** Pure-JVM capability resolver. Caller must pass one condition from one campaign only.
 * Uses documented Android units, never guesses a manufacturer's mA/µA scale.
 * No SOC estimates are injected into measured per-point power.
 */
object PowerResolver {
    private const val MIN_WINDOW_MS = 60_000L
    private const val MAX_WINDOW_MS = 28_800_000L

    private val pauseMarker = Regex("(?:^|;\\s*)power_paused_ms=([0-9]+)")

    fun isBoundary(previous: SessionSample, sample: SessionSample, legacyPaused: Boolean = false): Boolean {
        if (previous.timestampMs <= 0 || sample.timestampMs <= previous.timestampMs || sample.sampleIndex <= previous.sampleIndex) return true
        val recordedPause = pauseMarker.find(sample.note)?.groupValues?.get(1)?.toLongOrNull()
        val previousPause = pauseMarker.find(previous.note)?.groupValues?.get(1)?.toLongOrNull()
        if (recordedPause != null) return recordedPause != (previousPause ?: 0L)
        // An old batch is known to have paused, but older rows cannot identify which gap included it.
        return legacyPaused
    }

    fun resolve(samples: List<SessionSample>, legacyPaused: Boolean = false): List<SessionSample> {
        var chargeAnchor: SessionSample? = null
        var energyAnchor: SessionSample? = null
        var previous: SessionSample? = null
        return samples.sortedBy { it.sampleIndex }.map { sample ->
            val boundary = previous?.let { isBoundary(it, sample, legacyPaused) } == true
            if (boundary || sample.charging) {
                chargeAnchor = null
                energyAnchor = null
            }
            var watts: Double? = null
            var source: String? = null
            var window: Long? = null
            fun accept(value: Double?, method: String, dt: Long) {
                if (watts == null && value != null && value.isFinite() && value > 0.0 && value <= 100.0) {
                    watts = value
                    source = method
                    window = dt
                }
            }
            if (!sample.charging) {
                val charge = sample.chargeCounterUah?.takeIf { it > 0 }
                val ca = chargeAnchor
                if (charge != null && ca != null) {
                    val delta = ca.chargeCounterUah!!.toLong() - charge.toLong()
                    val dt = sample.timestampMs - ca.timestampMs
                    if (delta > 0 && dt in MIN_WINDOW_MS..MAX_WINDOW_MS) {
                        val voltage = listOfNotNull(ca.batteryVoltageMv, sample.batteryVoltageMv)
                            .filter { it in 2500..20000 }.average()
                        if (voltage.isFinite()) accept(PowerMath.powerWFromCharge(delta, voltage.toInt(), dt), "charge_counter", dt)
                    }
                }
                if (charge != null && (ca == null || ca.chargeCounterUah != charge || sample.timestampMs - ca.timestampMs > MAX_WINDOW_MS)) {
                    chargeAnchor = sample
                } else if (charge == null) chargeAnchor = null

                val energy = sample.energyCounterNwh?.takeIf { it in 1L..10_000_000_000_000L }
                val ea = energyAnchor
                if (energy != null && ea != null) {
                    val delta = ea.energyCounterNwh!! - energy
                    val dt = sample.timestampMs - ea.timestampMs
                    if (delta > 0 && dt in MIN_WINDOW_MS..MAX_WINDOW_MS) {
                        accept(delta.toDouble() / 1e9 * 3_600_000.0 / dt, "energy_counter", dt)
                    }
                }
                if (energy != null && (ea == null || ea.energyCounterNwh != energy || sample.timestampMs - ea.timestampMs > MAX_WINDOW_MS)) {
                    energyAnchor = sample
                } else if (energy == null) energyAnchor = null
            }
            previous = sample
            // Old counter power may have used a global baseline. Never reuse a counter
            // value for which we cannot reconstruct a window inside this condition.
            val instant = if (!sample.charging) PowerMath.instantPowerW(sample.batteryVoltageMv, sample.currentNowUa) else null
            when {
                watts != null -> sample.copy(computedPowerW = watts, powerSource = source, powerWindowMs = window)
                instant != null -> sample.copy(computedPowerW = instant, powerSource = "current_now", powerWindowMs = null)
                else -> sample.copy(computedPowerW = null, powerSource = "unavailable", powerWindowMs = null)
            }
        }
    }
}
