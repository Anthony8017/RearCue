# Pester tests for tools/ex/ExCommon.psm1 (the pure parsing seam of the PC experiment script set).
#
#   powershell -NoProfile -Command "Invoke-Pester -Path tools\ex\tests -Show Failed,Summary"
#   or: .\tools\ex\ex.ps1 -Task selftest
#
# The scripts run under Windows PowerShell 5.1 (no pwsh on this machine), so the tests keep to
# Pester 3.4 syntax. Parsers are pure: dumpsys / logcat text in, structured facts out - no device.

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Import-Module (Join-Path (Split-Path -Parent $here) 'ExCommon.psm1') -Force

$script:Fixtures = Join-Path $here 'fixtures'

function Get-ExFixture([string]$Name) {
    Get-Content -Raw -Encoding UTF8 (Join-Path $script:Fixtures $Name)
}

Describe 'Get-DisplayInfoBlocks' {
    $blocks = Get-DisplayInfoBlocks -DumpsysDisplay (Get-ExFixture 'dumpsys-display-17pro.txt')

    It 'reads one block per display, ignoring mOverrideDisplayInfo' {
        $blocks.Count | Should Be 2
        ($blocks | ForEach-Object { $_.DisplayId } | Sort-Object) -join ',' | Should Be '0,1'
    }

    It 'reads size, density, state and type of the rear display' {
        $rear = $blocks | Where-Object { $_.DisplayId -eq 1 }
        $rear.Width | Should Be 904
        $rear.Height | Should Be 572
        $rear.Density | Should Be 450
        $rear.Type | Should Be 'INTERNAL'
        $rear.State | Should Be 'OFF'
        $rear.CommittedState | Should Be 'DOZE_SUSPEND'
    }

    It 'keeps the flag list so the rear display can be told apart from the main one' {
        $rear = $blocks | Where-Object { $_.DisplayId -eq 1 }
        $main = $blocks | Where-Object { $_.DisplayId -eq 0 }
        ($rear.Flags -contains 'FLAG_PRESENTATION') | Should Be $true
        ($rear.Flags -contains 'FLAG_OWN_DISPLAY_GROUP') | Should Be $true
        ($main.Flags -contains 'FLAG_PRESENTATION') | Should Be $false
        $rear.IsDefault | Should Be $false
        $main.IsDefault | Should Be $true
    }
}

Describe 'Get-RearDisplay' {
    It 'picks the non-default INTERNAL display carrying PRESENTATION + OWN_DISPLAY_GROUP' {
        $rear = Get-RearDisplay -DumpsysDisplay (Get-ExFixture 'dumpsys-display-17pro.txt')
        $rear.DisplayId | Should Be 1
        $rear.UniqueId | Should Be 'local:4630946949513469332'
    }

    It 'returns nothing when the dump only has the main display' {
        $single = 'mBaseDisplayInfo=DisplayInfo{"Built-in screen", displayId 0, displayGroupId 0, FLAG_SECURE, real 1220 x 2656, state ON, committedState ON, type INTERNAL, uniqueId "local:1", density 520}'
        $rear = Get-RearDisplay -DumpsysDisplay $single
        ($null -eq $rear) | Should Be $true
    }
}

Describe 'Get-DisplayTopActivity' {
    It 'reads topResumedActivity of the requested display' {
        $dumpsys = Get-ExFixture 'dumpsys-activities-dashboard.txt'
        Get-DisplayTopActivity -DumpsysActivities $dumpsys -DisplayId 1 |
            Should Be 'com.rearcue.poc/.rear.RearDashboardActivity'
    }

    It 'does not leak activities from another display' {
        $dumpsys = Get-ExFixture 'dumpsys-activities-dashboard.txt'
        Get-DisplayTopActivity -DumpsysActivities $dumpsys -DisplayId 0 |
            Should Be 'moe.shizuku.privileged.api/moe.shizuku.manager.MainActivity'
    }

    It 'falls back to the first ActivityRecord when topResumedActivity is absent' {
        $dumpsys = Get-ExFixture 'dumpsys-activities-native.txt'
        Get-DisplayTopActivity -DumpsysActivities $dumpsys -DisplayId 1 |
            Should Be 'com.xiaomi.subscreencenter/.SubScreenLauncher'
    }

    It 'returns nothing for an empty display or an unknown display id' {
        ($null -eq (Get-DisplayTopActivity -DumpsysActivities (Get-ExFixture 'dumpsys-activities-empty.txt') -DisplayId 1)) | Should Be $true
        ($null -eq (Get-DisplayTopActivity -DumpsysActivities (Get-ExFixture 'dumpsys-activities-dashboard.txt') -DisplayId 9)) | Should Be $true
    }
}

