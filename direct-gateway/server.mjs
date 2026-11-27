import http from "node:http";
import crypto from "node:crypto";
import { address, networks, Transaction } from "bitcoinjs-lib";

const MAX_JOBS = 128;
const JOB_TTL_MS = 10 * 60 * 1000;
const jobs = new Map();
const MAINNET_POW_LIMIT = BigInt("0x00000000ffff0000000000000000000000000000000000000000000000000000");
const REGTEST_POW_LIMIT = BigInt("0x7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");

const sha256 = (data) => crypto.createHash("sha256").update(data).digest();
const sha256d = (data) => sha256(sha256(data));
const reverse = (buf) => Buffer.from(buf).reverse();
const hex32 = /^[0-9a-f]{64}$/i;
const hex = /^[0-9a-f]*$/i;

function compactSize(value) {
  const n = BigInt(value);
  if (n < 0xfdn) return Buffer.from([Number(n)]);
  if (n <= 0xffffn) {
    const b = Buffer.alloc(3);
    b[0] = 0xfd;
    b.writeUInt16LE(Number(n), 1);
    return b;
  }
  if (n <= 0xffff_ffffn) {
    const b = Buffer.alloc(5);
    b[0] = 0xfe;
    b.writeUInt32LE(Number(n), 1);
    return b;
  }
  const b = Buffer.alloc(9);
  b[0] = 0xff;
  b.writeBigUInt64LE(n, 1);
  return b;
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
  while (n > 0n) {
    bytes.push(Number(n & 0xffn));
    n >>= 8n;
  }
  if ((bytes[bytes.length - 1] & 0x80) !== 0) bytes.push(0);
  return Buffer.from(bytes);
}

function targetFromBits(bitsHex) {
  if (typeof bitsHex !== "string" || !/^[0-9a-f]{8}$/i.test(bitsHex)) {
    throw new Error("Invalid compact target");
  }
  const bits = BigInt("0x" + bitsHex);
  const exponent = Number(bits >> 24n);
  const mantissa = bits & 0x007fffffn;
  if ((bits & 0x00800000n) !== 0n || mantissa === 0n || exponent < 1 || exponent > 32) {
    throw new Error("Invalid or negative compact target");
  }
  const target = exponent <= 3
    ? mantissa >> (8n * BigInt(3 - exponent))
    : mantissa << (8n * BigInt(exponent - 3));
  if (target <= 0n || target >= (1n << 256n)) throw new Error("Compact target exceeds 256 bits");
  return target;
}

function merkleRoot(leaves) {
  if (!Array.isArray(leaves) || leaves.length === 0) throw new Error("Merkle tree needs at least one leaf");
  let level = leaves.map(x => Buffer.from(x));
  while (level.length > 1) {
    if (level.length % 2) level.push(level[level.length - 1]);
    const next = [];
    for (let i = 0; i < level.length; i += 2) {
      next.push(sha256d(Buffer.concat([level[i], level[i + 1]])));
    }
    level = next;
  }
  return level[0];
}

function validateTransactions(transactions) {
  for (const item of transactions) {
    if (typeof item.data !== "string" || item.data.length === 0 || item.data.length % 2 || !hex.test(item.data)) {
      throw new Error("Template contains invalid raw transaction data");
    }
    if (!hex32.test(item.txid || "") || !hex32.test(item.hash || "")) {
      throw new Error("Template transaction is missing txid or wtxid");
    }
    const tx = Transaction.fromHex(item.data);
    if (tx.getId().toLowerCase() !== item.txid.toLowerCase()) {
      throw new Error("Template transaction bytes do not match the supplied txid");
    }
    const calculatedWtxid = reverse(tx.getHash(true)).toString("hex");
    if (calculatedWtxid.toLowerCase() !== item.hash.toLowerCase()) {
      throw new Error("Template transaction bytes do not match the supplied wtxid");
    }
  }
}

function expectedWitnessCommitment(transactions) {
  // BIP141 witness merkle tree starts with a zero coinbase wtxid.
  const leaves = [Buffer.alloc(32), ...transactions.map(item => reverse(Buffer.from(item.hash, "hex")))];
  const root = merkleRoot(leaves);
  const reservedValue = Buffer.alloc(32);
  return sha256d(Buffer.concat([root, reservedValue]));
}

