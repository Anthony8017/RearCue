import { test } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

test("loopback-only manager apply removes every mobile path, blocks late events, restores current state and survives restart", async (t) => {
  const root = mkdtempSync(join(tmpdir(), "rearcue-mirror-http-"));
  const socket = createServer();
  await new Promise((r) => socket.listen(0, "127.0.0.1", r));
  const port = socket.address().port;
  await new Promise((r) => socket.close(r));
  const base = `http://127.0.0.1:${port}`;
  let child;
  const start = async () => {
    child = spawn(process.execPath, [fileURLToPath(new URL("bridge.mjs", import.meta.url)),
      "--no-tunnel", "--no-codex", "--no-claude", "--no-zcode", "--no-dsh"], {
      env: { ...process.env, BRIDGE_PORT: String(port), BRIDGE_SEQ_FILE: join(root, "seq"), BRIDGE_LOG: join(root, "bridge.log"),
        BRIDGE_IDENTITY_FILE: join(root, "identity"), BRIDGE_ACCESS_TOKEN: "test" }, stdio: "ignore",
    });
    for (let i = 0; i < 100; i++) {
      try { if ((await fetch(`${base}/health`)).ok) return; } catch { /* startup */ }
      await new Promise((r) => setTimeout(r, 50));
    }
    throw new Error("test bridge failed to start");
  };
  const stop = async () => { if (child?.exitCode === null) { const exited = new Promise((r) => child.once("exit", r)); child.kill(); await exited; } };
  t.after(async () => { await stop(); assert.ok(resolve(root).startsWith(join(tmpdir(), "rearcue-mirror-http-"))); rmSync(root, { recursive: true }); });
  await start();
  const inject = async (patch) => (await fetch(`${base}/inject`, { method: "POST", body: JSON.stringify({ source: "codex", sessionId: "a", ...patch }) })).json();
  await inject({ status: "working", userText: "prompt", assistantText: "answer" });
  let connection = JSON.parse(readFileSync(join(root, "seq.manager.json"), "utf8"));
  let local = `http://127.0.0.1:${connection.port}`;
  const admin = async (path, body, headers = {}) => fetch(local + path, { method: body ? "POST" : "GET",
    headers: { Authorization: `Bearer ${connection.token}`, ...headers }, ...(body ? { body: JSON.stringify(body) } : {}) });
  assert.equal((await fetch(local + "/sessions")).status, 401);
  assert.equal((await admin("/sessions", null, { Origin: "https://example.invalid" })).status, 401);
  assert.equal((await fetch(base + "/apply", { method: "POST" })).status, 404);
  const snapshot = await (await admin("/sessions")).json();
  assert.equal(snapshot.sessions[0].state, "enabled");
  assert.equal((await admin("/apply", { revision: snapshot.revision, changes: [{ source: "codex", sessionId: "a", enabled: false }] })).status, 200);
  assert.equal((await (await fetch(base + "/snapshot")).json()).sessions.length, 0);
  assert.equal((await fetch(base + "/history?sessionId=a")).status, 404);
  assert.equal((await fetch(base + "/action", { method: "POST", headers: { Authorization: "Bearer test" }, body: JSON.stringify({ sessionId: "a", action: "approve" }) })).status, 404);
  await inject({ status: "idle", assistantText: "hidden answer", completion: "done" });
  const page = await (await fetch(base + "/events?since=0&wait=0")).json();
  assert.ok(page.events.every((event) => event.kind === "membership"));
  assert.ok(page.events.some((event) => event.membership === "ABSENT" && event.archiveState !== "ARCHIVED"));
  const removed = await (await admin("/sessions")).json();
  assert.equal((await admin("/apply", { revision: removed.revision, changes: [{ source: "codex", sessionId: "a", enabled: true }] })).status, 200);
  const restored = await (await fetch(base + "/events?since=" + page.cursor + "&wait=0")).json();
  assert.ok(restored.events.some((event) => event.membership === "PRESENT"));
  assert.ok(restored.events.filter((event) => event.kind !== "membership").every((event) => !event.voiceEvent));
  if (process.platform === "win32") {
    const original = readFileSync(new URL("session-manager.ps1", import.meta.url), "utf8");
    const automated = original.replace("try { [System.Windows.Forms.Application]::Run($form) }", String.raw`
$form.Opacity = 0
$form.ShowInTaskbar = $false
$script:smokeFailure = $null
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class ManagerUiSmoke {
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr handle);
}
'@
$form.add_Shown({
    try {
        if (-not [ManagerUiSmoke]::IsWindowVisible($form.Handle)) { throw 'hidden launcher left Session Manager invisible' }
        $found = [RearCueManagerWindow]::FindWindow([IntPtr]::Zero, 'RearCue 会话管理')
        if ($found -ne $form.Handle) { throw 'single-window activation cannot find Session Manager' }
        if ($grid.Rows.Count -ne 1) { throw 'fixture row missing from actual window' }
        if ($env:RCU_MANAGER_QA_IMAGE) {
            $bitmap = New-Object System.Drawing.Bitmap($form.Width, $form.Height)
            $form.DrawToBitmap($bitmap, (New-Object System.Drawing.Rectangle(0,0,$form.Width,$form.Height)))
            $bitmap.Save($env:RCU_MANAGER_QA_IMAGE)
            $bitmap.Dispose()
        }
        $grid.Rows[0].Cells[0].Value = $true
        $remove.PerformClick()
        $beforeApply = Invoke-Manager 'GET' '/sessions'
        if ($beforeApply.sessions[0].state -ne 'enabled') { throw 'checkbox/staging changed live roster before Apply' }
        $apply.PerformClick()
        $afterApply = Invoke-Manager 'GET' '/sessions'
        if ($afterApply.sessions[0].state -ne 'removed') { throw "Apply did not remove conversation: $($status.Text)" }
        $grid.Rows[0].Cells[0].Value = $true
        $add.PerformClick()
        # Closing an unapplied restore must preserve removal and keep the bridge alive.
    } catch { $script:smokeFailure = $_ }
    finally { $form.Close() }
})
try { [System.Windows.Forms.Application]::Run($form) }`) + "\nif ($script:smokeFailure) { Write-Error $script:smokeFailure; exit 1 }\n";
    const script = join(root, "window-smoke.ps1");
    writeFileSync(script, "\ufeff" + automated.replace(/^\ufeff/, ""), "utf8");
    const ui = spawn("powershell.exe", ["-NoProfile", "-STA", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-File", script,
      "-ConnectionFile", join(root, "seq.manager.json")], { windowsHide: true, stdio: ["ignore", "pipe", "pipe"] });
    let output = "";
    ui.stdout.on("data", (buffer) => { output += buffer; });
    ui.stderr.on("data", (buffer) => { output += buffer; });
    const uiCode = await new Promise((resolve, reject) => { ui.once("exit", resolve); ui.once("error", reject); });
    assert.equal(uiCode, 0, output);
    assert.equal((await fetch(base + "/health")).status, 200);
    assert.equal((await (await fetch(base + "/snapshot")).json()).sessions.length, 0);
  }
  const enabled = await (await admin("/sessions")).json();
  await admin("/apply", { revision: enabled.revision, changes: [{ source: "codex", sessionId: "a", enabled: false }] });
  await inject({ status: "idle", assistantText: "hidden update before restart" });
  const beforeRestart = await (await fetch(base + "/snapshot")).json();
  assert.equal(Number(readFileSync(join(root, "seq"), "utf8")), beforeRestart.cursor);
  await stop(); await start();
  await inject({ status: "working", title: "renamed" });
  const rebooted = await (await fetch(base + "/snapshot")).json();
  assert.equal(rebooted.sessions.length, 0);
  assert.ok(rebooted.cursor > beforeRestart.cursor);
});
