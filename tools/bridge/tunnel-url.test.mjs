import { test } from "node:test";
import assert from "node:assert/strict";
import { quickTunnelUrl } from "./tunnel-url.mjs";

test("quick tunnel discovery ignores Cloudflare API errors", () => {
  assert.equal(quickTunnelUrl('Post "https://api.trycloudflare.com/tunnel": x509 unknown authority'), null);
  assert.equal(quickTunnelUrl("Requesting new quick Tunnel on trycloudflare.com"), null);
  assert.equal(quickTunnelUrl("https://example.com"), null);
});

test("quick tunnel discovery accepts generated public endpoint", () => {
  assert.equal(quickTunnelUrl("| https://real-generated-endpoint.trycloudflare.com |"), "https://real-generated-endpoint.trycloudflare.com");
});
