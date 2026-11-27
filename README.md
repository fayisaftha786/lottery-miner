# PHOENIX SOLO — Direct Bitcoin Core Android Miner

PHOENIX SOLO performs Bitcoin mainnet SHA-256d proof-of-work on the Android phone CPU. It does **not** use a mining pool or Stratum. The phone hashes the actual network target using block headers built from a synchronized Bitcoin Core getblocktemplate; a dedicated Phoenix gateway constructs the full coinbase/block and submits a candidate through submitblock.

A phone-only APK cannot independently provide a current, consensus-valid Bitcoin block template without access to a Bitcoin full node. Therefore the Android app connects to a small gateway that runs on the same trusted host as Bitcoin Core. The node's RPC credentials remain on that host and are never entered into the phone.

## Architecture

1. Bitcoin Core mainnet must be fully synchronized and expose RPC only on loopback.
2. direct-gateway/ calls getblockchaininfo and getblocktemplate and prepares a full coinbase transaction with BIP34 height and the node-provided SegWit witness commitment.
3. The gateway returns an 80-byte block header and the exact 256-bit network target to Android over an authenticated HTTPS API.
4. Android scans nonce ranges in the native SHA-256d engine. Its target is the **Bitcoin network target**, not a pool share target.
5. When a nonce meets the network target, Android sends the candidate nonce to the gateway. The gateway independently rechecks the hash, builds the complete block (header + coinbase + all template transactions), runs Bitcoin Core proposal validation, and then calls submitblock.
6. A node acceptance is reported separately from network confirmation. No payout is inferred from a local candidate or a successful RPC response.

## Requirements

- A fully synchronized Bitcoin Core mainnet node on a Linux host / server.
- Node.js 20 or later on that same host.
- A public HTTPS endpoint or private VPN HTTPS endpoint to reach the gateway from Android.
- Android 8+ (API 26+) device. Native ARM SHA-256 is used only after independent self-tests pass; otherwise the validated software path is selected.

## Configure Bitcoin Core securely

Keep RPC on loopback. Do not expose port 8332 to the internet. Example bitcoin.conf entries (choose unique credentials and retain the other required settings for your installation):

~~~ini
server=1
rpcbind=127.0.0.1
rpcallowip=127.0.0.1
rpcuser=phoenix_rpc
rpcpassword=REPLACE_WITH_A_LONG_RANDOM_PASSWORD
~~~

Start Bitcoin Core and wait until getblockchaininfo reports initialblockdownload=false and blocks == headers. The gateway refuses to issue work while the node is still syncing.

## Configure the Direct Node Gateway

On the same host as Bitcoin Core:

~~~bash
cd direct-gateway
npm install
cp .env.example .env
~~~

Set these environment values in .env:

- BITCOIN_RPC_URL=http://127.0.0.1:8332/
- BITCOIN_RPC_USER and BITCOIN_RPC_PASSWORD: the local Bitcoin Core RPC credentials.
- PHOENIX_API_TOKEN: create a separate 32-byte minimum random API token, for example openssl rand -hex 32.
- PHOENIX_BIND_HOST=127.0.0.1 and PHOENIX_PORT=8787.

Load the environment and run the service:

~~~bash
set -a
. ./.env
set +a
node server.mjs
~~~

The gateway binds to loopback by default. Publish it through a trusted HTTPS reverse proxy or a private VPN HTTPS ingress. Example Caddy site (replace the hostname with one you control and point its DNS record to the host):

~~~text
phoenix.example.net {
    reverse_proxy 127.0.0.1:8787
}
~~~

Never expose Bitcoin Core RPC directly. Never put its RPC username/password into Android. The phone only receives the gateway API token, which the app encrypts with an Android Keystore AES-GCM key.

Gateway tests:

~~~bash
npm test
~~~

The gateway validates target/bits consistency, transaction hex and txids, payout script, BIP34 height encoding, SegWit witness commitment, header construction, nonce target, proposal response, and submitblock response. It keeps a bounded in-memory cache of recent work identifiers so candidate submissions can be reconstructed safely.

## Configure the Android app

Install the PHOENIX SOLO APK. Enter:

- Your Bitcoin mainnet payout address.
- The HTTPS gateway URL, for example https://phoenix.example.net.
- The same PHOENIX_API_TOKEN configured on the gateway.
- The worker count and device safety thresholds.

The app rejects non-HTTPS gateway URLs and does not accept a URL containing embedded credentials. The API token is stored encrypted by Android Keystore. No Bitcoin Core RPC credentials or wallet seed/private keys belong in the app. Never share a seed phrase or private key.

The app polls for fresh templates, abandons stale work when the block template changes, and allocates non-overlapping nonce ranges. After the complete 32-bit nonce range is exhausted, it requests a new unique coinbase extranonce and starts a new header variant. Device battery, temperature availability, Android thermal state and the measured worker controller can pause/reduce CPU workers.

## What the status means

- **Hashrate / total hashes:** measured native SHA-256d scan work on this phone.
- **Network difficulty:** derived from the current template compact target.
- **Network-target candidate:** the native scanner found a header hash at or below the network target.
- **Node accepted:** Bitcoin Core accepted the submitted block at the RPC stage. This is not yet proof that the block became part of the best chain or that a wallet balance changed.
- **Payout:** only after the block is accepted by the Bitcoin network and the coinbase transaction matures under consensus rules. The app does not fabricate rewards or expected earnings.

At phone hashrates, finding a mainnet block is extraordinarily unlikely. Code changes cannot lower Bitcoin's proof-of-work target, guarantee a block, or turn hashrate into a predictable payout.

## Build and verification

Requirements: JDK 17, Android SDK 36, NDK 26.1.10909125, CMake 3.22.1, Android Gradle Plugin 8.10.1, Gradle 8.11.1, Node.js 22 for gateway tests.

~~~bash
cd direct-gateway
npm install
npm test
cd ..
cmake -S app/src/main/cpp -B build/native-tests -DBUILD_TESTING=ON -DCMAKE_BUILD_TYPE=Debug
cmake --build build/native-tests --parallel
ctest --test-dir build/native-tests --output-on-failure
gradle testDebugUnitTest lintDebug --no-daemon
gradle externalNativeBuildRelease --no-daemon
gradle assembleRelease --no-daemon
gradle assembleDebug --no-daemon
~~~

GitHub Actions runs gateway tests, host native tests, JVM tests, lint, native Android compilation, release/debug APK assembly, native ELF/APK alignment checks and APK verification. If release signing secrets are not configured, the release APK is unsigned; use the verified signed debug APK only for testing, and configure a protected release keystore for a distributable production APK.

No automated CI build can prove that a particular Bitcoin Core node is synchronized, your gateway is reachable from your phone, the Android device sustains its maximum hashrate, or a real mainnet block will be found. Those are runtime checks; the app fails closed and reports connection or node errors rather than inventing mining work or payouts.
