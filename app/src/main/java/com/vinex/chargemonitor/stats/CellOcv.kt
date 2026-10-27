package com.vinex.chargemonitor.stats

/** Approximate NCR18650B usable energy; valid only at rest, bank unplugged for 30 min or more. */
object CellOcv {
    private val points = listOf(
        3.00 to 0.00, 3.10 to 0.01, 3.20 to 0.025, 3.30 to 0.05, 3.40 to 0.10,
        3.50 to 0.18, 3.60 to 0.31, 3.70 to 0.45, 3.80 to 0.58, 3.90 to 0.70,
        4.00 to 0.81, 4.10 to 0.91, 4.20 to 1.00,
    )

    fun energyFraction(restingV: Double): Double {
        if (restingV.isNaN() || restingV <= points.first().first) return 0.0
        if (restingV >= points.last().first) return 1.0
        val upper = points.indexOfFirst { it.first >= restingV }
        val (loV, lo) = points[upper - 1]
        val (hiV, hi) = points[upper]
        return (lo + (hi - lo) * (restingV - loV) / (hiV - loV)).coerceIn(0.0, 1.0)
    }
}
