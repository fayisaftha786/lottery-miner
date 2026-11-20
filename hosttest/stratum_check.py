"""Checks the Stratum -> block header logic (same steps as Job.kt) on the real Bitcoin genesis block.
Run: python3 hosttest/stratum_check.py
"""
import hashlib


def sha256d(b):
    return hashlib.sha256(hashlib.sha256(b).digest()).digest()


COINBASE = bytes.fromhex(
    "01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff"
    "4d04ffff001d0104455468652054696d65732030332f4a616e2f32303039204368616e63656c6c6f72"
    "206f6e206272696e6b206f66207365636f6e64206261696c6f757420666f722062616e6b73ffffffff"
    "0100f2052a01000000434104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f"
    "61deb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac00000000"
)
assert sha256d(COINBASE)[::-1].hex() == "4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b"

# pretend a pool sent us this job (split the coinbase around extranonce1/extranonce2)
coinb1, en1, en2, coinb2 = COINBASE[:50], COINBASE[50:54], COINBASE[54:58], COINBASE[58:]
branch = []  # genesis has a single transaction
prevhash_stratum = "00" * 32
version_hex, nbits_hex, ntime_hex = "00000001", "1d00ffff", "495fab29"
nonce = 2083236893


def rev(b):
    return b[::-1]


prev = bytes.fromhex(prevhash_stratum)
prev_internal = b"".join(rev(prev[i:i + 4]) for i in range(0, 32, 4))  # word swap, as in Job.kt

h = sha256d(coinb1 + en1 + en2 + coinb2)
for b in branch:
    h = sha256d(h + b)

header = (rev(bytes.fromhex(version_hex)) + prev_internal + h + rev(bytes.fromhex(ntime_hex))
          + rev(bytes.fromhex(nbits_hex)) + nonce.to_bytes(4, "little"))
assert len(header) == 80
digest = sha256d(header)
print("block hash:", digest[::-1].hex())
assert digest[::-1].hex() == "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f"
print("submit nonce hex:", "%08x" % nonce)
print("OK")