function verifyTemplate(template, chainName) {
  if (!template || typeof template !== "object") throw new Error("Bitcoin Core returned no block template");
  if (!Number.isSafeInteger(template.height) || template.height < 1) throw new Error("Invalid template height");
  if (!Number.isSafeInteger(template.version) || !Number.isSafeInteger(template.curtime)) throw new Error("Invalid template version/time");
  if (!hex32.test(template.previousblockhash || "")) throw new Error("Invalid previous block hash");
  if (!Number.isSafeInteger(template.coinbasevalue) || template.coinbasevalue < 0) throw new Error("Invalid coinbase value");
  if (!Array.isArray(template.transactions) || template.transactions.length > 100_000) {
    throw new Error("Invalid or excessive template transaction list");
  }

  const target = targetFromBits(template.bits);
  const powLimit = chainName === "main" ? MAINNET_POW_LIMIT : REGTEST_POW_LIMIT;
  if (target > powLimit) throw new Error("Template target exceeds the selected Bitcoin network proof-of-work limit");
  if (template.target && BigInt("0x" + template.target) !== target) {
    throw new Error("Bitcoin Core target does not match compact bits");
  }
  if (!Array.isArray(template.rules) || !template.rules.includes("segwit")) {
    throw new Error("Bitcoin Core template did not enable SegWit rules");
  }

  validateTransactions(template.transactions);
  const commitmentHex = template.default_witness_commitment;
  if (typeof commitmentHex !== "string" || commitmentHex.length !== 76 || !hex.test(commitmentHex)) {
    throw new Error("Bitcoin Core did not provide a valid default SegWit witness commitment");
  }
  const commitmentScript = Buffer.from(commitmentHex, "hex");
  if (!commitmentScript.subarray(0, 6).equals(Buffer.from("6a24aa21a9ed", "hex"))) {
    throw new Error("Unexpected witness commitment script from Bitcoin Core");
  }
  const expected = expectedWitnessCommitment(template.transactions);
  if (!commitmentScript.subarray(6, 38).equals(expected)) {
    throw new Error("Bitcoin Core witness commitment does not match the template's witness merkle root");
  }
  return target;
}

function buildCoinbase(template, payoutAddress, extranonceHex) {
  const payoutScript = address.toOutputScript(payoutAddress, networks.bitcoin);
  const heightPush = pushData(scriptNumber(template.height));
  const flagsHex = template.coinbaseaux?.flags || "";
  if (flagsHex.length % 2 || !hex.test(flagsHex)) throw new Error("Invalid coinbase auxiliary flags");
  const flags = Buffer.from(flagsHex, "hex");
  const extranoncePush = pushData(Buffer.from(extranonceHex, "hex"));
  const scriptSig = Buffer.concat([heightPush, flags, extranoncePush]);
  if (scriptSig.length < 2 || scriptSig.length > 100) {
    throw new Error("Coinbase scriptSig length violates consensus limits");
  }

  const tx = new Transaction();
  tx.version = 2;
  tx.addInput(Buffer.alloc(32), 0xffffffff, 0xffffffff, scriptSig);
  tx.addOutput(payoutScript, template.coinbasevalue);
  const commitment = Buffer.from(template.default_witness_commitment, "hex");
  tx.addOutput(commitment, 0);
  tx.setWitness(0, [Buffer.alloc(32)]);
  return tx;
}

function makeWork(template, payoutAddress, extranonceHex, chainName) {
  const target = verifyTemplate(template, chainName);
  if (!/^[0-9a-f]{16}$/i.test(extranonceHex)) throw new Error("Extranonce must be exactly 8 bytes");
  const tx = buildCoinbase(template, payoutAddress, extranonceHex);

  const root = merkleRoot([
    tx.getHash(false),
    ...template.transactions.map(item => reverse(Buffer.from(item.txid, "hex")))
  ]);
  const header = Buffer.alloc(80);
  header.writeInt32LE(template.version, 0);
  reverse(Buffer.from(template.previousblockhash, "hex")).copy(header, 4);
  root.copy(header, 36);
  header.writeUInt32LE(template.curtime >>> 0, 68);
  header.writeUInt32LE(Number(BigInt("0x" + template.bits)), 72);
  header.writeUInt32LE(0, 76);

  // A stable template identity ignores only nTime. The exact cached header is
  // retained while this process is alive; Android compares header bytes as well
  // so a gateway restart safely replaces the old timestamp/header.
  const identityHeader = Buffer.from(header);
  identityHeader.fill(0, 68, 72);
  const canonical = Buffer.concat([
    identityHeader,
    Buffer.from(payoutAddress, "utf8"),
    Buffer.from(extranonceHex, "hex"),
    ...template.transactions.map(item => Buffer.from(item.data, "hex"))
  ]);
  const jobId = sha256(canonical).toString("hex");

  return {
    jobId,
    height: template.height,
    header: header.toString("hex"),
    target: target.toString(16).padStart(64, "0"),
    bits: template.bits,
    previousBlockHash: template.previousblockhash,
    transactionCount: template.transactions.length + 1,
    coinbaseHex: tx.toHex(),
    transactions: template.transactions.map(item => item.data),
    workid: template.workid || null,
    createdAt: Date.now()
  };
}