Describe 'Get-RearScreenOwner' {
    It 'reports dashboard while RearDashboardActivity holds the rear display' {
        Get-RearScreenOwner -DumpsysActivities (Get-ExFixture 'dumpsys-activities-dashboard.txt') -DisplayId 1 |
            Should Be 'dashboard'
    }

    It 'reports native when the Native Rear Screen is back' {
        Get-RearScreenOwner -DumpsysActivities (Get-ExFixture 'dumpsys-activities-native.txt') -DisplayId 1 |
            Should Be 'native'
    }

    It 'reports none when the rear display hosts nothing' {
        Get-RearScreenOwner -DumpsysActivities (Get-ExFixture 'dumpsys-activities-empty.txt') -DisplayId 1 |
            Should Be 'none'
    }
}

Describe 'Get-RearCueEvent' {
    $events = Get-RearCueEvent -Logcat (Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'logcat-rearcue-chain.txt'))

    It 'parses every RearCue line of the ticket #5 chain and drops comments' {
        $events.Count | Should Be 17
        @($events | Where-Object { $_.Kind -eq 'other' }).Count | Should Be 3
    }

    It 'classifies the event kinds of the auto up/down chain' {
        @($events | Where-Object { $_.Kind -eq 'posted' }).Count | Should Be 2
        @($events | Where-Object { $_.Kind -eq 'removed' }).Count | Should Be 2
        @($events | Where-Object { $_.Kind -eq 'project' }).Count | Should Be 1
        @($events | Where-Object { $_.Kind -eq 'update' }).Count | Should Be 1
        @($events | Where-Object { $_.Kind -eq 'exit' }).Count | Should Be 1
        @($events | Where-Object { $_.Kind -eq 'attach' }).Count | Should Be 1
        @($events | Where-Object { $_.Kind -eq 'detach' }).Count | Should Be 1
        @($events | Where-Object { $_.Kind -eq 'listener-connected' }).Count | Should Be 1
        @($events | Where-Object { $_.Kind -eq 'snapshot' }).Count | Should Be 1
        @($events | Where-Object { $_.Kind -eq 'signal' }).Count | Should Be 2
        @($events | Where-Object { $_.Kind -eq 'diagnostic' }).Count | Should Be 1
    }

    It 'reads the effect labels and the resulting Icon Set' {
        $launch = $events | Where-Object { $_.Kind -eq 'posted' } | Select-Object -First 1
        $launch.Effects -join ',' | Should Be 'LaunchDashboard'
        ($launch.IconSet -join ',') | Should Be 'com.android.shell'
        $launch.Package | Should Be 'com.android.shell'

        $last = $events | Where-Object { $_.Kind -eq 'removed' } | Select-Object -Last 1
        $last.Effects -join ',' | Should Be 'ExitDashboard'
        $last.IconSet.Count | Should Be 0

        $exit = $events | Where-Object { $_.Kind -eq 'exit' }
        $exit.Message | Should Match 'Dashboard=1'
    }

    It 'reads the projection target display' {
        $project = $events | Where-Object { $_.Kind -eq 'project' }
        $project.DisplayId | Should Be 1
    }

    It 'reads the Shizuku diagnostic fields' {
        $diag = $events | Where-Object { $_.Kind -eq 'diagnostic' }
        $diag.Shizuku.Server | Should Be 'false'
        $diag.Shizuku.Granted | Should Be 'false'
        $diag.Shizuku.UserService | Should Be 'false'
        $diag.Rear | Should Be 1
    }

    It 'reports the signal action of the takeover broadcasts' {
        $actions = ($events | Where-Object { $_.Kind -eq 'signal' } | ForEach-Object { $_.Action }) -join ','
        $actions | Should Be 'miui.intent.action.SUB_SCREEN_ON,android.intent.action.SCREEN_ON'
    }

    It 'ignores lines from other processes and other tags' {
        $other = Get-RearCueEvent -Logcat (Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'logcat-rearcue-shizuku.txt'))
        @($other | Where-Object { $_.Kind -eq 'diagnostic' }).Count | Should Be 4
        @($other | Where-Object { $_.Kind -eq 'posted' }).Count | Should Be 1
        @($other | Where-Object { $_.Kind -eq 'removed' }).Count | Should Be 1
        @($other).Count | Should Be 6
        ($other.Raw -join "`n").Contains('SecurityException') | Should Be $false
    }

    It 'tracks the Shizuku server going down and coming back' {
        $other = Get-RearCueEvent -Logcat (Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'logcat-rearcue-shizuku.txt'))
        $servers = ($other | Where-Object { $_.Kind -eq 'diagnostic' } | ForEach-Object { $_.Shizuku.Server }) -join ','
        $servers | Should Be 'true,false,true,true'
    }
}

