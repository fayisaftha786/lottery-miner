package com.lottery.miner

import org.junit.Assert.assertEquals
import org.junit.Test

class JobTest {
    private fun validJob(
        prevHash: String = "00".repeat(32),
        version: String = "00000001",
        nbits: String = "1d00ffff",
        ntime: String = "495fab29",
        merkle: List<ByteArray> = emptyList()
    ) = Job(
        id = "job-1",
        prevHashHex = prevHash,
        coinb1 = byteArrayOf(1),
        coinb2 = byteArrayOf(2),
        merkle = merkle,
        versionHex = version,
        nbitsHex = nbits,
        ntimeHex = ntime,
        clean = true
    )

    @Test fun acceptsWellFormedJob() {
        assertEquals("job-1", validJob().id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongPrevHashLength() {
        validJob(prevHash = "00")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedVersionField() {
        validJob(version = "000001")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedNbitsField() {
        validJob(nbits = "1d00ff")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMerkleBranchWithWrongLength() {
        validJob(merkle = listOf(ByteArray(31)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTooManyMerkleBranches() {
        validJob(merkle = List(33) { ByteArray(32) })
    }
}