function buildBlock(job, nonceHex) {
  if (!/^[0-9a-f]{8}$/i.test(nonceHex || "")) throw new Error("Nonce must be exactly 4 bytes");
  const header = Buffer.from(job.header, "hex");
  header.writeUInt32LE(Number.parseInt(nonceHex, 16) >>> 0, 76);
  return Buffer.concat([
    header,
    compactSize(job.transactionCount),
    Buffer.from(job.coinbaseHex, "hex"),
    ...job.transactions.map(item => Buffer.from(item, "hex"))
  ]);
}

function isHashWithinTarget(header, targetHex) {
  const digest = sha256d(header);
  return BigInt("0x" + reverse(digest).toString("hex")) <= BigInt("0x" + targetHex);
}

async function rpcCall(method, params = [], clientSignal) {
  const url = process.env.BITCOIN_RPC_URL;
  const user = process.env.BITCOIN_RPC_USER;
  const password = process.env.BITCOIN_RPC_PASSWORD;
  if (!url || !user || !password) throw new Error("Bitcoin Core RPC environment is not configured");
  const auth = Buffer.from(user + ":" + password).toString("base64");
  const isLongPoll = typeof params[0]?.longpollid === "string";
  const timeoutSignal = isLongPoll ? null : AbortSignal.timeout(60_000);
  const signal = clientSignal && timeoutSignal
    ? AbortSignal.any([clientSignal, timeoutSignal])
    : (clientSignal || timeoutSignal);
  const response = await fetch(url, {
    method: "POST",
    headers: { Authorization: "Basic " + auth, "Content-Type": "application/json" },
    body: JSON.stringify({ jsonrpc: "2.0", id: "phoenix", method, params }),
    ...(signal ? { signal } : {})
  });
  if (!response.ok) throw new Error("Bitcoin Core RPC HTTP " + response.status);
  const data = await response.json();
  if (data.error) throw new Error("Bitcoin Core RPC " + data.error.code + ": " + data.error.message);
  return data.result;
}

function pruneJobs(now = Date.now()) {
  for (const [id, job] of jobs) {
    if (now - job.createdAt > JOB_TTL_MS) jobs.delete(id);
  }
  while (jobs.size > MAX_JOBS) jobs.delete(jobs.keys().next().value);
}

async function getWorkWithRpc(body, rpc, allowRegtest, signal) {
  const payoutAddress = body?.payoutAddress;
  const extranonce = body?.extranonce;
  if (typeof payoutAddress !== "string" || payoutAddress.length > 90 || payoutAddress.includes(".")) {
    throw new Error("A plain Bitcoin mainnet payout address is required");
  }
  if (!/^[0-9a-f]{16}$/i.test(extranonce || "")) throw new Error("Invalid extranonce");

  const chain = await rpc("getblockchaininfo", [], signal);
  if (chain.chain !== "main" && !(allowRegtest && chain.chain === "regtest")) {
    throw new Error("Phoenix Direct Solo is restricted to Bitcoin mainnet");
  }
  if (chain.initialblockdownload || chain.blocks < chain.headers) {
    const err = new Error("Bitcoin Core is not fully synchronized");
    err.statusCode = 503;
    throw err;
  }
  const templateRequest = {
    rules: ["segwit"],
    capabilities: ["coinbasevalue", "longpoll"]
  };
  if (body.longpollId !== undefined && body.longpollId !== null) {
    if (typeof body.longpollId !== "string" || body.longpollId.length > 1024) {
      throw new Error("Invalid long-poll identifier");
    }
    templateRequest.longpollid = body.longpollId;
  }
  const template = await rpc("getblocktemplate", [templateRequest], signal);
  const job = makeWork(template, payoutAddress, extranonce, chain.chain);
  pruneJobs();
  const existing = jobs.get(job.jobId);
  if (existing) {
    // Retain the exact header already being scanned, but extend its cache lease.
    existing.createdAt = Date.now();
  } else {
    jobs.set(job.jobId, job);
  }
  return {
    jobId: job.jobId,
    height: job.height,
    header: existing ? existing.header : job.header,
    target: job.target,
    bits: job.bits,
    previousBlockHash: job.previousBlockHash,
    transactionCount: job.transactionCount,
    longpollid: template.longpollid || null,
    nodeBlocks: chain.blocks,
    network: chain.chain
  };
}

