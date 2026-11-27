import http from "node:http";
import crypto from "node:crypto";
import { address, networks, Transaction } from "bitcoinjs-lib";

const U32_SPACE = 0x1_0000_0000;
const MAX_JOBS = 128;
const JOB_TTL_MS = 10 * 60 * 1000;
const jobs = new Map();

const sha256 = (data) => crypto.createHash("sha256").update(data).digest();
const sha256d = (data) => sha256(sha256(data));
const reverse = (buf) => Buffer.from(buf).reverse();
const hex32 = /^[0-9a-f]{64}$/i;
const hex = /^[0-9a-f]*$/i;

function compactSize(value) {
  const n = BigInt(value);
  if (n < 0xfdn) return Buffer.from([Number(n)]);
  if (n <= 0xffffn) { const b = Buffer.alloc(3); b[0] = 0xfd; b.writeUInt16LE(Number(n), 1); return b; }
  if (n <= 0xffff_ffffn) { const b = Buffer.alloc(5); b[0] = 0xfe; b.writeUInt32LE(Number(n), 1); return b; }
  const b = Buffer.alloc(9); b[0] = 0xff; b.writeBigUInt64LE(n, 1); return b;
}

function pushData(data) {
  if (data.length === 0) return Buffer.from([0]);
  if (data.length <= 75) return Buffer.concat([Buffer.from([data.length]), data]);
  if (data.length <= 255) return Buffer.concat([Buffer.from([0x4c, data.length]), data]);
  throw new Error("Coinbase script push is too large");
}

function scriptNumber(value) {
  let n = BigInt(value);
  if (n < 0n) throw new Error("Block height cannot be negative");
  if (n === 0n) return Buffer.from([0]);
  const bytes = [];
  while (n > 0n) { bytes.push(Number(n & 0xffn)); n >>= 8n; }
  if ((bytes[bytes.length - 1] & 0x80) !== 0) bytes.push(0);
  return Buffer.from(bytes);
}

function targetFromBits(bitsHex) {
  if (typeof bitsHex !== "string" || !/^[0-9a-f]{8}$/i.test(bitsHex)) throw new Error("Invalid compact target");
  const bits = BigInt("0x" + bitsHex);
  const exponent = Number(bits >> 24n);
  const mantissa = bits & 0x007fffffn;
  if ((bits & 0x00800000n) !== 0n || mantissa === 0n || exponent < 3 || exponent > 32) {
    throw new Error("Invalid or negative compact target");
  }
  const target = mantissa << (8n * BigInt(exponent - 3));
  if (target <= 0n || target >= (1n << 256n)) throw new Error("Compact target exceeds 256 bits");
  return target.toString(16).padStart(64, "0");
}

function verifyTemplate(template) {
  if (!template || typeof template !== "object") throw new Error("Bitcoin Core returned no block template");
  if (!Number.isSafeInteger(template.height) || template.height < 1) throw new Error("Invalid template height");
  if (!Number.isSafeInteger(template.version) || !Number.isSafeInteger(template.curtime)) throw new Error("Invalid template version/time");
  if (!hex32.test(template.previousblockhash || "") || !/^[0-9a-f]{8}$/i.test(template.bits || "")) throw new Error("Invalid previous block or bits");
  if (!Number.isSafeInteger(template.coinbasevalue) || template.coinbasevalue < 0) throw new Error("Invalid coinbase value");
  if (!Array.isArray(template.transactions) || template.transactions.length > 100_000) throw new Error("Invalid or excessive template transaction list");
  const target = targetFromBits(template.bits);
  if (template.target && template.target.toLowerCase() !== target) {
    throw new Error("Bitcoin Core target does not match compact bits");
  }
  for (const tx of template.transactions) {
    if (typeof tx.data !== "string" || tx.data.length === 0 || tx.data.length % 2 || !hex.test(tx.data)) {
      throw new Error("Template contains invalid raw transaction data");
    }
    if (typeof tx.txid !== "string" || !hex32.test(tx.txid)) throw new Error("Template transaction is missing its txid");
  }
  if (template.rules?.includes("segwit") && template.transactions.some(tx => tx.hash && tx.hash.toLowerCase() !== tx.txid.toLowerCase()) &&
      !template.default_witness_commitment) {
    throw new Error("SegWit transactions require Bitcoin Core's default witness commitment");
  }
  return target;
}

