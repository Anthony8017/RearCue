import { request } from "node:http";

// Windows may assign a browser-blocked ephemeral port (e.g. 6000 or 6667).
// These loopback protocol fixtures use native HTTP; status and JSON remain real responses.
export function fixtureFetch(url, { method = "GET", headers = {}, body } = {}) {
  return new Promise((resolve, reject) => {
    const req = request(url, { method, headers }, res => {
      const chunks = [];
      res.on("data", chunk => chunks.push(chunk));
      res.on("error", reject);
      res.on("end", () => {
        const text = Buffer.concat(chunks).toString("utf8");
        resolve({ status: res.statusCode, ok: res.statusCode >= 200 && res.statusCode < 300,
          json: async () => JSON.parse(text), text: async () => text });
      });
    });
    req.on("error", reject);
    req.setTimeout(10000, () => req.destroy(new Error("loopback fixture timeout")));
    req.end(body);
  });
}
