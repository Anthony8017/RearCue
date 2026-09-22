param(
    [string]$Session = 'C:\Users\13691\Desktop\RearCue-wt\03\docs\poc-logs\20260923-043417-rear-safe-area',
    [string]$LogFile = 'logcat-rearcue.txt',
    [string[]]$Shots = @('01-rear-drift.png', '02-rear-drift.png', '03-rear-drift.png'),
    [string]$Out = 'measure.txt'
)
# 像素测量（本地，不需要设备）：背屏截图的内容 bbox vs 运行时内容安全矩形。
# contentRect 取 logcat 里【最后一条】rear-safe-geometry——锁屏首投会先在主屏建实例再搬上背屏，
# 日志里两块屏的 geometry 各有一条，取第一条会拿错屏（本轮首测即踩中，见 erratum.md）。
# 坑：param([string]$X) 的变量是强类型，后续同名（不分大小写）赋数组会被静默转成空格拼接的
# 字符串（本轮首测即踩中）；本地数组一律换变量名，或直接用 [IO.File]::ReadAllLines 读。
$ErrorActionPreference = 'Continue'
$dir = $Session
$shotsDir = Join-Path $dir 'screenshots'
Add-Type -AssemblyName System.Drawing
$measure = Join-Path $dir $Out
$lines = New-Object System.Collections.Generic.List[string]

$logLines = [IO.File]::ReadAllLines((Join-Path $dir $LogFile), [System.Text.UTF8Encoding]::new($false))
foreach ($row in $logLines) {
    if ($row -match 'rear-safe-(geometry|place)') { $lines.Add(('log  {0}' -f $row.Trim())) }
}
$all = $logLines -join "`n"
$geoMatches = [regex]::Matches($all, 'DisplayGeometry\(width=(\d+), height=(\d+),[\s\S]*?content=PxRect\(left=(-?\d+), top=(-?\d+), right=(-?\d+), bottom=(-?\d+)\)')
if ($geoMatches.Count -eq 0) {
    $lines.Add('verdict : E15-RUN-INVALID (logcat 里没有 contentRect，几何未采集)')
    $lines | Out-File $measure -Encoding utf8
    return
}
$geo = $geoMatches[$geoMatches.Count - 1]
$dispW = [int]$geo.Groups[1].Value
$dispH = [int]$geo.Groups[2].Value
$cl = [int]$geo.Groups[3].Value
$ct = [int]$geo.Groups[4].Value
$cr = [int]$geo.Groups[5].Value
$cb = [int]$geo.Groups[6].Value
$lines.Add(('geometry : display={0}x{1}（取最后一条 rear-safe-geometry = 背屏实例）' -f $dispW, $dispH))
$lines.Add(('content-rect : [{0}, {1}, {2}, {3}]  usable-band={4}x{5}' -f $cl, $ct, $cr, $cb, ($dispW - $cl), $dispH))

$boxes = @()
$index = 0
foreach ($shot in $Shots) {
    $index++
    $path = Join-Path $shotsDir $shot
    $bmp = [System.Drawing.Bitmap]::FromFile($path)
    $bmpW = $bmp.Width
    $bmpH = $bmp.Height
    $data = $bmp.LockBits(
        [System.Drawing.Rectangle]::new(0, 0, $bmpW, $bmpH),
        [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $stride = $data.Stride
    $bytes = New-Object byte[] ($stride * $bmpH)
    [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
    $bmp.UnlockBits($data)
    $bmp.Dispose()
    $minX = [int]::MaxValue; $minY = [int]::MaxValue; $maxX = -1; $maxY = -1
    for ($y = 0; $y -lt $bmpH; $y++) {
        $row = $y * $stride
        for ($x = 0; $x -lt $bmpW * 4; $x += 4) {
            if (($bytes[$row + $x] -ne 0) -or ($bytes[$row + $x + 1] -ne 0) -or ($bytes[$row + $x + 2] -ne 0)) {
                $px = $x / 4
                if ($px -lt $minX) { $minX = $px }
                if ($px -gt $maxX) { $maxX = $px }
                if ($y -lt $minY) { $minY = $y }
                if ($y -gt $maxY) { $maxY = $y }
            }
        }
    }
    $boxes += , @($minX, $minY, $maxX, $maxY)
    $inside = ($minX -ge $cl) -and ($minY -ge $ct) -and ($maxX -le $cr) -and ($maxY -le $cb)
    $verdict = if ($maxX -lt 0) { 'E15-RUN-INVALID (全黑截图，背屏没亮/没上屏)' }
        elseif ($inside) { 'E15-SAFE-PASS' } else { 'E15-OUT-OF-SAFE' }
    $lines.Add(('shot {0} {1} : bbox=[{2}, {3}, {4}, {5}] margins(l,t,r,b)=({6},{7},{8},{9}) -> {10}' -f
            $index, $shot, $minX, $minY, $maxX, $maxY,
            ($minX - $cl), ($minY - $ct), ($cr - $maxX), ($cb - $maxY), $verdict))
}
# 相邻拍位移（漂移调度：相邻分钟都在极限位，位移 = 幅度 8px 的轴向/对角 16px）
for ($i = 1; $i -lt $boxes.Count; $i++) {
    $dx = $boxes[$i][0] - $boxes[$i - 1][0]
    $dy = $boxes[$i][1] - $boxes[$i - 1][1]
    $lines.Add(('drift {0}->{1} : bbox 位移 dx={2} dy={3}' -f $i, ($i + 1), $dx, $dy))
}
$lines | Out-File $measure -Encoding utf8
Write-Host "measure written: $measure"