function merkleRoot(coinbaseTxid, transactions) {
  let level = [coinbaseTxid, ...transactions.map(tx => reverse(Buffer.from(tx.txid, "hex")))];
  while (level.length > 1) {
    if (level.length % 2) level.push(level[level.length - 1]);
    const next = [];
    for (let i = 0; i < level.length; i += 2) next.push(sha256d(Buffer.concat([level[i], level[i + 1]])));
    level = next;
  }
  return level[0];
}

function buildCoinbase(template, payoutAddress, extranonceHex) {
  if (!/^[0-9a-f]{16}$/i.test(extranonceHex)) throw new Error("Extranonce must be exactly 8 bytes");
  const payoutScript = address.toOutputScript(payoutAddress, networks.bitcoin);
  const heightPush = pushData(scriptNumber(template.height));
  const flagsHex = template.coinbaseaux?.flags || "";
  if (flagsHex.length % 2 || !hex.test(flagsHex)) throw new Error("Invalid coinbase auxiliary flags");
  const flags = Buffer.from(flagsHex, "hex");
  const scriptSig = Buffer.concat([heightPush, flags, pushData(Buffer.from(extranonceHex, "hex"))]);
  if (scriptSig.length < 2 || scriptSig.length > 100) throw new Error("Coinbase scriptSig length violates consensus limits");

  const tx = new Transaction();
  tx.version = 2;
  tx.addInput(Buffer.alloc(32), 0xffffffff, 0xffffffff, scriptSig);
  tx.addOutput(payoutScript, BigInt(template.coinbasevalue));
  const witnessCommitmentHex = template.default_witness_commitment;
  if (witnessCommitmentHex) {
    if (typeof witnessCommitmentHex !== "string" || witnessCommitmentHex.length % 2 || !hex.test(witnessCommitmentHex)) {
      throw new Error("Invalid default witness commitment");
    }
    const commitment = Buffer.from(witnessCommitmentHex, "hex");
    if (commitment.length !== 38 || !commitment.subarray(0, 6).equals(Buffer.from("6a24aa21a9ed", "hex"))) {
      throw new Error("Unexpected witness commitment script from Bitcoin Core");
    }
    tx.addOutput(commitment, 0n);
    tx.setWitness(0, [Buffer.alloc(32)]);
  }
  return tx;
}

function makeWork(template, payoutAddress, extranonceHex) {
  const targetHex = verifyTemplate(template);
  const coinbase = buildCoinbase(template, payoutAddress, extranonceHex);
  const root = merkleRoot(coinbase.getHash(false), template.transactions);
  const header = Buffer.alloc(80);
  header.writeInt32LE(template.version, 0);
  reverse(Buffer.from(template.previousblockhash, "hex")).copy(header, 4);
  root.copy(header, 36);
  header.writeUInt32LE(template.curtime >>> 0, 68);
  header.writeUInt32LE(Number(BigInt("0x" + template.bits)), 72);
  header.writeUInt32LE(0, 76);

  const canonical = Buffer.concat([
    header,
    Buffer.from(payoutAddress, "utf8"),
    Buffer.from(extranonceHex, "hex"),
    ...template.transactions.map(tx => Buffer.from(tx.data, "hex"))
  ]);
  const jobId = sha256(canonical).toString("hex");
  return {
    jobId,
    height: template.height,
    header: header.toString("hex"),
    target: targetHex,
    bits: template.bits,
    previousBlockHash: template.previousblockhash,
    transactionCount: template.transactions.length + 1,
    coinbaseHex: coinbase.toHex(),
    transactions: template.transactions.map(tx => tx.data),
    workid: template.workid || null,
    createdAt: Date.now()
  };
}

function buildBlock(job, nonceHex) {
  if (!/^[0-9a-f]{8}$/i.test(nonceHex)) throw new Error("Nonce must be exactly 4 bytes");
  const header = Buffer.from(job.header, "hex");
  header.writeUInt32LE(Number.parseInt(nonceHex, 16) >>> 0, 76);
  const blockBytes = [
    header,
    compactSize(job.transactionCount),
    Buffer.from(job.coinbaseHex, "hex"),
    ...job.transactions.map(tx => Buffer.from(tx, "hex"))
  ];
  return Buffer.concat(blockBytes);
}

