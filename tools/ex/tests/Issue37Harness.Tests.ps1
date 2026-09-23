# Pester 3.4 pure fixture tests. No adb or device mutation.
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Import-Module (Join-Path (Split-Path -Parent $here) 'Issue37Harness.psm1') -Force

function New-I37Sample([string] $Time, [string] $Main, [string] $Owner, [string] $MainPower = $Main) {
    [pscustomobject]@{ Time = $Time; Main = $Main; MainPower = $MainPower; Owner = $Owner; Locked = $true }
}

Describe 'Issue37 notification and event parsers' {
    It 'reads physical screen power for display 0, not rear display 1' {
        $dump = Get-Content -Raw -Encoding UTF8 (Join-Path $here 'fixtures/issue37-display-power.txt')
        Get-I37ScreenPowerState -DumpsysDisplay $dump -DisplayId 0 | Should Be 'OFF'
        Get-I37ScreenPowerState -DumpsysDisplay $dump -DisplayId 1 | Should Be 'ON'
    }

    It 'accepts DOZE_SUSPEND only when physical main screen power is OFF' {
        Get-I37MainCondition -DisplayState 'DOZE_SUSPEND' -ScreenPower 'OFF' | Should Be 'OFF'
        Get-I37MainCondition -DisplayState 'DOZE' -ScreenPower 'ON' | Should Be 'unknown'
        Get-I37MainCondition -DisplayState 'OFF' -ScreenPower 'ON' | Should Be 'unknown'
    }

    It 'reads the current Android package appId, not the permission UID field' {
        $dump = Get-Content -Raw -Encoding UTF8 (Join-Path $here 'fixtures/issue37-dumpsys-package-appid.txt')
        Get-I37AppUid -DumpsysPackage $dump | Should Be '10339'
    }

    It 'prefers appId and accepts legacy userId when appId is absent' {
        Get-I37AppUid -DumpsysPackage "userId=99999`nappId=10339" | Should Be '10339'
        Get-I37AppUid -DumpsysPackage 'userId=10336' | Should Be '10336'
    }

    It 'does not infer the app UID from an unrelated uid field' {
        Get-I37AppUid -DumpsysPackage 'uid=10339 gids=[] type=0' | Should Be $null
    }

    It 'reads only notification keys, never notification bodies' {
        $keys = Get-I37NotificationKeys -Lines @('0|com.android.shell|2020|rc37-abc|2000', 'secret body', '0|com.ss.android.lark|42|null|10414')
        $keys.Count | Should Be 2
        $keys[0].Tag | Should Be 'rc37-abc'
        ($keys | Out-String) -match 'secret' | Should Be $false
    }

    It 'pairs a unique marker with app callback and icon set, filtering foreign UIDs' {
        $lines = Get-Content -Encoding UTF8 (Join-Path $here 'fixtures/issue37-green-logcat.txt')
        $events = Get-I37Events -Lines ($lines + @('09-23 15:00:01.000  5157  8991 D GreezeManager: FZ uid = 99999 reason =screen off success !')) -AppUid '10339'
        $events.Count | Should Be 3
        $events[1].Kind | Should Be 'callback'
        ($events[1].IconSet -join ',') | Should Be 'com.rearcue.poc,com.android.shell'
    }
}

Describe 'Issue37 safety preflight' {
    It 'blocks an unlocked secure-lock starting point without changing it' {
        $state = [pscustomobject]@{ Locked = $false; Owner = 'native'; Rear = 'ON'; Keys = @() }
        (Test-I37Preflight -State $state -Allowlist @('com.ss.android.lark') -ListenerEnabled $true -AppRunning $true) -match 'starts unlocked' | Should Be $true
    }

    It 'blocks real allowlist notifications rather than deleting them' {
        $state = [pscustomobject]@{ Locked = $true; Owner = 'native'; Rear = 'ON'; Keys = @([pscustomobject]@{ Package = 'com.ss.android.lark' }) }
        (Test-I37Preflight -State $state -Allowlist @('com.ss.android.lark') -ListenerEnabled $true -AppRunning $true) -match 'preserving them' | Should Be $true
    }

    It 'allows a locked empty native-rear baseline' {
        $state = [pscustomobject]@{ Locked = $true; Owner = 'native'; Rear = 'ON'; Keys = @() }
        (Test-I37Preflight -State $state -Allowlist @('com.ss.android.lark') -ListenerEnabled $true -AppRunning $true) | Should Be $null
    }

    It 'blocks a rear-OFF start because the control cannot restore that power state' {
        $state = [pscustomobject]@{ Locked = $true; Owner = 'native'; Rear = 'OFF'; Keys = @() }
        (Test-I37Preflight -State $state -Allowlist @('com.ss.android.lark') -ListenerEnabled $true -AppRunning $true) -match 'cannot reliably restore OFF' | Should Be $true
    }
}

