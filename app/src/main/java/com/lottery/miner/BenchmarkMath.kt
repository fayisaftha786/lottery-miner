package com.lottery.miner

/** Pure benchmark statistics so worker recommendations are deterministic and testable. */
internal object BenchmarkMath {
    fun median(values: List<Double>): Double {
        require(values.isNotEmpty()) { "At least one benchmark sample is required" }
        require(values.all { it.isFinite() && it > 0.0 }) {
            "Benchmark samples must be finite and greater than zero"
        }
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    /** Returns every worker count in the bounded device-supported range. */
    fun candidateWorkers(maxWorkers: Int): List<Int> {
        require(maxWorkers in 1..8) { "Worker count must be 1–8" }
        return (1..maxWorkers).toList()
    }

    /**
     * Select the lowest worker count that retains the requested fraction of
     * peak median throughput. This is a throughput trade-off only; it is not
     * presented as a measured energy-efficiency result.
     */
    fun recommendWorkers(
        medianRatesByWorkers: Map<Int, Double>,
        retainedPeakFraction: Double = 0.985
    ): Int {
        require(medianRatesByWorkers.isNotEmpty())
        require(retainedPeakFraction.isFinite() && retainedPeakFraction in 0.5..1.0)
        require(medianRatesByWorkers.all { (workers, rate) ->
            workers > 0 && rate.isFinite() && rate > 0.0
        })
        val peak = medianRatesByWorkers.values.maxOrNull()
            ?: throw IllegalArgumentException("No benchmark rates supplied")
        val threshold = peak * retainedPeakFraction
        return medianRatesByWorkers
            .filterValues { it >= threshold }
            .keys.minOrNull()
            ?: throw IllegalStateException("No worker recommendation could be selected")
    }
}
