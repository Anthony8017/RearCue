import http from "node:http";
import { randomBytes, timingSafeEqual } from "node:crypto";
import { writeFileSync } from "node:fs";

/** Separate loopback listener: the public tunnel never reaches mirror administration. */
export async function startMirrorManager({ file, snapshot, apply }) {
  const token = randomBytes(32).toString("base64url");
  const server = http.createServer(async (req, res) => {
    const json = (code, body) => res.writeHead(code, { "Content-Type": "application/json; charset=utf-8" }).end(JSON.stringify(body));
    const actual = Buffer.from(String(req.headers.authorization || "").replace(/^Bearer\s+/i, ""));
    const expected = Buffer.from(token);
    if (req.headers.origin || req.headers["x-forwarded-for"] || req.headers["cf-connecting-ip"] ||
        actual.length !== expected.length || !timingSafeEqual(actual, expected)) {
      json(401, { error: "unauthorized" }); return;
    }
    try {
      if (req.method === "GET" && req.url === "/sessions") { json(200, await snapshot()); return; }
      if (req.method === "POST" && req.url === "/apply") {
        const buffers = [];
        let bytes = 0;
        for await (const buffer of req) {
          bytes += buffer.length;
          if (bytes > 1_000_000) { json(413, { error: "名单操作过大" }); return; }
          buffers.push(buffer);
        }
        json(200, await apply(JSON.parse(Buffer.concat(buffers).toString("utf8")))); return;
      }
      json(404, { error: "not-found" });
    } catch (error) { json(409, { error: error.message || "名单保存失败" }); }
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  try { writeFileSync(file, JSON.stringify({ port: server.address().port, token, pid: process.pid }), { mode: 0o600 }); }
  catch (error) { server.close(); throw error; }
  return server;
}
