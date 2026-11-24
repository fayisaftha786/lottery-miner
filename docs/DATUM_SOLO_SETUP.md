# Lottery Miner — DATUM Direct Solo setup

This mode connects the phone's Stratum V1 miner to a DATUM Gateway that you operate. The Gateway, not the Android app, connects to Bitcoin Core, obtains block templates and submits a solved block to the node/network. The app never connects to Bitcoin Core RPC directly and must never receive RPC credentials.

## Required infrastructure

- A separate 64-bit Linux PC or VPS running a fully synchronized Bitcoin Core mainnet node.
- DATUM Gateway installed on that machine and configured to use the local Bitcoin Core node.
- Phone-to-Gateway connectivity over the same trusted LAN or a private VPN.
- A Bitcoin mainnet reward address you control, configured in the Gateway.

The Android phone alone is not a practical Bitcoin full-node + DATUM Gateway host.

## Gateway configuration

Follow the official DATUM installation and Bitcoin Core RPC connection guide. Merge these solo settings into the Gateway's existing configuration while retaining its `bitcoind` RPC section:

```json
{
  "mining": {
    "pool_address": "YOUR_BITCOIN_MAINNET_ADDRESS"
  },
  "datum": {
    "pool_host": "",
    "pooled_mining_only": false
  }
}
```

- `mining.pool_address` is the payout destination. Configure your own mainnet address here; the phone worker name is not the payout address.
- `datum.pool_host` must be explicitly empty, not omitted.
- `datum.pooled_mining_only` must be `false`.
- Confirm the Gateway reports Non-Pooled Mode.
- Its default Stratum listener is usually TCP port `23334`; use your actual `stratum.listen_port` if changed.

## Configure Lottery Miner

1. Select **Direct Solo (DATUM)**.
2. Enter the Gateway's private LAN IP/hostname.
3. Enter port `23334` unless you changed the Gateway listener.
4. Enter a worker name such as `miner1`.
5. Keep charging and thermal protections enabled, then start.

## Security

- Never expose Bitcoin Core RPC (normally TCP `8332`) to the public internet.
- Never enter RPC username/password, cookie, wallet seed, private key or wallet credentials in Lottery Miner.
- Do not public-port-forward DATUM Stratum TCP `23334` or dashboard/API ports. Use trusted LAN or a private VPN.
- DATUM's default Stratum V1 listener is plain TCP; it is intended for trusted private-network use unless protected by a properly secured tunnel.

## Reward semantics

A Gateway-accepted Stratum share confirms only that the Gateway accepted the submitted share. It is not a Bitcoin block and does not create a payout. A reward exists only when the Gateway submits a valid block and the Bitcoin network accepts it. DATUM changes who constructs and submits the work; it does not increase the probability per hash.

## Live verification required

- Bitcoin Core is on mainnet, fully synced and has current templates.
- Gateway reports Non-Pooled Mode and a current block height.
- Phone connects and receives jobs; Gateway and phone share response counts agree.
- Confirm real block-template refresh and block-candidate submission logs. Never manufacture test candidates in live telemetry.
- A sustained Android hashrate/thermal benchmark remains required.
