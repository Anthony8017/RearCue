# RearCue PC 桥托盘图标（票 #171）：桥没有窗口，这是它唯一的人机界面。
#
# 契约（与 tools/bridge/tray.mjs 两侧对齐，改一处必改另一处）：
#   桥把状态写成一行 JSON 到 -StateFile，本脚本每秒读一次：
#     {"v":1,"ready":true,"phone":true,"url":"https://xxx.trycloudflare.com","port":18787,"log":"…\\bridge.log"}
#   桥要弹气泡时把一行 JSON 追加到 -EventFile：{"kind":"url-changed","text":"…"}
#   父进程（桥）一死，本脚本立刻自退——图标在＝桥在，不留孤儿图标。
#
# 为什么是 PowerShell + WinForms 而不是 node 的托盘库：桥是零 npm 依赖的纯 Node 进程
# （ADR 0006），本机也没有 pwsh 7；System.Windows.Forms 的 NotifyIcon 是唯一不引入
# 依赖、也不给桥加打包负担的做法。
#
# 本文件是 UTF-8 **带 BOM**——**必须保住这个 BOM**：Windows PowerShell 5.1 对无 BOM 文件按
# ANSI 解码，本文件里的中文会毁掉引号配对，报出一堆 "Unexpected token" 语法错、托盘进程 exit 1
# （2026-09-29 实测：无 BOM 时 `"RearCue PC 桥"` 被解码成 `"RearCue PC 妗?`，整个脚本解析失败）。
# 仓内 status.ps1 同例；enable-autostart.ps1 是无 BOM 的，所以它的中文字符串一律收尾留 ASCII。
param(
    [Parameter(Mandatory = $true)][int] $ParentPid,
    [Parameter(Mandatory = $true)][int] $Port,
    [string] $Log = "",
    [Parameter(Mandatory = $true)][string] $StateFile,
    [Parameter(Mandatory = $true)][string] $EventFile,
    [Parameter(Mandatory = $true)][string] $IconDir
)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

# 退出留痕（spec 0019-1 / #185）：本进程是「桥被硬杀」时唯一的目击者——任何退出路径
# 都往 -Log 落一行结构化事实（谁退的、为什么、相关 PID、时刻），事后读日志就能归因。
# reason 固定四类：bridge-gone（父进程没了）/ health-miss（连续 3 拍探不通端口）/
# takeover（单例接管清场，由幸存者补记）/ manual-exit（机主右键「退出桥」）。
# UTF8Encoding($false)＝无 BOM 追加，和桥侧 Node 追加的日志保持同一种字节。
function Write-TrayTrail([string] $Tag, [string] $Reason, [string] $Extra = "") {
    try {
        $line = "[trail] {0} reason={1} pid={2} parentPid={3} port={4}{5} at={6}" -f `
            $Tag, $Reason, $PID, $ParentPid, $Port, $(if ($Extra) { " $Extra" } else { "" }), (Get-Date -Format o)
        if ($Log) {
            [System.IO.File]::AppendAllText($Log, $line + "`n", (New-Object System.Text.UTF8Encoding($false)))
        }
    } catch { }   # 留痕失败不能拦住退出本身
}

# 单例语义：托盘永远只该有一份——"图标在＝桥在"这条不变量靠它成立。
# 桥被异常杀掉时（taskkill /F、启动器中间环节先死）旧托盘可能留下，新桥起来会再起一个：
# 两个图标、其中一个还显示着失效地址。所以新托盘**接管即清场**：先把别的 tray.ps1 进程收掉，
# 自己再画图标。
#
# 匹配必须**用正则钉住 `-File <路径>\tray.ps1` 这个形态**，不能退化成 `-like "*-File*tray.ps1*"`：
# 后者会把"命令行里恰好提到这两个词"的无关进程也算成托盘——2026-09-30 实测，
# 一条排查用的 pwsh 命令就被自己匹配上了（差点误杀正在跑的工具进程，也把"托盘有几份"数错）。
$usurped = @(Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe'" |
    Where-Object { $_.ProcessId -ne $PID -and $_.CommandLine -match '-File\s+"?[^"]*[\\/]tray\.ps1' })
foreach ($u in $usurped) { taskkill /F /PID $u.ProcessId 2>$null | Out-Null }
if ($usurped.Count -gt 0) {
    # 被接管的旧托盘是被 taskkill /F 硬杀的，自己留不了痕——由接管者补记这条事实。
    Write-TrayTrail "tray-exit" "takeover" ("killedPids=" + (($usurped | ForEach-Object { $_.ProcessId }) -join ","))
}