Describe 'Test-RearCueCrash' {
    It 'finds nothing in a healthy run' {
        (Test-RearCueCrash -Logcat (Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'logcat-rearcue-chain.txt'))).Count | Should Be 0
    }

    It 'finds FATAL EXCEPTION and ANR lines' {
        $lines = @(
            '09-22 08:52:00.000 15873 15873 E AndroidRuntime: FATAL EXCEPTION: main',
            '09-22 08:52:01.000 1000 1000 E ActivityManager: ANR in com.rearcue.poc (pid 15873)'
        )
        (Test-RearCueCrash -Logcat $lines).Count | Should Be 2
    }
}

Describe 'Get-ExNodeCenter' {
    # The label is decoded from \uXXXX escapes (same trick as the module): this test file must stay
    # ASCII, because Windows PowerShell 5.1 reads BOM-less .ps1 sources as ANSI/GBK.
    $dump = Get-ExFixture 'ui-usb-install-dialog.xml'
    $confirmLabel = [regex]::Unescape('\u7EE7\u7EED\u5B89\u88C5')

    It 'finds the confirm button of the MIUI USB install dialog by its label' {
        $node = Get-ExNodeCenter -WindowDump $dump -Text $confirmLabel
        $node.Text | Should Be $confirmLabel
        $node.X | Should Be 351
        $node.Y | Should Be 2414
    }

    It 'finds the same button by resource id' {
        $node = Get-ExNodeCenter -WindowDump $dump -ResourceId 'android:id/button2'
        $node.X | Should Be 351
    }

    It 'returns nothing when the label is not on screen' {
        ($null -eq (Get-ExNodeCenter -WindowDump $dump -Text 'no-such-button')) | Should Be $true
        ($null -eq (Get-ExNodeCenter -WindowDump '' -Text $confirmLabel)) | Should Be $true
    }

    It 'needs a selector' {
        { Get-ExNodeCenter -WindowDump $dump } | Should Throw
    }
}

