package com.lottery.miner

import org.junit.Assert.assertArrayEquals
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

    @Test fun serializesBlockHeaderFieldsInBitcoinByteOrder() {
        val header = validJob().buildHeader(byteArrayOf(), byteArrayOf())
        assertArrayEquals(Util.hexToBytes("01000000"), header.copyOfRange(0, 4))
        assertArrayEquals(ByteArray(32), header.copyOfRange(4, 36))
        assertArrayEquals(Util.sha256d(byteArrayOf(1, 2)), header.copyOfRange(36, 68))
        assertArrayEquals(Util.hexToBytes("29ab5f49"), header.copyOfRange(68, 72))
        assertArrayEquals(Util.hexToBytes("ffff001d"), header.copyOfRange(72, 76))
    }

    @Test fun buildsTheBitcoinGenesisHeaderMerkleRootFromTheKnownCoinbaseVector() {
        val genesisCoinbase = Util.hexToBytes(
            "01000000" +
            "01" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "ffffffff" +
            "4d" +
            "04ffff001d010445" +
            "5468652054696d65732030332f4a616e2f32303039204368616e63656c6c6f72206f6e206272696e6b206f66207365636f6e64206261696c6f757420666f722062616e6b73" +
            "ffffffff" +
            "01" +
            "00f2052a01000000" +
            "43" +
            "4104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac" +
            "00000000"
        )
        val genesisJob = Job(
            id = "genesis",
            prevHashHex = "00".repeat(32),
            coinb1 = genesisCoinbase,
            coinb2 = byteArrayOf(),
            merkle = emptyList(),
            versionHex = "00000001",
            nbitsHex = "1d00ffff",
            ntimeHex = "495fab29",
            clean = true
        )

        assertEquals(
            "3ba3edfd7a7b12b27ac72c3e67768f617fc81bc388a51323a9fb8aa4b1e5e4a",
            Util.bytesToHex(Util.sha256d(genesisCoinbase))
        )
        assertArrayEquals(
            Util.hexToBytes("3ba3edfd7a7b12b27ac72c3e67768f617fc81bc388a51323a9fb8aa4b1e5e4a"),
            genesisJob.buildHeader(byteArrayOf(), byteArrayOf()).copyOfRange(36, 68)
        )
    }

    @Test fun foldsMerkleBranchesBeforeSerializingRoot() {
        val branch = ByteArray(32) { it.toByte() }
        val coinbaseHash = Util.sha256d(byteArrayOf(1, 2))
        val expectedRoot = Util.sha256d(coinbaseHash + branch)
        val header = validJob(merkle = listOf(branch)).buildHeader(byteArrayOf(), byteArrayOf())
        assertArrayEquals(expectedRoot, header.copyOfRange(36, 68))
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