async function submitWithRpc(body, rpc, signal) {
  const jobId = body?.jobId;
  const nonce = body?.nonce;
  if (typeof jobId !== "string" || !/^[0-9a-f]{64}$/i.test(jobId)) throw new Error("Invalid job id");
  const job = jobs.get(jobId);
  if (!job) {
    const err = new Error("Mining job expired or was replaced");
    err.statusCode = 404;
    throw err;
  }

  const block = buildBlock(job, nonce);
  const header = block.subarray(0, 80);
  if (!isHashWithinTarget(header, job.target)) {
    throw new Error("Submitted nonce does not meet the active Bitcoin network target");
  }

  const blockHex = block.toString("hex");
  const proposal = await rpc("getblocktemplate", [{ mode: "proposal", data: blockHex }], signal);
  if (proposal !== null) {
    return {
      accepted: false,
      stage: "proposal",
      response: String(proposal || "Node rejected block proposal"),
      blockHash: reverse(sha256d(header)).toString("hex")
    };
  }

  const result = await rpc("submitblock", [blockHex], signal);
  const accepted = result === null;
  return {
    accepted,
    stage: "submitblock",
    response: accepted
      ? "Bitcoin Core accepted the block submission; chain confirmation is still separate"
      : String(result),
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
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw Object.assign(new Error("Invalid JSON body"), { statusCode: 400 });
  }
}

export function createGatewayServer({
  token = process.env.PHOENIX_API_TOKEN,
  rpc = rpcCall,
  allowRegtest = false
} = {}) {
  if (!token || Buffer.byteLength(token) < 32) {
    throw new Error("PHOENIX_API_TOKEN must contain at least 32 bytes");
  }

  const handler = async (req, res) => {
    const send = (code, data) => {
      res.writeHead(code, {
        "Content-Type": "application/json",
        "Cache-Control": "no-store",
        "X-Content-Type-Options": "nosniff"
      });
      res.end(JSON.stringify(data));
    };

    if (req.method === "GET" && req.url === "/health") {
      if (!safeEqual((req.headers.authorization || "").replace(/^Bearer\s+/i, ""), token)) {
        return send(401, { error: "Unauthorized" });
      }
      return send(200, { ok: true, service: "phoenix-direct-node-gateway" });
    }

    if (req.method !== "POST" || !["/v1/work", "/v1/submit"].includes(req.url)) {
      return send(404, { error: "Not found" });
    }
    if (!safeEqual((req.headers.authorization || "").replace(/^Bearer\s+/i, ""), token)) {
      return send(401, { error: "Unauthorized" });
    }

    const requestController = new AbortController();
    res.on("close", () => {
      if (!res.writableEnded) requestController.abort();
    });
    try {
      const body = await readJson(req);
      const result = req.url === "/v1/work"
        ? await getWorkWithRpc(body, rpc, allowRegtest, requestController.signal)
        : await submitWithRpc(body, rpc, requestController.signal);
      return send(200, result);
    } catch (error) {
      return send(error.statusCode || 400, { error: error.message || "Request failed" });
    }
  };

  return http.createServer((req, res) => {
    handler(req, res).catch(() => {
      if (!res.destroyed && !res.headersSent) res.writeHead(500).end();
    });
  });
}

const isMain = process.argv[1] && new URL("file:" + process.argv[1]).href === import.meta.url;
if (isMain) {
  const server = createGatewayServer();
  const host = process.env.PHOENIX_BIND_HOST || "127.0.0.1";
  const port = Number(process.env.PHOENIX_PORT || 8787);
  server.listen(port, host, () => {
    process.stdout.write("Phoenix Direct Node Gateway listening on " + host + ":" + port +
      "; put HTTPS/VPN ingress in front\n");
  });
}