Describe 'Test-ExKeyguardLockedText' {
    It 'sees the keyguard in a real locked-screen dump' {
        Test-ExKeyguardLockedText -DumpsysWindow (Get-ExFixture 'dumpsys-window-keyguard.txt') | Should Be $true
    }

    It 'reports unlocked when the keyguard is gone' {
        $unlocked = (Get-ExFixture 'dumpsys-window-keyguard.txt') -replace 'mDreamingLockscreen=true', 'mDreamingLockscreen=false' `
            -replace 'isKeyguardShowing=true', 'isKeyguardShowing=false'
        Test-ExKeyguardLockedText -DumpsysWindow $unlocked | Should Be $false
        Test-ExKeyguardLockedText -DumpsysWindow '' | Should Be $false
    }
}

Describe 'Get-OverlayProbeWindow' {
    # E9 (ticket #10): the decisive device fact is "is the overlay window really on the rear
    # display", read from `dumpsys window windows` -- never from a broadcast exit code.
    # Fixture: verbatim `dumpsys window windows` slice of the 2026-09-22 control run (session
    # 20260922-122827-overlay): the probe window really added to display 0, next to a StatusBar
    # block and a WeChat FloatingWindow (another app's APPLICATION_OVERLAY -- the decoy).
    $dump = Get-ExFixture 'dumpsys-window-overlay-probe.txt'

    It 'finds the probe window and reads its display, package and appop' {
        $probe = Get-OverlayProbeWindow -DumpsysWindow $dump
        $probe.Found | Should Be $true
        $probe.DisplayId | Should Be 0
        $probe.Package | Should Be 'com.rearcue.poc'
        $probe.Appop | Should Be 'SYSTEM_ALERT_WINDOW'
    }

    It 'does not mistake another app overlay for the probe' {
        $dumpNoProbe = $dump -replace 'Window #10 Window\{17a3eb4 u0 RearCueOverlayProbe\}', 'Window #10 Window{17a3eb4 u0 OtherOverlay}'
        $probe = Get-OverlayProbeWindow -DumpsysWindow $dumpNoProbe
        $probe.Found | Should Be $false
        $probe.DisplayId | Should Be $null
    }

    It 'ignores the title echoing inside the mToken line of the block body' {
        # The real mToken line echoes `-topChild=Window{17a3eb4 u0 RearCueOverlayProbe}`; renaming
        # only the block header must make the window disappear from the parser`s view.
        $echo = $dump -replace 'Window #10 Window\{17a3eb4 u0 RearCueOverlayProbe\}', 'Window #10 Window{17a3eb4 u0 Probe}'
        $probe = Get-OverlayProbeWindow -DumpsysWindow $echo
        $probe.Found | Should Be $false
    }

    It 'reports display 1 when the probe really lands on the rear display' {
        $landedRear = $dump -replace '(RearCueOverlayProbe\}:\r?\n\s+mDisplayId=)0', '${1}1'
        $probe = Get-OverlayProbeWindow -DumpsysWindow $landedRear
        $probe.Found | Should Be $true
        $probe.DisplayId | Should Be 1
    }

    It 'returns not-found for an empty dump' {
        $probe = Get-OverlayProbeWindow -DumpsysWindow ''
        $probe.Found | Should Be $false
        $probe.DisplayId | Should Be $null
    }
}

Describe 'Get-OverlayWindowEvents' {
    # Fixtures are the verbatim logcat of the 2026-09-22 E9 pair: the rear-display attempt
    # (session 20260922-122608-overlay, denied by the HyperOS rear policy) and the display-0
    # control (session 20260922-122827-overlay, added and removed cleanly).
    $denied = Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'logcat-overlay-e9-rear-denied.txt')
    $ok = Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'logcat-overlay-e9-main-ok.txt')

    It 'sees the accepted add and the completed remove of the control run' {
        $events = Get-OverlayWindowEvents -Logcat $ok
        $events.Added | Should Be $true
        $events.Removed | Should Be $true
        $events.AddFailed | Should Be $false
        $events.FailureReason | Should Be $null
        $events.RearPolicyDeny | Should Be $false
    }

    It 'classifies the rear-display denial as rear policy, not as add evidence' {
        $events = Get-OverlayWindowEvents -Logcat $denied
        $events.Added | Should Be $false
        $events.AddFailed | Should Be $true
        $events.FailureReason | Should Match 'BadTokenException'
        $events.RearPolicyDeny | Should Be $true
        $events.SystemDeny.Count | Should Be 1
        $events.SystemDeny[0] | Should Match 'Not allow non-system app'
        # The policy line contains "add system_window on rear display" and must NOT count as a hit.
        $events.SystemAdd.Count | Should Be 0
    }
}

