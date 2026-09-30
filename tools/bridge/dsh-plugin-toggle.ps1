# RearCue DSH plugin toggle (ASCII-only source: avoids encoding corruption).
# Disable/enable the RearCue read-only DSH plugin in a dsh profile manifest.
# Usage:
#   powershell -ExecutionPolicy Bypass -File dsh-plugin-toggle.ps1 -Action disable
#   powershell -ExecutionPolicy Bypass -File dsh-plugin-toggle.ps1 -Action enable
#   powershell -ExecutionPolicy Bypass -File dsh-plugin-toggle.ps1 -Action status
# The plugin package name is dsh-bridge-readonly; its loader entry id is rearcue-dsh-bridge.
param(
    [ValidateSet('disable', 'enable', 'status')]
    [string]$Action = 'status',
    [string]$Profile = 'desktop'
)

$ErrorActionPreference = 'Stop'
$pkgName = 'dsh-bridge-readonly'
$linkSpec = 'link:' + ((Resolve-Path (Join-Path $PSScriptRoot 'adapters\dsh')).Path -replace '\\', '/')
$manifest = Join-Path $env:USERPROFILE ".dsh\profiles\$Profile\package.json"

if (-not (Test-Path $manifest)) {
    Write-Host "[RearCue] manifest not found: $manifest" -ForegroundColor Red
    exit 2
}

function Read-Manifest {
    # -Encoding UTF8 on Windows PowerShell 5.1 writes a BOM; use .NET without BOM for writes.
    return (Get-Content $manifest -Raw) | ConvertFrom-Json
}

function Save-Manifest($obj) {
    Copy-Item $manifest "$manifest.rearcue-bak" -Force
    $json = $obj | ConvertTo-Json -Depth 20
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($manifest, $json, $utf8NoBom)
}

$doc = Read-Manifest
$inDeps = $null -ne $doc.dependencies.PSObject.Properties[$pkgName]
$inBundles = @($doc.dsh.profile.bundles) -contains $pkgName

switch ($Action) {
    'status' {
        Write-Host "[RearCue] profile      : $Profile"
        Write-Host "[RearCue] manifest     : $manifest"
        Write-Host "[RearCue] dependency   : $inDeps"
        Write-Host "[RearCue] bundle entry : $inBundles"
        if ($inBundles) { Write-Host '[RearCue] state        : ENABLED (restart dsh to take effect)' }
        else { Write-Host '[RearCue] state        : DISABLED' }
    }
    'disable' {
        if ($inDeps) { $doc.dependencies.PSObject.Properties.Remove($pkgName) }
        if ($inBundles) {
            $kept = @($doc.dsh.profile.bundles) | Where-Object { $_ -ne $pkgName }
            $doc.dsh.profile.bundles = $kept
        }
        Save-Manifest $doc
        Write-Host "[RearCue] plugin DISABLED in profile '$Profile' (backup: $manifest.rearcue-bak)" -ForegroundColor Yellow
        Write-Host '[RearCue] restart dsh: it will boot without the RearCue plugin.'
    }
    'enable' {
        if (-not $inDeps) {
            $doc.dependencies | Add-Member -NotePropertyName $pkgName -NotePropertyValue $linkSpec -Force
        }
        if (-not $inBundles) {
            $doc.dsh.profile.bundles = @($doc.dsh.profile.bundles) + $pkgName
        }
        Save-Manifest $doc
        Write-Host "[RearCue] plugin ENABLED in profile '$Profile' (backup: $manifest.rearcue-bak)" -ForegroundColor Green
        Write-Host '[RearCue] restart dsh to load it.'
    }
}
