export function quickTunnelUrl(line) {
  const match = String(line).match(/https:\/\/(?!api\.)[a-z0-9-]+\.trycloudflare\.com\b/i);
  return match?.[0] || null;
}