Describe 'Get-ExDelaySeconds' {
    # E10/E11 (ticket #11): how long after locking does the system reclaim the rear Dashboard?
    # The lock moment is marked straight into the RearCue tag on the device
    # (`adb shell log -t RearCue pc-lock-issued`), so the marker and the app's own
    # `Dashboard detach` line share one clock -- no PC/device clock skew in the measurement.

    It 'measures the marker -> the first matching line after it' {
        $lines = @(
            '09-22 13:00:00.000  1000  1000 I RearCue : Dashboard attach',
            '09-22 13:00:01.500  1000  1000 I RearCue : pc-lock-issued',
            '09-22 13:00:02.000  1000  1000 I RearCue : project iconSet=[com.android.shell] -> sent',
            '09-22 13:00:05.300  1000  1000 I RearCue : Dashboard detach 0',
            '09-22 13:00:06.000  1000  1000 I RearCue : Dashboard detach 1'
        )
        Get-ExDelaySeconds -Logcat $lines -Marker 'pc-lock-issued' -Pattern 'Dashboard detach' | Should Be 3.8
    }

    It 'ignores matching lines from before the marker' {
        $lines = @(
            '09-22 12:59:00.000  1000  1000 I RearCue : Dashboard detach 0',
            '09-22 13:00:01.500  1000  1000 I RearCue : pc-lock-issued',
            '09-22 13:00:04.000  1000  1000 I RearCue : Dashboard detach 0'
        )
        Get-ExDelaySeconds -Logcat $lines -Marker 'pc-lock-issued' -Pattern 'Dashboard detach' | Should Be 2.5
    }

    It 'returns null when the marker or the follow-up line is missing' {
        $noMarker = @(
            '09-22 13:00:00.000  1000  1000 I RearCue : Dashboard detach 0'
        )
        ($null -eq (Get-ExDelaySeconds -Logcat $noMarker -Marker 'pc-lock-issued' -Pattern 'Dashboard detach')) | Should Be $true
        $noDetach = @(
            '09-22 13:00:01.500  1000  1000 I RearCue : pc-lock-issued',
            '09-22 13:00:02.000  1000  1000 I RearCue : project iconSet=[com.android.shell]'
        )
        ($null -eq (Get-ExDelaySeconds -Logcat $noDetach -Marker 'pc-lock-issued' -Pattern 'Dashboard detach')) | Should Be $true
        ($null -eq (Get-ExDelaySeconds -Logcat @() -Marker 'pc-lock-issued' -Pattern 'Dashboard detach')) | Should Be $true
    }

    It 'rolls over midnight' {
        $lines = @(
            '09-22 23:59:59.000  1000  1000 I RearCue : pc-lock-issued',
            '09-23 00:00:01.500  1000  1000 I RearCue : Dashboard detach 0'
        )
        Get-ExDelaySeconds -Logcat $lines -Marker 'pc-lock-issued' -Pattern 'Dashboard detach' | Should Be 2.5
    }

    It 'measures the real ticket #11 run: lock marker -> Dashboard detach = 1.4s' {
        $lines = Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'logcat-e10-lock.txt')
        Get-ExDelaySeconds -Logcat $lines -Marker 'pc-lock-issued' -Pattern 'Dashboard detach' | Should Be 1.4
    }
}

