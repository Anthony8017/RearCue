# 启动 PC 桥（票 #116）：node bridge.mjs [+ --demo 示例源] [+ --no-tunnel 仅本机]
# 隧道 URL 落 tools/bridge/bridge.url；开机自启用 enable-autostart.ps1。
param(
    [switch]$Demo,
    [switch]$NoTunnel
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$nodeArgs = @()
if ($Demo)     { $nodeArgs += "--demo" }
if ($NoTunnel) { $nodeArgs += "--no-tunnel" }
Write-Host "[start] bridge demo=$Demo tunnel=$(-not $NoTunnel)"
node (Join-Path $here "bridge.mjs") @nodeArgs
