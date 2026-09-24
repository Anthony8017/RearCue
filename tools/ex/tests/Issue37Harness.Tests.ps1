# Pester 3.4 pure fixture tests. No adb or device mutation.
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Import-Module (Join-Path (Split-Path -Parent $here) 'ExCommon.psm1') -Force
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

    It 'reads the app notification permission for restoration checks' {
        (Get-I37NotificationPermission -DumpsysPackage 'android.permission.POST_NOTIFICATIONS: granted=false, flags=[USER_SENSITIVE_WHEN_DENIED]') | Should Be $false
        (Get-I37NotificationPermission -DumpsysPackage 'android.permission.POST_NOTIFICATIONS: granted=true, flags=[]') | Should Be $true
        (Get-I37NotificationPermission -DumpsysPackage 'android.permission.POST_NOTIFICATIONS') | Should Be $null
    }

    It 'reads only notification keys, never notification bodies' {
        $keys = Get-I37NotificationKeys -Lines @('0|com.android.shell|2020|rc37-abc|2000', 'secret body', '0|com.ss.android.lark|42|null|10414')
        $keys.Count | Should Be 2
        $keys[0].Tag | Should Be 'rc37-abc'
        ($keys | Out-String) -match 'secret' | Should Be $false
    }

    It 'limits shell cleanup to the one run-owned key while preserving real notifications' {
        $keys = Get-I37NotificationKeys -Lines @('0|com.android.shell|2020|rc37-ccc9b9e63d-firstcast-off|2000', '0|com.ss.android.lark|42|null|10414')
        (Get-I37ShellCleanupScope -Keys $keys -Tag 'rc37-ccc9b9e63d-firstcast-off') | Should Be 'target'
        (Get-I37ShellCleanupScope -Keys $keys -Tag 'another-run') | Should Be 'unsafe'
        (Get-I37ShellCleanupScope -Keys ($keys + [pscustomobject]@{ Package = 'com.android.shell'; Tag = 'other' }) -Tag 'rc37-ccc9b9e63d-firstcast-off') | Should Be 'unsafe'
        (Get-I37ShellCleanupScope -Keys @($keys[1]) -Tag 'rc37-ccc9b9e63d-firstcast-off') | Should Be 'absent'
    }

    It 'does not mistake a queued broadcast for receiver-side cancellation' {
        $marker = 'pc-i37-ccc9b9e63d-cancel-shell-1'
        $queued = @(
            "09-23 16:49:15.700  2000  2000 I RearCue : $marker",
            '09-23 16:49:15.759  5157 10116 I ActivityManager: Enqueued broadcast Intent { act=com.rearcue.poc.action.CANCEL_PACKAGE }: 0'
        )
        (Get-I37CancelFeedback -Lines $queued -Marker $marker) | Should Be 'receiver-not-observed'
        (Get-I37CancelFeedback -Lines ($queued + '09-23 16:49:15.900 16810 16810 I RearCue : debug cancel pkg=com.android.shell -> -1') -Marker $marker) | Should Be 'listener-unavailable'
        (Get-I37CancelFeedback -Lines ($queued + '09-23 16:51:22.391 16810 16810 I RearCue : debug cancel pkg=com.android.shell -> 1') -Marker $marker) | Should Be 'cancelled'
    }

    It 'allows one bounded retry only for a still-owned shell key and an unavailable receiver' {
        (Test-I37CancelRetryAllowed -Feedback 'receiver-not-observed' -Scope 'target' -Attempts 1) | Should Be $true
        (Test-I37CancelRetryAllowed -Feedback 'listener-unavailable' -Scope 'target' -Attempts 1) | Should Be $true
        (Test-I37CancelRetryAllowed -Feedback 'receiver-not-observed' -Scope 'target' -Attempts 2) | Should Be $false
        (Test-I37CancelRetryAllowed -Feedback 'receiver-not-observed' -Scope 'unsafe' -Attempts 1) | Should Be $false
        (Test-I37CancelRetryAllowed -Feedback 'cancelled' -Scope 'target' -Attempts 1) | Should Be $false
    }

    It 'allows one process restart only after the bounded retry also lost the receiver' {
        (Test-I37CancelRestartAllowed -Attempts 2 -Scope 'target' -Feedback 'receiver-not-observed') | Should Be $true
        (Test-I37CancelRestartAllowed -Attempts 3 -Scope 'target' -Feedback 'listener-unavailable') | Should Be $true
        (Test-I37CancelRestartAllowed -Attempts 1 -Scope 'target' -Feedback 'receiver-not-observed') | Should Be $false
        (Test-I37CancelRestartAllowed -Attempts 2 -Scope 'absent' -Feedback 'receiver-not-observed') | Should Be $false
        (Test-I37CancelRestartAllowed -Attempts 2 -Scope 'unsafe' -Feedback 'receiver-not-observed') | Should Be $false
        (Test-I37CancelRestartAllowed -Attempts 2 -Scope 'target' -Feedback 'cancelled') | Should Be $false
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
        $state = [pscustomobject]@{ Locked = $true; Owner = 'native'; Rear = 'ON'; Main = 'OFF'; MainPower = 'OFF'; Keys = @([pscustomobject]@{ Package = 'com.ss.android.lark' }) }
        (Test-I37Preflight -State $state -Allowlist @('com.ss.android.lark') -ListenerEnabled $true -AppRunning $true) -match 'preserving them' | Should Be $true
        (Test-I37Preflight -State $state -Allowlist @('com.ss.android.lark') -ListenerEnabled $true -AppRunning $true -OffOnly) -match 'preserving them' | Should Be $true
    }

    It 'allows a locked empty native-rear baseline' {
        $state = [pscustomobject]@{ Locked = $true; Owner = 'native'; Rear = 'ON'; Keys = @() }
        (Test-I37Preflight -State $state -Allowlist @('com.ss.android.lark') -ListenerEnabled $true -AppRunning $true) | Should Be $null
    }

    It 'allows the locked empty native rear in its natural DOZE_SUSPEND idle state' {
        $state = [pscustomobject]@{ Locked = $true; Owner = 'native'; Rear = 'DOZE_SUSPEND'; Main = 'OFF'; MainPower = 'OFF'; Keys = @() }
        (Test-I37Preflight -State $state -Allowlist @('com.ss.android.lark') -ListenerEnabled $true -AppRunning $true) | Should Be $null
    }

    It 'blocks a rear-OFF start because the control cannot restore that power state' {
        $state = [pscustomobject]@{ Locked = $true; Owner = 'native'; Rear = 'OFF'; Keys = @() }
        (Test-I37Preflight -State $state -Allowlist @('com.ss.android.lark') -ListenerEnabled $true -AppRunning $true) -match 'cannot reliably restore that state' | Should Be $true
    }

    It 'allows a rear-OFF locked empty start for OFF-only firstcast' {
        $state = [pscustomobject]@{ Locked = $true; Owner = 'native'; Rear = 'OFF'; Main = 'OFF'; MainPower = 'OFF'; Keys = @() }
        (Test-I37Preflight -State $state -Allowlist @('com.ss.android.lark') -ListenerEnabled $true -AppRunning $true -OffOnly) | Should Be $null
    }

    It 'requires a physically OFF main screen for OFF-only start' {
        $state = [pscustomobject]@{ Locked = $true; Owner = 'native'; Rear = 'OFF'; Main = 'unknown'; MainPower = 'ON'; Keys = @() }
        (Test-I37Preflight -State $state -Allowlist @('com.ss.android.lark') -ListenerEnabled $true -AppRunning $true -OffOnly) -match 'main screen is not physically OFF' | Should Be $true
    }
}