Describe 'Issue37 bounded verdict' {
    It 'is red when the OS accepted but Greeze held the callback until wake' {
        $events = Get-I37Events -Lines (Get-Content -Encoding UTF8 (Join-Path $here 'fixtures/issue37-red-logcat.txt')) -AppUid '10339'
        $samples = @((New-I37Sample '09-23 14:03:21.000' 'OFF' 'native'), (New-I37Sample '09-23 14:03:26.000' 'OFF' 'native'))
        $v = Get-I37Verdict -StartTime '09-23 14:03:20.934' -InSystem $true -Events $events -Samples $samples -ExpectedIcons @('com.android.shell') -PostedPackage 'com.android.shell' -RequireOff $true
        $v.Word | Should Be 'RED-NO-CALLBACK'
    }

    It 'is green only for a timely icon change and dashboard owner' {
        $events = Get-I37Events -Lines (Get-Content -Encoding UTF8 (Join-Path $here 'fixtures/issue37-green-logcat.txt')) -AppUid '10339'
        $samples = @((New-I37Sample '09-23 15:00:01.000' 'OFF' 'dashboard'), (New-I37Sample '09-23 15:00:06.000' 'OFF' 'dashboard'))
        $v = Get-I37Verdict -StartTime '09-23 15:00:00.000' -InSystem $true -Events $events -Samples $samples -ExpectedIcons @('com.rearcue.poc', 'com.android.shell') -PostedPackage 'com.android.shell' -RequireOff $true
        $v.Word | Should Be 'GREEN'
        $v.IconMs | Should Be 780
    }

    It 'does not count a stale Dashboard as an icon update' {
        $events = Get-I37Events -Lines (Get-Content -Encoding UTF8 (Join-Path $here 'fixtures/issue37-red-logcat.txt')) -AppUid '10339'
        $samples = @((New-I37Sample '09-23 14:03:21.000' 'OFF' 'dashboard'), (New-I37Sample '09-23 14:03:26.000' 'OFF' 'dashboard'))
        $v = Get-I37Verdict -StartTime '09-23 14:03:20.934' -InSystem $true -Events $events -Samples $samples -ExpectedIcons @('com.rearcue.poc', 'com.android.shell') -PostedPackage 'com.android.shell' -RequireOff $true
        $v.Word | Should Be 'RED-NO-CALLBACK'
    }

    It 'rejects an off leg if the main display wakes' {
        $events = Get-I37Events -Lines @('09-23 15:00:01.000  5157  8991 I PowerGroup: Waking up power group from Dozing (groupId=0, uid=1000, reason=WAKE_REASON_POWER_BUTTON, details=test)')
        $samples = @((New-I37Sample '09-23 15:00:02.000' 'OFF' 'dashboard'), (New-I37Sample '09-23 15:00:06.000' 'OFF' 'dashboard'))
        $v = Get-I37Verdict -StartTime '09-23 15:00:00.000' -InSystem $true -Events $events -Samples $samples -ExpectedIcons @('com.android.shell') -PostedPackage 'com.android.shell' -RequireOff $true
        $v.Word | Should Be 'INVALID'
    }

    It 'accepts the locked main-on control with the same chain' {
        $events = Get-I37Events -Lines @('09-23 15:00:00.780 14638 14638 I RearCue : posted com.android.shell -> LaunchDashboard(1) iconSet [] -> [com.android.shell] tracked=1', '09-23 15:00:00.790 14638 14638 I RearCue : project iconSet=[com.android.shell] -> sent displayId=1')
        $samples = @((New-I37Sample '09-23 15:00:02.000' 'ON' 'dashboard'), (New-I37Sample '09-23 15:00:06.000' 'ON' 'dashboard'))
        $v = Get-I37Verdict -StartTime '09-23 15:00:00.000' -InSystem $true -Events $events -Samples $samples -ExpectedIcons @('com.android.shell') -PostedPackage 'com.android.shell' -RequireOff $false
        $v.Word | Should Be 'GREEN'
    }

    It 'uses the observed system key even when the command reply text is unparseable' {
        $samples = @((New-I37Sample '09-23 15:00:02.000' 'OFF' 'dashboard'), (New-I37Sample '09-23 15:00:06.000' 'OFF' 'dashboard'))
        $v = Get-I37Verdict -StartTime '09-23 15:00:00.000' -InSystem $true -Samples $samples -ExpectedIcons @('com.android.shell') -PostedPackage 'com.android.shell' -RequireOff $true
        $v.Word | Should Be 'RED-NO-CALLBACK'
    }

    It 'does not claim an app failure without a system key' {
        $samples = @((New-I37Sample '09-23 15:00:02.000' 'OFF' 'native'), (New-I37Sample '09-23 15:00:06.000' 'OFF' 'native'))
        $v = Get-I37Verdict -StartTime '09-23 15:00:00.000' -InSystem $false -Samples $samples -ExpectedIcons @('com.android.shell') -PostedPackage 'com.android.shell' -RequireOff $true
        $v.Word | Should Be 'INVALID'
    }

    It 'does not blame the app when the system key appears only after the budget' {
        $samples = @((New-I37Sample '09-23 15:00:02.000' 'OFF' 'native'), (New-I37Sample '09-23 15:00:06.000' 'OFF' 'native'))
        $v = Get-I37Verdict -StartTime '09-23 15:00:00.000' -InSystem $true -ReceiptTime '09-23 15:00:06.000' -Samples $samples -ExpectedIcons @('com.android.shell') -PostedPackage 'com.android.shell' -RequireOff $true
        $v.Word | Should Be 'INVALID'
    }

    It 'rejects a physically lit main panel even if DisplayInfo says OFF' {
        $samples = @((New-I37Sample '09-23 15:00:02.000' 'OFF' 'dashboard' 'ON'), (New-I37Sample '09-23 15:00:06.000' 'OFF' 'dashboard' 'ON'))
        $v = Get-I37Verdict -StartTime '09-23 15:00:00.000' -InSystem $true -Samples $samples -ExpectedIcons @('com.android.shell') -PostedPackage 'com.android.shell' -RequireOff $true
        $v.Word | Should Be 'INVALID'
    }

    It 'rejects a stale dashboard sample before rear rendering' {
        $events = Get-I37Events -Lines (Get-Content -Encoding UTF8 (Join-Path $here 'fixtures/issue37-green-logcat.txt')) -AppUid '10339'
        $samples = @((New-I37Sample '09-23 15:00:00.500' 'OFF' 'dashboard'), (New-I37Sample '09-23 15:00:06.000' 'OFF' 'native'))
        $v = Get-I37Verdict -StartTime '09-23 15:00:00.000' -InSystem $true -Events $events -Samples $samples -ExpectedIcons @('com.rearcue.poc', 'com.android.shell') -PostedPackage 'com.android.shell' -RequireOff $true
        $v.Word | Should Be 'RED-OWNER'
    }

    It 'rejects an icon transition without a rear render event' {
        $events = Get-I37Events -Lines @('09-23 15:00:00.780 14638 14638 I RearCue : posted com.android.shell -> UpdateIconSet(2) iconSet [com.rearcue.poc] -> [com.rearcue.poc, com.android.shell] tracked=2')
        $samples = @((New-I37Sample '09-23 15:00:01.000' 'OFF' 'dashboard'), (New-I37Sample '09-23 15:00:06.000' 'OFF' 'dashboard'))
        $v = Get-I37Verdict -StartTime '09-23 15:00:00.000' -InSystem $true -Events $events -Samples $samples -ExpectedIcons @('com.rearcue.poc', 'com.android.shell') -PostedPackage 'com.android.shell' -RequireOff $true
        $v.Word | Should Be 'RED-RENDER'
    }

    It 'rejects a dashboard that disappears before the window ends' {
        $events = Get-I37Events -Lines (Get-Content -Encoding UTF8 (Join-Path $here 'fixtures/issue37-green-logcat.txt')) -AppUid '10339'
        $samples = @((New-I37Sample '09-23 15:00:01.000' 'OFF' 'dashboard'), (New-I37Sample '09-23 15:00:06.000' 'OFF' 'native'))
        $v = Get-I37Verdict -StartTime '09-23 15:00:00.000' -InSystem $true -Events $events -Samples $samples -ExpectedIcons @('com.rearcue.poc', 'com.android.shell') -PostedPackage 'com.android.shell' -RequireOff $true
        $v.Word | Should Be 'RED-OWNER'
    }
}
