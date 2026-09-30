# 启动 PC 桥（票 #116）：node bridge.mjs [+ --demo 示例源] [+ --no-tunnel 仅本机]
# 隧道默认 cloudflared quick tunnel：URL 落 tools/bridge/bridge.url 并自动 adb 推给手机
# （BRIDGE_TUNNEL=tunwg 可切回）；开机自启用 enable-autostart.ps1（走 start-bridge.cmd 落日志）。
# 起隧道时会同时冒出任务栏托盘图标（票 #171）：图标在＝桥在，点一下复制桥地址。
param(
    [switch]$Demo,
    [switch]$NoTunnel
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
# Claude hooks 幂等注册（#119：新机/重装后 start 一次即接上 Stop→idle；无变化不写盘）。
node (Join-Path $here 'adapters\register-claude-hooks.mjs')
$nodeArgs = @()
if ($Demo)     { $nodeArgs += "--demo" }
if ($NoTunnel) { $nodeArgs += "--no-tunnel" }
Write-Host "[start] bridge demo=$Demo tunnel=$(-not $NoTunnel)"
node (Join-Path $here "bridge.mjs") @nodeArgs