Describe 'Issue37 rear restoration' {
    It 'selects only the rear MainActivity root task above the native launcher for hand-back' {
        $dump = Get-Content -Raw -Encoding UTF8 (Join-Path $here 'fixtures/issue37-main-task-on-rear.txt')
        (Get-RearScreenOwner -DumpsysActivities $dump -DisplayId 1) | Should Be 'other'
        $plan = Get-I37RearMainTaskMovePlan -DumpsysActivities $dump -RearDisplayId 1
        $plan.Safe | Should Be $true
        $plan.TaskId | Should Be 13162
        $plan.TopActivity | Should Be 'com.rearcue.poc/.ui.MainActivity'
    }

    It 'discovers a changed root task id instead of assuming 13162' {
        $dump = (Get-Content -Raw -Encoding UTF8 (Join-Path $here 'fixtures/issue37-main-task-on-rear.txt')).Replace('13162', '14123')
        (Get-I37RearMainTaskMovePlan -DumpsysActivities $dump -RearDisplayId 1).TaskId | Should Be 14123
    }

    It 'does not depend on the apps current Android uid' {
        $dump = (Get-Content -Raw -Encoding UTF8 (Join-Path $here 'fixtures/issue37-main-task-on-rear.txt')).Replace('10339:', '10666:')
        (Get-I37RearMainTaskMovePlan -DumpsysActivities $dump -RearDisplayId 1).Safe | Should Be $true
    }

    It 'accepts repeated records for the same native launcher task' {
        $dump = Get-Content -Raw -Encoding UTF8 (Join-Path $here 'fixtures/issue37-main-task-on-rear.txt')
        $dump += "`n      * Hist  #0: ActivityRecord{ccc013 u0 com.xiaomi.subscreencenter/.SubScreenLauncher t12819}"
        (Get-I37RearMainTaskMovePlan -DumpsysActivities $dump -RearDisplayId 1).Safe | Should Be $true
    }

    It 'refuses hand-back if the top is foreign, native is absent, or the app task is nested' {
        $dump = Get-Content -Raw -Encoding UTF8 (Join-Path $here 'fixtures/issue37-main-task-on-rear.txt')
        (Get-I37RearMainTaskMovePlan -DumpsysActivities ($dump.Replace('com.rearcue.poc/.ui.MainActivity t13162', 'com.example.other/.MainActivity t13162')) -RearDisplayId 1).Safe | Should Be $false
        (Get-I37RearMainTaskMovePlan -DumpsysActivities ($dump.Replace('com.xiaomi.subscreencenter/.SubScreenLauncher', 'com.example.other/.Launcher')) -RearDisplayId 1).Safe | Should Be $false
        (Get-I37RearMainTaskMovePlan -DumpsysActivities ($dump.Replace('#13162 type=standard', '#13162 type=standard rootTaskId=3')) -RearDisplayId 1).Safe | Should Be $false
        (Get-I37RearMainTaskMovePlan -DumpsysActivities ($dump.Replace('A=10339:com.rearcue.poc U=0 visible=true', 'A=10339:com.example.other U=0 visible=true')) -RearDisplayId 1).Safe | Should Be $false
    }

    It 'refuses a stale MainActivity record when no explicit rear top is available' {
        $dump = Get-Content -Raw -Encoding UTF8 (Join-Path $here 'fixtures/issue37-main-task-on-rear.txt')
        (Get-I37RearMainTaskMovePlan -DumpsysActivities ($dump.Replace('topResumedActivity=ActivityRecord{bbb012', 'mLastPausedActivity: ActivityRecord{bbb012')) -RearDisplayId 1).Safe | Should Be $false
    }

    It 'keeps a product failure invalid even after a safe harness hand-back' {
        (Get-I37OwnerRecoveryVerdict -PreviousResult 'RED' -MovedOwnMainTask $true).Result | Should Be 'INVALID'
        (Get-I37OwnerRecoveryVerdict -PreviousResult 'GREEN' -MovedOwnMainTask $true).Result | Should Be 'INVALID'
        (Get-I37OwnerRecoveryVerdict -PreviousResult 'RED' -MovedOwnMainTask $false).Result | Should Be 'RED'
    }

    It 'accepts a native idle rear that naturally advances from DOZE_SUSPEND to OFF' {
        (Test-I37RearRestored -InitialRear 'DOZE_SUSPEND' -CurrentRear 'OFF') | Should Be $true
    }

    It 'never calls a lit rear restored to an idle start' {
        (Test-I37RearRestored -InitialRear 'DOZE_SUSPEND' -CurrentRear 'ON') | Should Be $false
        (Test-I37RearRestored -InitialRear 'DOZE_SUSPEND' -CurrentRear 'DOZE') | Should Be $false
    }

    It 'keeps an ON start and an OFF start exact' {
        (Test-I37RearRestored -InitialRear 'ON' -CurrentRear 'ON') | Should Be $true
        (Test-I37RearRestored -InitialRear 'ON' -CurrentRear 'DOZE_SUSPEND') | Should Be $false
        (Test-I37RearRestored -InitialRear 'OFF' -CurrentRear 'DOZE_SUSPEND') | Should Be $false
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

    It 'accepts a render logged a hair before the posted summary of the same transition' {
        # Real device run 20260924-190143: project() runs to completion inside the post()
        # pipeline, so the `posted ...` summary line lands ~1ms AFTER `project iconSet=...`.
        # Wall-clock order between the two lines is racy; window + icon-set equality already
        # tie the render to this leg, so requiring render >= callback mislabels a real GREEN.
        $events = Get-I37Events -Lines @(
            '09-23 15:00:00.779 14638 14638 I RearCue : project iconSet=[com.android.shell] -> sent displayId=1',
            '09-23 15:00:00.780 14638 14638 I RearCue : posted com.android.shell -> LaunchDashboard(1) iconSet [] -> [com.android.shell] tracked=1')
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