function isHashWithinTarget(header, targetHex) {
  const digest = sha256d(header);
  return BigInt("0x" + reverse(digest).toString("hex")) <= BigInt("0x" + targetHex);
}

async function rpcCall(method, params = []) {
  const url = process.env.BITCOIN_RPC_URL;
  const user = process.env.BITCOIN_RPC_USER;
  const password = process.env.BITCOIN_RPC_PASSWORD;
  if (!url || !user || !password) throw new Error("Bitcoin Core RPC environment is not configured");
  const auth = Buffer.from(user + ":" + password).toString("base64");
  const response = await fetch(url, {
    method: "POST",
    headers: { "Authorization": "Basic " + auth, "Content-Type": "application/json" },
    body: JSON.stringify({ jsonrpc: "2.0", id: "phoenix", method, params }),
    signal: AbortSignal.timeout(60_000)
  });
  if (!response.ok) throw new Error("Bitcoin Core RPC HTTP " + response.status);
  const data = await response.json();
  if (data.error) throw new Error("Bitcoin Core RPC " + data.error.code + ": " + data.error.message);
  return data.result;
}

function pruneJobs(now = Date.now()) {
  for (const [id, job] of jobs) if (now - job.createdAt > JOB_TTL_MS) jobs.delete(id);
  while (jobs.size > MAX_JOBS) jobs.delete(jobs.keys().next().value);
}

async function getWork(body) {
  const payoutAddress = body?.payoutAddress;
  const extranonce = body?.extranonce;
  if (typeof payoutAddress !== "string" || payoutAddress.length > 90) throw new Error("Invalid payout address");
  if (!/^[0-9a-f]{16}$/i.test(extranonce || "")) throw new Error("Invalid extranonce");
  const chain = await rpcCall("getblockchaininfo");
  if (chain.initialblockdownload || chain.blocks < chain.headers) {
    const err = new Error("Bitcoin Core is not fully synchronized");
    err.statusCode = 503;
    throw err;
  }
  const template = await rpcCall("getblocktemplate", [{ rules: ["segwit"], capabilities: ["longpoll", "coinbasevalue"] }]);
  const job = makeWork(template, payoutAddress, extranonce);
  pruneJobs();
  if (!jobs.has(job.jobId)) jobs.set(job.jobId, job);
  return {
    jobId: job.jobId,
    height: job.height,
    header: job.header,
    target: job.target,
    bits: job.bits,
    previousBlockHash: job.previousBlockHash,
    transactionCount: job.transactionCount,
    nodeBlocks: chain.blocks,
    network: chain.chain
  };
}

async function submitCandidate(body) {
  const jobId = body?.jobId;
  const nonce = body?.nonce;
  if (typeof jobId !== "string" || !/^[0-9a-f]{64}$/i.test(jobId)) throw new Error("Invalid job id");
  const job = jobs.get(jobId);
  if (!job) { const err = new Error("Mining job expired or was replaced"); err.statusCode = 404; throw err; }
  const block = buildBlock(job, nonce);
  const header = block.subarray(0, 80);
  if (!isHashWithinTarget(header, job.target)) throw new Error("Submitted nonce does not meet the active Bitcoin network target");

  const blockHex = block.toString("hex");
  const proposal = await rpcCall("getblocktemplate", [{ mode: "proposal", data: blockHex }]);
  if (proposal !== null) {
    return { accepted: false, stage: "proposal", response: String(proposal || "Node rejected block proposal"), blockHash: reverse(sha256d(header)).toString("hex") };
  }
  const result = await rpcCall("submitblock", [blockHex]);
  const accepted = result === null;
  return {
    accepted,
    stage: "submitblock",
    response: accepted ? "Bitcoin Core accepted the block submission; chain confirmation is still separate" : String(result),
    blockHash: reverse(sha256d(header)).toString("hex")
  };
}

function safeEqual(a, b) {
  const aa = Buffer.from(a || "");
  const bb = Buffer.from(b || "");
  return aa.length === bb.length && crypto.timingSafeEqual(aa, bb);
}

async function readJson(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 16_384) throw Object.assign(new Error("Request body too large"), { statusCode: 413 });
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString("utf8")); }
  catch { throw Object.assign(new Error("Invalid JSON body"), { statusCode: 400 }); }
}