$iconReady = Join-Path $IconDir "ready.ico"
$iconPhone = Join-Path $IconDir "phone.ico"
$iconPending = Join-Path $IconDir "pending.ico"
$iconLight = Join-Path $IconDir "light.ico"
foreach ($f in @($iconReady, $iconPhone, $iconPending, $iconLight)) {
    if (-not (Test-Path -LiteralPath $f)) { throw "缺少图标文件: $f（跑 node tools/bridge/make-icons.mjs 生成）" }
}

function Test-LightTaskbar {
    try {
        $v = Get-ItemPropertyValue -Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize" -Name "SystemUsesLightTheme" -ErrorAction Stop
        return ($v -eq 1)
    } catch { return $false }
}

[System.Windows.Forms.NotifyIcon]$ni = New-Object System.Windows.Forms.NotifyIcon
$script:light = Test-LightTaskbar
$ni.Icon = New-Object System.Drawing.Icon($(if ($script:light) { $iconLight } else { $iconPending }))
$ni.Text = "RearCue PC 桥"
$ni.Visible = $true

function Get-TrayState {
    try {
        if (Test-Path -LiteralPath $StateFile) {
            return (Get-Content -LiteralPath $StateFile -Raw -Encoding UTF8 | ConvertFrom-Json)
        }
    } catch { }
    return $null
}

# 图标三态：隧道就绪＋手机已连＝晴空蓝、就绪未连＝薄荷绿、隧道未就绪＝琥珀黄；
# 浅色任务栏改用深色版（白色/彩色在浅底上看不见——三态在浅色任务栏上不区分，维持既有口径）。
function Update-Tray {
    $st = Get-TrayState
    $ready = $false
    $phone = $false
    $url = ""
    if ($st) {
        $ready = [bool]$st.ready
        $phone = [bool]$st.phone
        if ($st.url) { $url = [string]$st.url }
    }
    $icon = if ($script:light) { $iconLight } elseif ($ready -and $phone) { $iconPhone } elseif ($ready) { $iconReady } else { $iconPending }
    $ni.Icon = New-Object System.Drawing.Icon($icon)
    $addr = if ($url) { $url } else { "隧道未就绪" }
    $txt = "RearCue PC 桥 - $addr"
    if ($phone -and ($txt.Length + 7) -le 63) { $txt = "$txt · 手机已连" }   # 63 字符硬上限内才补，别把地址截掉
    if ($txt.Length -gt 63) { $txt = $txt.Substring(0, 63) }
    $ni.Text = $txt
}

function Invoke-Balloon([string] $text) {
    if (-not $text) { return }
    if ($text.Length -gt 250) { $text = $text.Substring(0, 250) }
    try {
        $ni.BalloonTipTitle = "RearCue PC 桥"
        $ni.BalloonTipText = $text
        $ni.ShowBalloonTip(8000)
    } catch { }
}

# 桥追加的事件：只做气泡，读完即截断（事件文件不参与状态判定）。
function Read-TrayEvents {
    try {
        if (-not (Test-Path -LiteralPath $EventFile)) { return }
        $lines = @(Get-Content -LiteralPath $EventFile -Encoding UTF8 -ErrorAction Stop)
        Set-Content -LiteralPath $EventFile -Value "" -Encoding UTF8 -NoNewline
        foreach ($line in $lines) {
            if (-not $line.Trim()) { continue }
            try {
                $ev = $line | ConvertFrom-Json
                if ($ev.text) { Invoke-Balloon ([string]$ev.text) }
            } catch { }
        }
    } catch { }
}

# 桥还活着吗：直接问它的端口。
# 为什么不能只看父进程（2026-09-30 实测两种孤儿）：父进程可能是 cmd/powershell 启动器，
# 中间任何一环先死、或者桥是被 taskkill /F 单独收掉的，父进程都可能还挂着——
# 于是托盘留着一个**永远显示旧地址的图标**（机主照着复制只会拿到失效域名）。
# 连续 3 拍（约 3 秒）问不通就自我了断：图标在＝桥在，这条不变量由图标自己保证。
function Test-BridgeAlive {
    try {
        $req = [System.Net.WebRequest]::Create("http://127.0.0.1:$Port/health")
        $req.Method = "GET"
        $req.Timeout = 1200
        $req.Proxy = $null
        $resp = $req.GetResponse()
        $resp.Close()
        return $true
    } catch { return $false }
}