Describe 'Get-ExLockSampleFacts' {
    # The lock-watch sampler of 07-lock-compare.ps1 archives one line per point:
    #   [+   4s] window=present(1) owner=dashboard rear=ON/ON last=<latest RearCue log line>
    # This parser reads those lines back into the survival facts the E10/E11/Activity
    # verdicts assert on (pure; fixtures come from real runs).

    It 'reads a run where the window survived and the rear display stayed on' {
        $lines = @(
            '[+   1s] window=present(1) owner=dashboard rear=ON/ON           last=Dashboard attach',
            '[+   4s] window=present(1) owner=dashboard rear=ON/ON           last=update iconSet=[com.android.shell]',
            '[+   8s] window=present(1) owner=dashboard rear=ON/ON           last='
        )
        $facts = Get-ExLockSampleFacts -SampleLines $lines
        $facts.SampleCount | Should Be 3
        $facts.WindowEverPresent | Should Be $true
        $facts.WindowSurvivedLock | Should Be $true
        ($null -eq $facts.WindowFirstAbsentSec) | Should Be $true
        $facts.WindowLastSeenSec | Should Be 8
        $facts.OwnerHeldThroughout | Should Be $true
        ($null -eq $facts.OwnerFirstLostSec) | Should Be $true
        $facts.RearHeldOnThroughout | Should Be $true
        ($null -eq $facts.RearFirstNonOnSec) | Should Be $true
    }

    It 'times the window leaving, the owner change and the rear display sleeping' {
        $lines = @(
            '[+   1s] window=present(1) owner=dashboard rear=ON/ON           last=pc-lock-issued',
            '[+   4s] window=present(1) owner=dashboard rear=ON/ON           last=project iconSet=[com.android.shell]',
            '[+   6s] window=absent owner=dashboard rear=ON/ON           last=Dashboard detach 0',
            '[+   9s] window=absent owner=native     rear=DOZE/DOZE_SUSPEND last=Dashboard detach 0'
        )
        $facts = Get-ExLockSampleFacts -SampleLines $lines
        $facts.WindowEverPresent | Should Be $true
        $facts.WindowSurvivedLock | Should Be $false
        $facts.WindowFirstAbsentSec | Should Be 6
        $facts.WindowLastSeenSec | Should Be 4
        $facts.OwnerHeldThroughout | Should Be $false
        $facts.OwnerFirstLostSec | Should Be 9
        $facts.RearHeldOnThroughout | Should Be $false
        $facts.RearFirstNonOnSec | Should Be 9
    }

    It 'reads a run where the window never made it onto the display at all' {
        $lines = @(
            '[+   1s] window=absent owner=dashboard rear=ON/ON           last=overlay add failed reason=BadTokenException',
            '[+   5s] window=absent owner=native     rear=DOZE/DOZE_SUSPEND last=Dashboard detach 0'
        )
        $facts = Get-ExLockSampleFacts -SampleLines $lines
        $facts.WindowEverPresent | Should Be $false
        $facts.WindowSurvivedLock | Should Be $false
        $facts.WindowFirstAbsentSec | Should Be 1
        $facts.OwnerFirstLostSec | Should Be 5
        $facts.RearFirstNonOnSec | Should Be 5
    }

    It 'exposes the per-sample records and ignores malformed lines' {
        $lines = @(
            '# samples (2)',
            '',
            'garbage that parses as nothing',
            '[+   2s] window=present(0) owner=other      rear=ON/OFF          last=x'
        )
        $facts = Get-ExLockSampleFacts -SampleLines $lines
        $facts.SampleCount | Should Be 1
        $facts.Samples[0].Elapsed | Should Be 2
        $facts.Samples[0].WindowDisplayId | Should Be 0
        $facts.Samples[0].Owner | Should Be 'other'
        $facts.Samples[0].RearState | Should Be 'ON'
        $facts.Samples[0].RearCommitted | Should Be 'OFF'
    }

    It 'keeps Samples flat: indexing hits one record, not a wrapped array' {
        $lines = @(
            '[+   1s] window=present(1) owner=dashboard rear=ON/ON           last=a',
            '[+   4s] window=absent owner=dashboard rear=ON/ON           last=b',
            '[+   9s] window=absent owner=native     rear=DOZE/DOZE_SUSPEND last=c'
        )
        $facts = Get-ExLockSampleFacts -SampleLines $lines
        $facts.Samples.Count | Should Be 3
        $facts.Samples[-1].Elapsed | Should Be 9
        $facts.Samples[-1].Owner | Should Be 'native'
    }

    It 'reads the real ticket #11 run: window blocked by E9, owner and rear lost at +5s' {
        $lines = Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'e10-lock-samples.txt')
        $facts = Get-ExLockSampleFacts -SampleLines $lines
        $facts.SampleCount | Should Be 17
        $facts.WindowEverPresent | Should Be $false
        $facts.WindowSurvivedLock | Should Be $false
        $facts.WindowFirstAbsentSec | Should Be 5
        $facts.OwnerFirstLostSec | Should Be 5
        $facts.RearFirstNonOnSec | Should Be 5
        $end = $facts.Samples | Select-Object -Last 1
        $end.RearState | Should Be 'DOZE_SUSPEND'
    }
}

