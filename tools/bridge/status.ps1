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

# 探活参数：本机地址与隧道都是直连，别让环境里的 HTTP_PROXY 把探活带偏——
# 2026-09-29 实测过一次死代理（变量指向没人监听的端口）把所有出站请求打成 Connection error，
# 那种故障下"体检说桥不通、其实桥好好的"，最难查。
#
# 绕代理的正确姿势**不是 `-NoProxy`**：那个参数只有 PowerShell 7 有，Windows PowerShell 5.1
# 上会直接抛 "找不到与参数名称 NoProxy 匹配的参数"——脚本语法错、体检恒报不通
# （2026-09-29 亲手踩过：改完没在 5.1 上跑，就把那版当成"加固"了）。
# 5.1 上 `-Proxy $null` 也不行（参数绑定拒绝空值）。这里两手：
#   * 有 -NoProxy 就用它；
#   * 否则探活期间临时摘掉进程内的代理环境变量（IWR 的默认代理就是从它们来的），探完复原——
#     只影响本进程、不写注册表。
$script:NoProxySupported = (Get-Command Invoke-WebRequest).Parameters.ContainsKey("NoProxy")

function Invoke-DirectWebRequest([string] $Uri) {
    if ($script:NoProxySupported) {
        return Invoke-WebRequest -Uri $Uri -TimeoutSec $TimeoutSec -UseBasicParsing -NoProxy
    }
    $saved = @{}
    foreach ($name in @("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy", "ALL_PROXY", "all_proxy")) {
        $saved[$name] = [System.Environment]::GetEnvironmentVariable($name)
        [System.Environment]::SetEnvironmentVariable($name, $null)
    }
    try {
        return Invoke-WebRequest -Uri $Uri -TimeoutSec $TimeoutSec -UseBasicParsing
    } finally {
        foreach ($name in $saved.Keys) {
            [System.Environment]::SetEnvironmentVariable($name, $saved[$name])
        }
    }
}

function Get-BridgeBaseUrl([string] $Raw) {
    if (-not $Raw) { return "" }
    try { return ([Uri]$Raw).GetLeftPart([UriPartial]::Path).TrimEnd("/") } catch { return $Raw.TrimEnd("/") }
}

function Test-BridgeHealth([string] $Base) {
    try {
        $r = Invoke-DirectWebRequest "$Base/health"
        return ($r.StatusCode -eq 200)
    } catch { return $false }
}

function Get-BridgeSessionCount([string] $Base) {
    try {
        $r = Invoke-DirectWebRequest "$Base/snapshot"
        return @(($r.Content | ConvertFrom-Json).sessions).Count
    } catch { return $null }
}

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
$taskState = if ($task) { $task.State } else { "未注册" }
$procs = @(Get-CimInstance Win32_Process -Filter "Name = 'node.exe'" |
    Where-Object { $_.CommandLine -like "*bridge.mjs*" })
# 托盘是桥的人机界面（票 #171）：图标在＝桥在——这一行是"桥到底有没有活着"的第二证据，
# 也是"我没看见图标"时唯一能问的地方（图标可能在溢出区、或托盘进程自己崩了）。
$trayProcs = @(Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe'" |
    Where-Object { $_.CommandLine -like "*tray.ps1*" })
$localOk = Test-BridgeHealth "http://127.0.0.1:$Port"
$url = if (Test-Path -LiteralPath $urlFile) { (Get-Content -LiteralPath $urlFile -Raw).Trim() } else { "" }
$baseUrl = Get-BridgeBaseUrl $url
$tunnelOk = if ($baseUrl) { Test-BridgeHealth $baseUrl } else { $false }
$sessions = if ($localOk) { Get-BridgeSessionCount "http://127.0.0.1:$Port" } else { $null }

$online = $localOk -and $tunnelOk
$verdict =
    if ($online -and $sessions -gt 0) { "在线（本机 + 隧道都通，有会话在册）" }
    elseif ($online) { "在线，但一个会话都没在册——手机上是空 Agent 页" }
    elseif ($localOk) { "半死：桥进程在跑，隧道当前不通（看门狗每 5s 会自动重拉一条；持续不通查 cloudflared）" }
    elseif ($procs.Count) { "半死：桥进程在，但本机端口 $Port 没起来（启动中，或启动就崩了）" }
    else { "离线：桥进程根本没在跑" }

Write-Host ""
Write-Host "[桥状态] $verdict"
Write-Host "  计划任务 $TaskName : $taskState"
Write-Host "  桥进程                        : $(if ($procs.Count) { 'pid ' + ($procs.ProcessId -join ',') } else { '没有' })"
Write-Host "  托盘图标                      : $(if ($trayProcs.Count) { '在（pid ' + ($trayProcs.ProcessId -join ',') + '）' } else { '没有——图标不出现时先看这行' })"
Write-Host "  本机 http://127.0.0.1:$Port    : $(if ($localOk) { '通' } else { '不通' })"
Write-Host "  隧道 $(if ($baseUrl) { $baseUrl } else { '(bridge.url 还没有)' })"
Write-Host "  隧道可达                      : $(if ($tunnelOk) { '通（手机能连）' } else { '不通' })"
Write-Host "  在册会话                      : $(if ($null -eq $sessions) { '问不到（本机都不通）' } else { $sessions })"
Write-Host ""

if (-not $online) {
    if ($localOk) {
        Write-Host "修复（隧道断了，从托盘右键「退出桥」，再双击 start-bridge.cmd 换新隧道）:"
    } else {
        Write-Host "修复（从托盘右键「退出桥」后，双击 tools\bridge\start-bridge.cmd）:"
    }
    if (Test-Path -LiteralPath $log) {
        Write-Host "看日志尾部（桥自己记的退出原因）:"
        Write-Host "  Get-Content `"$log`" -Tail 20"
    }
    Write-Host ""
}

exit $(if ($online) { 0 } else { 1 })
