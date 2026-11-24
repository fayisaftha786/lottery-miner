package com.lottery.miner

import kotlin.math.expm1

/**
 * Real-data-only economics layer for the Bitcoin SHA-256d miner.
 *
 * This is a mathematical expected-value model, not a payout forecast. It uses
 * the current block subsidy only; transaction fees are deliberately excluded.
 * Rental quotes are user-entered market observations, never fabricated.
 */
internal data class SatsYieldInput(
    val deviceHashrateHps: Double,
    val networkHashrateHps: Double,
    val blockSubsidySats: Double,
    val btcPriceInr: Double,
    val powerWatts: Double,
    val electricityInrPerKwh: Double,
    val poolFeePercent: Double,
    val snapshotAgeSeconds: Long,
    val rentalQuoteSatsPerThDay: Double? = null,
    val blocksPerDay: Double = 144.0
)

internal data class SatsYieldResult(
    val isFresh: Boolean,
    val expectedGrossSatsPerDay: Double,
    val expectedAfterPoolFeeSatsPerDay: Double,
    val electricityCostInrPerDay: Double,
    val electricityCostSatsPerDay: Double,
    val expectedNetSatsPerDay: Double,
    val expectedNetInrPerDay: Double,
    val expectedBlocksPerDay: Double,
    val expectedDaysPerBlock: Double,
    val blockProbability1Day: Double,
    val blockProbability7Days: Double,
    val blockProbability30Days: Double,
    val energyKwhPerDay: Double,
    val hashesPerWatt: Double,
    val joulesPerTerahash: Double,
    val breakEvenElectricityInrPerKwh: Double,
    /** Subsidy-only expected value ceiling; not a guaranteed profitable rental price. */
    val maximumRentalSatsPerThDay: Double,
    val rentalQuoteSatsPerThDay: Double?,
    val rentalDeltaSatsPerThDay: Double?
)

internal object SatsYieldAdvisor {
    const val MAX_SNAPSHOT_AGE_SECONDS = 900L
    private const val SATS_PER_BTC = 100_000_000.0
    private const val HASHES_PER_TERAHASH = 1_000_000_000_000.0

    fun analyze(input: SatsYieldInput): SatsYieldResult {
        require(input.deviceHashrateHps.isFinite() && input.deviceHashrateHps > 0.0)
        require(input.networkHashrateHps.isFinite() && input.networkHashrateHps > 0.0)
        require(input.deviceHashrateHps <= input.networkHashrateHps) {
            "Device hashrate cannot exceed the supplied network hashrate"
        }
        require(input.blockSubsidySats.isFinite() && input.blockSubsidySats >= 0.0)
        require(input.btcPriceInr.isFinite() && input.btcPriceInr > 0.0)
        require(input.powerWatts.isFinite() && input.powerWatts >= 0.0)
        require(input.electricityInrPerKwh.isFinite() && input.electricityInrPerKwh >= 0.0)
        require(input.poolFeePercent.isFinite() && input.poolFeePercent in 0.0..100.0)
        require(input.snapshotAgeSeconds >= 0L)
        require(input.blocksPerDay.isFinite() && input.blocksPerDay > 0.0)
        require(input.rentalQuoteSatsPerThDay == null ||
            (input.rentalQuoteSatsPerThDay.isFinite() && input.rentalQuoteSatsPerThDay >= 0.0))

        val energyKwh = input.powerWatts / 1_000.0 * 24.0
        val electricityInr = energyKwh * input.electricityInrPerKwh
        val hashesPerWatt = input.deviceHashrateHps / input.powerWatts.takeIf { it > 0.0 }!!
        val joulesPerTh = input.powerWatts / input.deviceHashrateHps * HASHES_PER_TERAHASH
        val fresh = input.snapshotAgeSeconds <= MAX_SNAPSHOT_AGE_SECONDS

        if (!fresh) {
            return SatsYieldResult(
                isFresh = false,
                expectedGrossSatsPerDay = Double.NaN,
                expectedAfterPoolFeeSatsPerDay = Double.NaN,
                electricityCostInrPerDay = electricityInr,
                electricityCostSatsPerDay = Double.NaN,
                expectedNetSatsPerDay = Double.NaN,
                expectedNetInrPerDay = Double.NaN,
                expectedBlocksPerDay = Double.NaN,
                expectedDaysPerBlock = Double.NaN,
                blockProbability1Day = Double.NaN,
                blockProbability7Days = Double.NaN,
                blockProbability30Days = Double.NaN,
                energyKwhPerDay = energyKwh,
                hashesPerWatt = hashesPerWatt,
                joulesPerTerahash = joulesPerTh,
                breakEvenElectricityInrPerKwh = Double.NaN,
                maximumRentalSatsPerThDay = Double.NaN,
                rentalQuoteSatsPerThDay = input.rentalQuoteSatsPerThDay,
                rentalDeltaSatsPerThDay = null
            )
        }

        val networkShare = input.deviceHashrateHps / input.networkHashrateHps
        val expectedBlocksPerDay = networkShare * input.blocksPerDay
        val grossSats = expectedBlocksPerDay * input.blockSubsidySats
        val afterFeeSats = grossSats * (1.0 - input.poolFeePercent / 100.0)
        val electricitySats = electricityInr / input.btcPriceInr * SATS_PER_BTC
        val netSats = afterFeeSats - electricitySats
        val netInr = netSats / SATS_PER_BTC * input.btcPriceInr
        val expectedDaysPerBlock = 1.0 / expectedBlocksPerDay
        val maximumRental = input.blockSubsidySats * input.blocksPerDay *
            HASHES_PER_TERAHASH / input.networkHashrateHps *
            (1.0 - input.poolFeePercent / 100.0)
        val rentalDelta = input.rentalQuoteSatsPerThDay?.let { maximumRental - it }
        val breakEvenTariff = if (energyKwh > 0.0) {
            (afterFeeSats / SATS_PER_BTC * input.btcPriceInr) / energyKwh
        } else {
            Double.POSITIVE_INFINITY
        }

        return SatsYieldResult(
            isFresh = true,
            expectedGrossSatsPerDay = grossSats,
            expectedAfterPoolFeeSatsPerDay = afterFeeSats,
            electricityCostInrPerDay = electricityInr,
            electricityCostSatsPerDay = electricitySats,
            expectedNetSatsPerDay = netSats,
            expectedNetInrPerDay = netInr,
            expectedBlocksPerDay = expectedBlocksPerDay,
            expectedDaysPerBlock = expectedDaysPerBlock,
            blockProbability1Day = probabilityAtLeastOneBlock(expectedBlocksPerDay, 1.0),
            blockProbability7Days = probabilityAtLeastOneBlock(expectedBlocksPerDay, 7.0),
            blockProbability30Days = probabilityAtLeastOneBlock(expectedBlocksPerDay, 30.0),
            energyKwhPerDay = energyKwh,
            hashesPerWatt = hashesPerWatt,
            joulesPerTerahash = joulesPerTh,
            breakEvenElectricityInrPerKwh = breakEvenTariff,
            maximumRentalSatsPerThDay = maximumRental,
            rentalQuoteSatsPerThDay = input.rentalQuoteSatsPerThDay,
            rentalDeltaSatsPerThDay = rentalDelta
        )
    }

    private fun probabilityAtLeastOneBlock(expectedBlocksPerDay: Double, days: Double): Double {
        val expectedBlocks = expectedBlocksPerDay * days
        return if (!expectedBlocks.isFinite() || expectedBlocks >= 745.0) {
            1.0
        } else {
            (-expm1(-expectedBlocks)).coerceIn(0.0, 1.0)
        }
    }
}
