import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import { createGatewayServer } from "./server.mjs";

const TOKEN = "phoenix-test-token-0123456789abcdef0123456789";
const ADDRESS = "bc1qe4xhqarfw9tqaxaad3cx6ayl0r93ehyqf5uses";
const sha256d = (data) => crypto.createHash("sha256").update(
  crypto.createHash("sha256").update(data).digest()
).digest();

function template(bits = "1d00ffff") {
  const target = bits === "207fffff"
    ? "7fffff" + "00".repeat(29)
    : "00000000ffff0000000000000000000000000000000000000000000000000000";
  const witnessCommitment = sha256d(Buffer.alloc(64)).toString("hex");
  return {
    version: 0x20000000,
    previousblockhash: "00".repeat(32),
    transactions: [],
    coinbaseaux: { flags: "062f503253482f" },
    coinbasevalue: 3_125_000_000,
    target,
    bits,
    curtime: 1_700_000_000,
    height: 900_001,
    rules: ["csv", "segwit"],
    default_witness_commitment: "6a24aa21a9ed" + witnessCommitment
  };
}

async function withServer(rpc, fn, allowRegtest = false) {
  const server = createGatewayServer({ token: TOKEN, rpc, allowRegtest });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  const url = "http://127.0.0.1:" + server.address().port;
  try { await fn(url); } finally { await new Promise(resolve => server.close(resolve)); }
}

test("gateway refuses unauthenticated access", async () => {
  await withServer(async () => null, async url => {
    const response = await fetch(url + "/health");
    assert.equal(response.status, 401);
  });
});

test("gateway returns a real template-derived 80-byte Bitcoin header and network target", async () => {
  const rpc = async (method, params) => {
    if (method === "getblockchaininfo") return { initialblockdownload: false, blocks: 900_000, headers: 900_000, chain: "main" };
    if (method === "getblocktemplate" && params[0]?.mode !== "proposal") return template();
    throw new Error("Unexpected RPC method " + method);
  };
  await withServer(rpc, async url => {
    const response = await fetch(url + "/v1/work", {
      method: "POST", headers: { Authorization: "Bearer " + TOKEN, "Content-Type": "application/json" },
      body: JSON.stringify({ payoutAddress: ADDRESS, extranonce: "0102030405060708" })
    });
    assert.equal(response.status, 200);
    const work = await response.json();
    assert.equal(work.header.length, 160);
    assert.equal(work.target, template().target);
    assert.equal(work.height, 900_001);
    assert.equal(work.transactionCount, 1);
    assert.equal(work.bits, "207fffff");
    assert.match(work.jobId, /^[0-9a-f]{64}$/);
  });
});

test("gateway rejects a nonce that does not meet the Bitcoin network target", async () => {
  const rpc = async (method, params) => {
    if (method === "getblockchaininfo") return { initialblockdownload: false, blocks: 1, headers: 1, chain: "regtest" };
    if (method === "getblocktemplate") return template("207fffff");
    throw new Error("submit must not be called for an invalid candidate");
  };
  await withServer(rpc, async url => {
    const workResponse = await fetch(url + "/v1/work", {
      method: "POST", headers: { Authorization: "Bearer " + TOKEN, "Content-Type": "application/json" },
      body: JSON.stringify({ payoutAddress: ADDRESS, extranonce: "1111111111111111" })
    });
    const work = await workResponse.json();
    const header = Buffer.from(work.header, "hex");
    let invalidNonce = null;
    for (let n = 0; n < 100; n++) {
      header.writeUInt32LE(n, 76);
      const number = BigInt("0x" + Buffer.from(sha256d(header)).reverse().toString("hex"));
      if (number > BigInt("0x" + work.target)) { invalidNonce = n; break; }
    }
    assert.notEqual(invalidNonce, null, "test must find a regtest nonce above target");
    const response = await fetch(url + "/v1/submit", {
      method: "POST", headers: { Authorization: "Bearer " + TOKEN, "Content-Type": "application/json" },
      body: JSON.stringify({ jobId: work.jobId, nonce: invalidNonce.toString(16).padStart(8, "0") })
    });
    assert.equal(response.status, 400);
    assert.match((await response.json()).error, /does not meet/);
  }, true);
});

test("gateway proposal-checks before submitting a network-target candidate", async () => {
  const calls = [];
  const rpc = async (method, params) => {
    calls.push(method);
    if (method === "getblockchaininfo") return { initialblockdownload: false, blocks: 1, headers: 1, chain: "regtest" };
    if (method === "getblocktemplate" && params[0]?.mode !== "proposal") return template();
    if (method === "getblocktemplate" && params[0]?.mode === "proposal") return null;
    if (method === "submitblock") return null;
    throw new Error("Unexpected RPC method " + method);
  };
  await withServer(rpc, async url => {
    const workResponse = await fetch(url + "/v1/work", {
      method: "POST", headers: { Authorization: "Bearer " + TOKEN, "Content-Type": "application/json" },
      body: JSON.stringify({ payoutAddress: ADDRESS, extranonce: "2222222222222222" })
    });
    const work = await workResponse.json();
    const header = Buffer.from(work.header, "hex");
    let validNonce = null;
    for (let n = 0; n < 100; n++) {
      header.writeUInt32LE(n, 76);
      const number = BigInt("0x" + Buffer.from(sha256d(header)).reverse().toString("hex"));
      if (number <= BigInt("0x" + work.target)) { validNonce = n; break; }
    }
    assert.notEqual(validNonce, null, "test must find a valid regtest nonce");
    const response = await fetch(url + "/v1/submit", {
      method: "POST", headers: { Authorization: "Bearer " + TOKEN, "Content-Type": "application/json" },
      body: JSON.stringify({ jobId: work.jobId, nonce: validNonce.toString(16).padStart(8, "0") })
    });
    assert.equal(response.status, 200);
    const result = await response.json();
    assert.equal(result.accepted, true);
    assert.deepEqual(calls.slice(-2), ["getblocktemplate", "submitblock"]);
  }, true);
});

test("gateway will not issue work while the Bitcoin Core node is syncing", async () => {
  const rpc = async method => {
    if (method === "getblockchaininfo") return { initialblockdownload: true, blocks: 5, headers: 10, chain: "main" };
    throw new Error("template must not be requested while syncing");
  };
  await withServer(rpc, async url => {
    const response = await fetch(url + "/v1/work", {
      method: "POST", headers: { Authorization: "Bearer " + TOKEN, "Content-Type": "application/json" },
      body: JSON.stringify({ payoutAddress: ADDRESS, extranonce: "3333333333333333" })
    });
    assert.equal(response.status, 503);
  });
});