$script:deadTicks = 0
function Invoke-Tick {
    try {
        $processGone = -not (Get-Process -Id $ParentPid -ErrorAction SilentlyContinue)
        if ($processGone -or -not (Test-BridgeAlive)) {
            $script:deadTicks++
            if ($script:deadTicks -ge 3) {
                # 原因区分：父进程没了＝桥没了；父进程还在但端口探不通＝桥半死/被挂起。
                $why = if ($processGone) { "bridge-gone" } else { "health-miss" }
                Write-TrayTrail "tray-exit" "$why deadTicks=$($script:deadTicks)"
                $ni.Visible = $false
                $ni.Dispose()
                [System.Windows.Forms.Application]::Exit()
                return
            }
        } else {
            $script:deadTicks = 0
        }
        Update-Tray
        Read-TrayEvents
    } catch { }   # 单拍失败不掀桌子：下一拍再来
}

# 左键点击＝把桥地址复制到剪贴板并弹一句（手填入口在手机上，这里给最短路径）。
$ni.add_MouseClick({
    param($sender, $e)
    if ($e.Button -ne [System.Windows.Forms.MouseButtons]::Left) { return }
    $st = Get-TrayState
    if ($st -and $st.url) {
        try {
            [System.Windows.Forms.Clipboard]::SetText([string]$st.url)
            Invoke-Balloon "桥地址已复制：$($st.url)"
        } catch { Invoke-Balloon "复制失败，右键查看地址" }
    } else {
        Invoke-Balloon "隧道还没就绪，地址一拿到就会复制到这里"
    }
})

[System.Windows.Forms.ContextMenuStrip]$menu = New-Object System.Windows.Forms.ContextMenuStrip
$itemCopy = $menu.Items.Add("复制桥地址")
$itemCopy.add_Click({
    $st = Get-TrayState
    if ($st -and $st.url) {
        try {
            [System.Windows.Forms.Clipboard]::SetText([string]$st.url)
            Invoke-Balloon "桥地址已复制：$($st.url)"
        } catch { Invoke-Balloon "复制失败" }
    } else { Invoke-Balloon "隧道还没就绪" }
})
$itemOpen = $menu.Items.Add("打开 bridge.log")
$itemOpen.add_Click({
    try { if ($Log -and (Test-Path -LiteralPath $Log)) { Start-Process notepad.exe -ArgumentList "`"$Log`"" } }
    catch { }
})
$itemExit = $menu.Items.Add("退出桥")
$itemExit.add_Click({
    # 机主手动退出＝彻底退出（票 #189）：**先停计划任务＋立停止旗，再收桥进程**。
    # 两层都要堵：任务动作拉起的 start-bridge.cmd 里有重试引擎（会被 Stop-ScheduledTask
    # 连树收掉）；机主**手动**跑 start-bridge.cmd 时没有任务，重试引擎还在——停止旗
    # （<Log>.stopflag）让引擎把这次退出当「干净停」，不重来。旗文件每次桥启动时自清。
    # 桥是被 Stop-Process -Force 硬杀的，自己收不到信号、留不了痕——这里替它补一条
    # （谁杀的、杀的谁，含任务真实处置结果），再记自己这条。留痕先落：万一收进程树
    # 收得比预期深，这条审计事实也已经写进日志了。
    $taskState = "absent"
    try {
        if (Get-ScheduledTask -TaskName "RearCueBridge" -ErrorAction SilentlyContinue) {
            Stop-ScheduledTask -TaskName "RearCueBridge" -ErrorAction SilentlyContinue
            $taskState = "stopped"
        }
    } catch { $taskState = "stop-failed" }
    try {
        if ($Log) { [System.IO.File]::WriteAllText("$Log.stopflag", "manual-exit`n", (New-Object System.Text.UTF8Encoding($false))) }
    } catch { }
    Write-TrayTrail "bridge-exit" "manual-exit actor=tray task=$taskState"
    try { Stop-Process -Id $ParentPid -Force -ErrorAction SilentlyContinue } catch { }
    Write-TrayTrail "tray-exit" "manual-exit"
    $ni.Visible = $false
    $ni.Dispose()
    [System.Windows.Forms.Application]::Exit()
})
$ni.ContextMenuStrip = $menu

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 1000
$timer.add_Tick({ Invoke-Tick })
$timer.Start()

Update-Tray
$script:announced = $false
[System.Windows.Forms.Application]::Run()