Describe 'Get-ExRearCueMessage' {
    It 'strips the logcat header and keeps the bare message' {
        Get-ExRearCueMessage -Line '09-22 12:59:59.882  12659 12659 I RearCue : pc-lock-issued' |
            Should Be 'pc-lock-issued'
    }

    It 'survives the double-space pid/tid padding of the real device format' {
        Get-ExRearCueMessage -Line '09-22 12:54:55.698  8587 20818 I RearCue : Dashboard detach 0' |
            Should Be 'Dashboard detach 0'
    }
}

Describe 'Format-ExLockSampleLine' {
    It 'round-trips through Get-ExLockSampleFacts' {
        $line = Format-ExLockSampleLine -Elapsed 4 -WindowDisplayId 1 -Owner 'dashboard' `
            -StatePair 'ON/ON' -Last 'update iconSet'
        $facts = Get-ExLockSampleFacts -SampleLines @($line)
        $facts.SampleCount | Should Be 1
        $facts.Samples[0].Elapsed | Should Be 4
        $facts.Samples[0].WindowPresent | Should Be $true
        $facts.Samples[0].WindowDisplayId | Should Be 1
        $facts.Samples[0].Owner | Should Be 'dashboard'
        $facts.Samples[0].RearState | Should Be 'ON'
        $facts.Samples[0].RearCommitted | Should Be 'ON'
        $facts.Samples[0].Last | Should Be 'update iconSet'
    }

    It 'encodes an absent window as window=absent' {
        $line = Format-ExLockSampleLine -Elapsed 5 -Owner 'native' -StatePair 'DOZE/DOZE'
        $line | Should Match 'window=absent'
        $facts = Get-ExLockSampleFacts -SampleLines @($line)
        $facts.Samples[0].WindowPresent | Should Be $false
        ($null -eq $facts.Samples[0].WindowDisplayId) | Should Be $true
    }
}

Describe 'Get-ExChainSummary' {
    It 'summarises the auto up/down chain into the facts an experiment asserts on' {
        $events = Get-RearCueEvent -Logcat (Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'logcat-rearcue-chain.txt'))
        $summary = Get-ExChainSummary -Events $events
        $summary.LaunchRequested | Should Be $true
        $summary.LaunchSent | Should Be $true
        $summary.LaunchConfirmed | Should Be $true
        $summary.IconSetUpdates | Should Be 1
        $summary.Exited | Should Be $true
        $summary.PostedPackages -join ',' | Should Be 'com.android.shell,com.tencent.mm'
        $summary.SignalActions -join ',' | Should Be 'miui.intent.action.SUB_SCREEN_ON,android.intent.action.SCREEN_ON'
    }

    It 'reports a chain that never launched' {
        $events = Get-RearCueEvent -Logcat @('09-22 08:05:40.806 26015 26015 I RearCue : posted com.android.shell iconSet [] -> [com.android.shell] tracked=1')
        $summary = Get-ExChainSummary -Events $events
        $summary.LaunchRequested | Should Be $false
        $summary.LaunchSent | Should Be $false
        $summary.Exited | Should Be $false
        $summary.PostedPackages -join ',' | Should Be 'com.android.shell'
    }

    It 'does not call a server that was never seen "went down"' {
        $events = Get-RearCueEvent -Logcat (Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'logcat-rearcue-chain.txt'))
        $summary = Get-ExChainSummary -Events $events
        $summary.ShizukuServer | Should Be 'false'
        $summary.ShizukuServerWentDown | Should Be $false
        $summary.ShizukuServerCameBack | Should Be $false
    }

    It 'carries the Shizuku server state through a kill/restart run' {
        $events = Get-RearCueEvent -Logcat (Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'logcat-rearcue-shizuku.txt'))
        $summary = Get-ExChainSummary -Events $events
        $summary.ShizukuServer | Should Be 'true'
        $summary.ShizukuServerWentDown | Should Be $true
        $summary.ShizukuServerCameBack | Should Be $true
    }
}