export function createGatewayServer({
  token = process.env.PHOENIX_API_TOKEN,
  rpc = rpcCall
} = {}) {
  if (!token || Buffer.byteLength(token) < 32) throw new Error("PHOENIX_API_TOKEN must contain at least 32 bytes");
  const handler = async (req, res) => {
    const send = (code, data) => {
      res.writeHead(code, { "Content-Type": "application/json", "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" });
      res.end(JSON.stringify(data));
    };
    if (req.method === "GET" && req.url === "/health") {
      if (!safeEqual((req.headers.authorization || "").replace(/^Bearer\s+/i, ""), token)) return send(401, { error: "Unauthorized" });
      return send(200, { ok: true, service: "phoenix-direct-node-gateway" });
    }
    if (req.method !== "POST" || !["/v1/work", "/v1/submit"].includes(req.url)) return send(404, { error: "Not found" });
    if (!safeEqual((req.headers.authorization || "").replace(/^Bearer\s+/i, ""), token)) return send(401, { error: "Unauthorized" });
    try {
      const body = await readJson(req);
      const result = req.url === "/v1/work"
        ? await getWorkWithRpc(body, rpc)
        : await submitWithRpc(body, rpc);
      return send(200, result);
    } catch (error) {
      return send(error.statusCode || 400, { error: error.message || "Request failed" });
    }
  };
  return http.createServer((req, res) => { handler(req, res).catch(() => { if (!res.headersSent) res.writeHead(500).end(); }); });
}

async function getWorkWithRpc(body, rpc) {
  const payoutAddress = body?.payoutAddress;
  const extranonce = body?.extranonce;
  if (typeof payoutAddress !== "string" || payoutAddress.length > 90) throw new Error("Invalid payout address");
  if (!/^[0-9a-f]{16}$/i.test(extranonce || "")) throw new Error("Invalid extranonce");
  const chain = await rpc("getblockchaininfo");
  if (chain.initialblockdownload || chain.blocks < chain.headers) {
    const err = new Error("Bitcoin Core is not fully synchronized"); err.statusCode = 503; throw err;
  }
  const template = await rpc("getblocktemplate", [{ rules: ["segwit"], capabilities: ["longpoll", "coinbasevalue"] }]);
  const job = makeWork(template, payoutAddress, extranonce);
  pruneJobs();
  if (!jobs.has(job.jobId)) jobs.set(job.jobId, job);
  return { jobId: job.jobId, height: job.height, header: job.header, target: job.target, bits: job.bits,
    previousBlockHash: job.previousBlockHash, transactionCount: job.transactionCount, nodeBlocks: chain.blocks, network: chain.chain };
}

async function submitWithRpc(body, rpc) {
  const jobId = body?.jobId;
  const nonce = body?.nonce;
  if (typeof jobId !== "string" || !/^[0-9a-f]{64}$/i.test(jobId)) throw new Error("Invalid job id");
  const job = jobs.get(jobId);
  if (!job) { const err = new Error("Mining job expired or was replaced"); err.statusCode = 404; throw err; }
  const block = buildBlock(job, nonce);
  const header = block.subarray(0, 80);
  if (!isHashWithinTarget(header, job.target)) throw new Error("Submitted nonce does not meet the active Bitcoin network target");
  const blockHex = block.toString("hex");
  const proposal = await rpc("getblocktemplate", [{ mode: "proposal", data: blockHex }]);
  if (proposal !== null) return { accepted: false, stage: "proposal", response: String(proposal || "Node rejected block proposal"),
    blockHash: reverse(sha256d(header)).toString("hex") };
  const result = await rpc("submitblock", [blockHex]);
  const accepted = result === null;
  return { accepted, stage: "submitblock",
    response: accepted ? "Bitcoin Core accepted the block submission; chain confirmation is still separate" : String(result),
    blockHash: reverse(sha256d(header)).toString("hex") };
}

if (import.meta.url === \`file://\${process.argv[1]}\`) {
  const server = createGatewayServer();
  const host = process.env.PHOENIX_BIND_HOST || "127.0.0.1";
  const port = Number(process.env.PHOENIX_PORT || 8787);
  server.listen(port, host, () => console.log(\`Phoenix Direct Node Gateway listening on \${host}:\${port}; put HTTPS/VPN ingress in front\`));
}
