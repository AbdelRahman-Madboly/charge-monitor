package com.vinex.chargemonitor.ui.chart

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Pure helpers behind the Canvas charts, kept free of Compose so they are unit-tested on the JVM. */
object ChartMath {

    /** An axis range snapped to round numbers, with the tick values inside it. */
    data class Scale(val min: Double, val max: Double, val ticks: List<Double>) {
        val span: Double get() = (max - min).takeIf { it > 0 } ?: 1.0
        /** 0 at [min], 1 at [max]. */
        fun fraction(v: Double): Double = (v - min) / span
    }

    /**
     * Round-number axis covering [lo]..[hi] with roughly [target] intervals. A flat series gets a
     * small artificial span so it draws as a line mid-chart rather than collapsing onto an edge.
     */
    fun niceScale(lo: Double, hi: Double, target: Int = 4, includeZero: Boolean = false): Scale {
        var a = if (lo.isFinite()) lo else 0.0
        var b = if (hi.isFinite()) hi else 1.0
        if (includeZero) {
            a = minOf(a, 0.0)
            b = maxOf(b, 0.0)
        }
        if (b < a) a = b.also { b = a }
        if (b - a < 1e-9) {
            val pad = if (abs(a) > 1e-9) abs(a) * 0.1 else 1.0
            a -= pad
            b += pad
            if (includeZero && lo >= 0) a = maxOf(a, 0.0)
        }
        val step = niceStep((b - a) / target.coerceAtLeast(1))
        val min = floor(a / step) * step
        val max = ceil(b / step) * step
        val ticks = buildList {
            var t = min
            // Bounded loop: at most target*3 ticks even with floating-point drift.
            var guard = 0
            while (t <= max + step * 1e-6 && guard++ < target * 3 + 2) {
                add(if (abs(t) < step * 1e-9) 0.0 else t)
                t += step
            }
        }
        return Scale(min, max, ticks)
    }

    /** 1, 2, 2.5 or 5 times a power of ten, the closest at or above [raw]. */
    fun niceStep(raw: Double): Double {
        if (raw <= 0 || !raw.isFinite()) return 1.0
        val exp = floor(log10(raw))
        val base = 10.0.pow(exp)
        val m = raw / base
        val nice = when {
            m <= 1.0 -> 1.0
            m <= 2.0 -> 2.0
            m <= 2.5 -> 2.5
            m <= 5.0 -> 5.0
            else -> 10.0
        }
        return nice * base
    }

    /** Decimals needed to print ticks spaced [step] apart without losing information. */
    fun decimalsFor(step: Double): Int = when {
        step >= 1.0 -> 0
        step >= 0.1 -> 1
        step >= 0.01 -> 2
        else -> 3
    }

    /**
     * Indices to draw when a series has more points than pixels: for each of [buckets] equal
     * x-slices keeps the first, min, max and last point, in x order. Peaks survive, which plain
     * decimation would drop. Returns every index when the series is already small enough.
     */
    fun downsample(xs: LongArray, ys: FloatArray, buckets: Int): IntArray {
        val n = minOf(xs.size, ys.size)
        if (n <= buckets * 4 || buckets <= 0) return IntArray(n) { it }
        val x0 = xs[0]
        val span = (xs[n - 1] - x0).coerceAtLeast(1)
        val out = ArrayList<Int>(buckets * 4)
        var i = 0
        for (b in 0 until buckets) {
            val end = x0 + span * (b + 1) / buckets
            if (i >= n) break
            val first = i
            var minI = i
            var maxI = i
            while (i < n && (xs[i] <= end || b == buckets - 1)) {
                if (ys[i] < ys[minI]) minI = i
                if (ys[i] > ys[maxI]) maxI = i
                i++
            }
            val last = i - 1
            if (last < first) continue
            sortedSetOf(first, minI, maxI, last).forEach { out += it }
        }
        return out.toIntArray()
    }

    /** Index of the element of sorted [xs] closest to [x]; -1 when empty. */
    fun nearestIndex(xs: LongArray, x: Long): Int {
        if (xs.isEmpty()) return -1
        var lo = 0
        var hi = xs.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (xs[mid] < x) lo = mid + 1 else hi = mid
        }
        return if (lo > 0 && abs(xs[lo - 1] - x) <= abs(xs[lo] - x)) lo - 1 else lo
    }
}
