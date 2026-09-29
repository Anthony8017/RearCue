# PC 桥体检：一条命令看清「桥在不在、隧道通不通、有没有会话在册」。
#
# 为什么单拎一个脚本：桥有三种坏法，肉眼都看不出来——
#   ① 进程没了（隧道域名连 DNS 都消失，手机侧只剩 http 530）；
#   ② 进程在、cloudflared 掉了（本机通、外网 530，手机连不上）；
#   ③ 都通但一个会话都没在册（手机上看是空 Agent 页、点空白没反应）。
# 本脚本把三层事实分别打出来，最后给一句结论与对应修法。
#
# 本文件是 UTF-8 **带 BOM**（仓内 .ps1 多为无 BOM）：Windows PowerShell 5.1 对无 BOM 文件
# 按 ANSI 解码，中文会变乱码；此处特意保留 BOM，改文件时别把 BOM 转存掉。
param(
    [int] $Port = 18787,
    [int] $TimeoutSec = 15,
    [string] $TaskName = "RearCueBridge"
)
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$log = Join-Path $here "bridge.log"
$urlFile = Join-Path $here "bridge.url"

function Test-BridgeHealth([string] $Base) {
    try {
        $r = Invoke-WebRequest -Uri "$Base/health" -TimeoutSec $TimeoutSec -UseBasicParsing
        return ($r.StatusCode -eq 200)
    } catch { return $false }
}

function Get-BridgeSessionCount([string] $Base) {
    try {
        $r = Invoke-WebRequest -Uri "$Base/snapshot" -TimeoutSec $TimeoutSec -UseBasicParsing
        return @(($r.Content | ConvertFrom-Json).sessions).Count
    } catch { return $null }
}

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
$taskState = if ($task) { $task.State } else { "未注册" }
$procs = @(Get-CimInstance Win32_Process -Filter "Name = 'node.exe'" |
    Where-Object { $_.CommandLine -like "*bridge.mjs*" })
$localOk = Test-BridgeHealth "http://127.0.0.1:$Port"
$url = if (Test-Path -LiteralPath $urlFile) { (Get-Content -LiteralPath $urlFile -Raw).Trim() } else { "" }
$tunnelOk = if ($url) { Test-BridgeHealth $url } else { $false }
$sessions = if ($localOk) { Get-BridgeSessionCount "http://127.0.0.1:$Port" } else { $null }

$online = $localOk -and $tunnelOk
$verdict =
    if ($online -and $sessions -gt 0) { "在线（本机 + 隧道都通，有会话在册）" }
    elseif ($online) { "在线，但一个会话都没在册——手机上是空 Agent 页" }
    elseif ($localOk) { "半死：桥进程在跑，隧道断了——手机连不上（要重启桥换隧道）" }
    elseif ($procs.Count) { "半死：桥进程在，但本机端口 $Port 没起来（启动中，或启动就崩了）" }
    else { "离线：桥进程根本没在跑" }

Write-Host ""
Write-Host "[桥状态] $verdict"
Write-Host "  计划任务 $TaskName : $taskState"
Write-Host "  桥进程                        : $(if ($procs.Count) { 'pid ' + ($procs.ProcessId -join ',') } else { '没有' })"
Write-Host "  本机 http://127.0.0.1:$Port    : $(if ($localOk) { '通' } else { '不通' })"
Write-Host "  隧道 $(if ($url) { $url } else { '(bridge.url 还没有)' })"
Write-Host "  隧道可达                      : $(if ($tunnelOk) { '通（手机能连）' } else { '不通' })"
Write-Host "  在册会话                      : $(if ($null -eq $sessions) { '问不到（本机都不通）' } else { $sessions })"
Write-Host ""

if (-not $online) {
    if ($localOk) {
        Write-Host "修复（隧道断了，重启桥会换一条新隧道并自动推给手机）:"
        Write-Host "  Stop-ScheduledTask -TaskName $TaskName; Start-ScheduledTask -TaskName $TaskName"
    } else {
        Write-Host "修复（拉起桥；计划任务会在登录时自启、崩了自动重启）:"
        Write-Host "  Start-ScheduledTask -TaskName $TaskName"
    }
    if (Test-Path -LiteralPath $log) {
        Write-Host "看日志尾部（桥自己记的退出原因）:"
        Write-Host "  Get-Content `"$log`" -Tail 20"
    }
    Write-Host ""
}

exit $(if ($online) { 0 } else { 1 })
