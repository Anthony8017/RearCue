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

    It 'prefers KeyguardServiceDelegate showing= over stale WMS lines (SYNTHETIC EDGE STATE -- NOT a fixture)' {
        # Real state seen 2026-09-23 00:07: the delegate said showing=false while the WMS
        # `mDreamingLockscreen=true`/`isKeyguardShowing=true` lines lingered and read "locked".
        $stale = @(
            '    KeyguardServiceDelegate',
            '      showing=false',
            '      inputRestricted=false',
            '      occluded=false',
            '      secure=true',
            '      dreaming=false',
            '    mShowingDream=false mDreamingLockscreen=true',
            '    isKeyguardShowing=true'
        ) -join "`n"
        Test-ExKeyguardLockedText -DumpsysWindow $stale | Should Be $false
        $reallyLocked = $stale -replace 'showing=false', 'showing=true'
        Test-ExKeyguardLockedText -DumpsysWindow $reallyLocked | Should Be $true
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

Describe 'Get-ExWakeSampleFacts' {
    # E12 (ticket #16): the wake keep-alive watch samples rear + main display state per point:
    #   [+   2s] rear=ON/ON              main=OFF/OFF            owner=native
    # Fixtures are the verbatim sample artifacts of the real 2026-09-22 collection round
    # (docs/poc-logs/20260922-155740-wake-collect, baseline leg and keep-alive leg).

    It 'reads the real baseline run: rear left ON at +9s and never came back' {
        $lines = Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'e12-samples-baseline.txt')
        $facts = Get-ExWakeSampleFacts -SampleLines $lines
        $facts.SampleCount | Should Be 14
        $facts.RearHeldOnThroughout | Should Be $false
        $facts.RearFirstNonOnSec | Should Be 9
        $facts.RearReturnedOnAfterLoss | Should Be $false
        $facts.RearEndStatePair | Should Be 'DOZE_SUSPEND/DOZE_SUSPEND'
        $facts.MainHeldOffThroughout | Should Be $true
        ($null -eq $facts.MainFirstOnSec) | Should Be $true
    }

    It 'reads the real keep-alive run: rear held ON, main display lit as a side effect' {
        $lines = Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'e12-samples-keepalive.txt')
        $facts = Get-ExWakeSampleFacts -SampleLines $lines
        $facts.SampleCount | Should Be 18
        $facts.RearHeldOnThroughout | Should Be $true
        ($null -eq $facts.RearFirstNonOnSec) | Should Be $true
        $facts.RearEndStatePair | Should Be 'ON/ON'
        $facts.MainHeldOffThroughout | Should Be $false
        $facts.MainFirstOnSec | Should Be 2
        # that run had no Dashboard at all (owner=native from the first point on)
        $facts.OwnerHeldThroughout | Should Be $false
        $facts.OwnerFirstLostSec | Should Be 2
        $facts.OwnerEnd | Should Be 'native'
    }

    It 'tracks who held the rear display through the watch (E13 survival facts)' {
        # SYNTHETIC EDGE STATE -- NOT a fixture: composed only to exercise the owner facts; the
        # wire format of every line is the real device format.
        $lines = @(
            '[+   2s] rear=ON/ON              main=OFF/OFF            owner=dashboard',
            '[+   4s] rear=ON/ON              main=OFF/OFF            owner=dashboard',
            '[+   6s] rear=ON/ON              main=OFF/OFF            owner=native'
        )
        $facts = Get-ExWakeSampleFacts -SampleLines $lines
        $facts.OwnerHeldThroughout | Should Be $false
        $facts.OwnerFirstLostSec | Should Be 6
        $facts.OwnerEnd | Should Be 'native'
    }

    It 'reports a Dashboard held in every sample as held throughout' {
        # SYNTHETIC EDGE STATE -- NOT a fixture (see above).
        $lines = @(
            '[+   2s] rear=ON/ON              main=OFF/OFF            owner=dashboard',
            '[+   4s] rear=ON/ON              main=OFF/OFF            owner=dashboard'
        )
        $facts = Get-ExWakeSampleFacts -SampleLines $lines
        $facts.OwnerHeldThroughout | Should Be $true
        ($null -eq $facts.OwnerFirstLostSec) | Should Be $true
        $facts.OwnerEnd | Should Be 'dashboard'
    }

    It 'reports a return to ON as a fact of its own' {
        $lines = @(
            '[+   2s] rear=ON/ON              main=OFF/OFF            owner=native',
            '[+   4s] rear=DOZE/DOZE_SUSPEND  main=OFF/OFF            owner=native',
            '[+   6s] rear=ON/ON              main=OFF/OFF            owner=native'
        )
        $facts = Get-ExWakeSampleFacts -SampleLines $lines
        $facts.RearHeldOnThroughout | Should Be $false
        $facts.RearFirstNonOnSec | Should Be 4
        $facts.RearReturnedOnAfterLoss | Should Be $true
        $facts.RearEndStatePair | Should Be 'ON/ON'
    }

    It 'ignores header and malformed lines instead of guessing' {
        $lines = @(
            '# baseline (no keep-alive)',
            '',
            'garbage that parses as nothing',
            '[+   2s] rear=ON/ON main=OFF/OFF owner=native'
        )
        $facts = Get-ExWakeSampleFacts -SampleLines $lines
        $facts.SampleCount | Should Be 1
        $facts.Samples[0].Elapsed | Should Be 2
        $facts.Samples[0].RearState | Should Be 'ON'
        $facts.Samples[0].RearCommitted | Should Be 'ON'
        $facts.Samples[0].MainState | Should Be 'OFF'
        $facts.Samples[0].MainCommitted | Should Be 'OFF'
        $facts.Samples[0].Owner | Should Be 'native'
    }

    It 'claims nothing about an empty watch' {
        $facts = Get-ExWakeSampleFacts -SampleLines @()
        $facts.SampleCount | Should Be 0
        $facts.RearHeldOnThroughout | Should Be $false
        $facts.RearReturnedOnAfterLoss | Should Be $false
        $facts.MainHeldOffThroughout | Should Be $false
        $facts.OwnerHeldThroughout | Should Be $false
        ($null -eq $facts.OwnerFirstLostSec) | Should Be $true
        $facts.OwnerEnd | Should Be ''
    }
}

Describe 'Format-ExWakeSampleLine' {
    It 'writes exactly the wire format of the real ticket #16 collection run' {
        # The archived sample line is the source of truth for the wire format (fixture is real
        # device data); the writer may not drift from it.
        $real = Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'e12-samples-baseline.txt') |
            Where-Object { $_ -match '^\[\+' } | Select-Object -First 1
        Format-ExWakeSampleLine -Elapsed 2 -RearPair 'ON/ON' -MainPair 'OFF/OFF' -Owner 'native' |
            Should Be $real
    }

    It 'round-trips through Get-ExWakeSampleFacts' {
        $line = Format-ExWakeSampleLine -Elapsed 7 -RearPair 'DOZE_SUSPEND/DOZE_SUSPEND' `
            -MainPair 'OFF/OFF' -Owner 'native'
        $facts = Get-ExWakeSampleFacts -SampleLines @($line)
        $facts.SampleCount | Should Be 1
        $facts.Samples[0].Elapsed | Should Be 7
        $facts.Samples[0].RearState | Should Be 'DOZE_SUSPEND'
        $facts.Samples[0].RearCommitted | Should Be 'DOZE_SUSPEND'
        $facts.Samples[0].MainState | Should Be 'OFF'
        $facts.Samples[0].Owner | Should Be 'native'
    }
}

Describe 'Format-ExRearBehavior' {
    # Extracted at ticket #16 review close-out: one wording shape shared by both watch legs.

    It 'words a held-on watch from the real keep-alive fixture' {
        $lines = Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'e12-samples-keepalive.txt')
        $facts = Get-ExWakeSampleFacts -SampleLines $lines
        Format-ExRearBehavior -Facts $facts -WatchLabel 'keep-alive watch' |
            Should Be 'rear stayed ON through the whole keep-alive watch'
    }

    It 'words a left-ON watch from the real baseline fixture' {
        $lines = Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'e12-samples-baseline.txt')
        $facts = Get-ExWakeSampleFacts -SampleLines $lines
        Format-ExRearBehavior -Facts $facts -WatchLabel 'control watch' |
            Should Be 'rear left ON at +9s and never ON again, ending DOZE_SUSPEND/DOZE_SUSPEND'
    }

    It 'carries a return to ON as its own wording fact' {
        $lines = @(
            '[+   2s] rear=ON/ON              main=OFF/OFF            owner=native',
            '[+   4s] rear=DOZE/DOZE_SUSPEND  main=OFF/OFF            owner=native',
            '[+   6s] rear=ON/ON              main=OFF/OFF            owner=native'
        )
        $facts = Get-ExWakeSampleFacts -SampleLines $lines
        Format-ExRearBehavior -Facts $facts -WatchLabel 'control watch' |
            Should Be 'rear left ON at +4s (ON again later), ending ON/ON'
    }

    It 'claims nothing about an unreadable watch' {
        $facts = Get-ExWakeSampleFacts -SampleLines @()
        Format-ExRearBehavior -Facts $facts -WatchLabel 'control watch' | Should Be 'no readable rear state'
    }
}

Describe 'Get-ExWakeTickFacts' {
    # E12 (ticket #16): the injection loop logs one line per iteration on the device
    #   tick 09-22 15:58:30 rc=0
    # under a `loop start ... pid= ... display= ... sleep= ...` header. The tick file is the
    # device-side proof that the loop really ran and the `input` commands were accepted.
    # Fixture: the verbatim tick log of the real 2026-09-22 collection run
    # (docs/poc-logs/20260922-155740-wake-collect).

    It 'reads the real tick log: 77 injections, no input errors, clean exit' {
        $facts = Get-ExWakeTickFacts -TickLines (Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'e12-wake-ticks.txt'))
        $facts.Started | Should Be $true
        $facts.Pid | Should Be 7606
        $facts.DisplayId | Should Be 1
        $facts.SleepSeconds | Should Be '0.5'
        $facts.TickCount | Should Be 77
        $facts.ErrorCount | Should Be 0
        $facts.FirstTick | Should Be '09-22 15:58:30'
        $facts.LastTick | Should Be '09-22 15:59:13'
        $facts.Exited | Should Be $true
    }

    It 'counts input command errors without hiding them' {
        $lines = @(
            'loop start 09-22 15:58:30 pid=99 display=1 sleep=1',
            'tick 09-22 15:58:30 rc=0',
            'tick 09-22 15:58:31 rc=1',
            'tick 09-22 15:58:32 rc=0'
        )
        $facts = Get-ExWakeTickFacts -TickLines $lines
        $facts.TickCount | Should Be 3
        $facts.ErrorCount | Should Be 1
        $facts.Ticks[1].Rc | Should Be 1
    }

    It 'reports a loop that never started or never exited as facts, not as success' {
        $none = Get-ExWakeTickFacts -TickLines @()
        $none.Started | Should Be $false
        $none.TickCount | Should Be 0
        $none.Exited | Should Be $false

        $killed = @(
            'loop start 09-22 15:58:30 pid=99 display=1 sleep=1',
            'tick 09-22 15:58:30 rc=0'
        )
        $facts = Get-ExWakeTickFacts -TickLines $killed
        $facts.Started | Should Be $true
        $facts.Exited | Should Be $false
    }
}

Describe 'Get-ExPowerGroupEvents' {
    # E12 (ticket #16): the lock event and the injected wake key both leave system-side traces
    #   PowerGroup: Powering off display group due to power_button (groupId= 1, ...)
    #   PowerGroup: Waking up power group from Dozing (groupId=1, ..., reason=WAKE_REASON_WAKE_KEY,
    #               details=android.policy:KEY)...
    # Fixture: verbatim `logcat -b all` slice of the real 2026-09-22 collection run
    # (docs/poc-logs/20260922-155740-wake-collect), with neighbouring decoy lines left in.
    $lines = Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'logcat-e12-power-group.txt')

    It 'reads the six power-group transitions of the real run' {
        $events = Get-ExPowerGroupEvents -Logcat $lines
        @($events).Count | Should Be 6
    }

    It 'reads the lock event: both display groups powered off by power_button' {
        # NOTE: no @() wrap around these parser results -- they return `,$arr` so an empty
        # result stays an array; @(...) would count the inner array as one element.
        $events = Get-ExPowerGroupEvents -Logcat $lines
        $events[0].Kind | Should Be 'power-off'
        $events[0].GroupId | Should Be 0
        $events[0].Reason | Should Be 'power_button'
        $events[0].Time | Should Be '09-22 15:57:51.690'
        $events[1].Kind | Should Be 'power-off'
        $events[1].GroupId | Should Be 1
        $events[1].Reason | Should Be 'power_button'
        $events[1].Time | Should Be '09-22 15:57:51.692'
    }

    It 'reads the wake events with their reason and details' {
        $events = Get-ExPowerGroupEvents -Logcat $lines
        $events[2].Kind | Should Be 'wake'
        $events[2].GroupId | Should Be 0
        $events[2].Reason | Should Be 'WAKE_REASON_WAKE_KEY'
        $events[2].Details | Should Be 'android.policy:KEY'
        $events[3].Kind | Should Be 'power-off'
        $events[3].Reason | Should Be 'application'
        $events[4].Kind | Should Be 'wake'
        $events[4].GroupId | Should Be 1
        $events[4].Details | Should Be 'android.policy:KEY'
        $events[5].Kind | Should Be 'wake'
        $events[5].Reason | Should Be 'WAKE_REASON_POWER_BUTTON'
        $events[5].Details | Should Be 'android.policy:POWER'
    }

    It 'drops decoy lines that merely mention a power group' {
        $events = Get-ExPowerGroupEvents -Logcat $lines
        ($events | Where-Object { $_.Raw -match 'DreamManagerService|goToSleepInternal|BaseMiuiPhoneWindowManager' }).Count |
            Should Be 0
        ($events.Raw -join "`n") | Should Match 'PowerGroup: Powering off display group|PowerGroup: Waking up power group'
    }
}

Describe 'Get-ExWakePollution' {
    # E12 (ticket #16): external interference with a watch window must go to erratum.md, never
    # into the verdict. Known pollutions: fingerprint wakes (the human touching the phone) and
    # power-button presses the script did not make. Fixtures are real device lines:
    #   * logcat-e11-fingerprint-wake.txt -- the pollution that ruined the ticket #11 first run
    #     (session 20260922-125426-overlay-lock; archived inside a flattened log section there,
    #     sliced back to its single logcat line here, characters unchanged)
    #   * logcat-e12-power-group.txt -- the 2026-09-22 collection round (its own power-button
    #     press and its own wake-key lines)
    #   * logcat-e12-pollution.txt -- the human-polluted E12 round (session
    #     20260922-162618-wake-keepalive): two FINGERPRINT:UnlockFinishT wakes and a hand-pressed
    #     power button INSIDE the keep-alive watch window

    It 'flags the fingerprint wake of the ticket #11 pollution run' {
        $hits = Get-ExWakePollution -Logcat (Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'logcat-e11-fingerprint-wake.txt'))
        $hits.Count | Should Be 1
        $hits[0].Class | Should Be 'fingerprint'
        $hits[0].Time | Should Be '09-22 12:54:55.285'
    }

    It 'flags both fingerprint wake variants of the polluted E12 round' {
        # The device does NOT only log `FINGERPRINT:finishCallBack` (ticket #11); the E12 round
        # was woken twice by `FINGERPRINT:UnlockFinishT`. Both variants are pollution.
        $hits = Get-ExWakePollution -Logcat (Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'logcat-e12-pollution.txt'))
        $fingerprints = $hits | Where-Object { $_.Class -eq 'fingerprint' }
        @($fingerprints).Count | Should Be 2
        @($fingerprints | ForEach-Object { $_.Time }) -join ',' | Should Be '09-22 16:27:48.999,09-22 16:27:51.627'
    }

    It 'flags every power-button transition (wake AND off), never the injected wake key' {
        # Attributing a power-button hit to the script or to a human is the scenario`s job (it
        # knows its own press times); the parser only says "this was a power-button transition".
        $hits = Get-ExWakePollution -Logcat (Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'logcat-e12-pollution.txt'))
        $buttons = $hits | Where-Object { $_.Class -eq 'power-button' }
        @($buttons).Count | Should Be 9
        @($buttons | Where-Object { $_.Time -eq '09-22 16:28:04.494' }).Count | Should Be 1
        ($hits | Where-Object { $_.Raw -match 'WAKE_REASON_WAKE_KEY' }).Count | Should Be 0
    }

    It 'reads the power-button transitions of the collection round' {
        $hits = Get-ExWakePollution -Logcat (Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'logcat-e12-power-group.txt'))
        $hits.Count | Should Be 3
        @($hits | Where-Object { $_.Class -eq 'power-button' }).Count | Should Be 3
    }

    It 'skips blank lines left in the capture instead of failing to bind' {
        # A `logcat -d -b all` capture carries blank separator lines; they are data like any
        # other non-matching line (a real run bound them and threw once).
        $hits = Get-ExWakePollution -Logcat @(
            '',
            '09-22 22:30:00.000  5157  5409 I PowerGroup: Powering off display group due to power_button (groupId= 0, uid= 1000)...',
            ''
        )
        @($hits).Count | Should Be 1
        @(Get-ExPowerGroupEvents -Logcat @('', '09-22 22:30:00.000  5157  5409 I PowerGroup: Powering off display group due to power_button (groupId= 0, uid= 1000)...')).Count | Should Be 1
    }

    It 'reads nothing in a clean buffer' {
        (Get-ExWakePollution -Logcat @()).Count | Should Be 0
    }
}

Describe 'Get-ExTaskPlacement' {
    # E14 (ticket #18): where does the Dashboard root task actually sit? The verdict must read it
    # from `dumpsys activity activities`, never from the `service call` reply.
    # Fixtures:
    #   dumpsys-activities-task-moved.txt -- verbatim excerpt of the 2026-09-22 fixture-collection
    #     round (docs/poc-logs/20260922-174125-task-move-collect, raw-21-after-move-to-0.txt):
    #     `service call activity_task 51 i32 12985 i32 0` moved the Dashboard root task to
    #     display 0. Its block carries `rootOfTask=true task=Task{... #12985 ...}` self-references
    #     that must not be read as task headers.
    #   dumpsys-activities-dashboard.txt / dumpsys-activities-native.txt -- ticket #6 archives
    #     (on the rear resumed; nested home task with rootTaskId=3).

    It 'finds the Dashboard root task on display 0 after the transaction moved it' {
        $place = Get-ExTaskPlacement -DumpsysActivities (Get-ExFixture 'dumpsys-activities-task-moved.txt') -TaskId 12985
        $place.Found | Should Be $true
        $place.TaskId | Should Be 12985
        $place.DisplayId | Should Be 0
        $place.RootTaskId | Should Be 12985
        $place.Component | Should Be 'com.rearcue.poc/.rear.RearDashboardActivity'
        $place.Resumed | Should Be $true
    }

    It 'accepts the t-prefixed task id form used in dumpsys and in the ticket' {
        $place = Get-ExTaskPlacement -DumpsysActivities (Get-ExFixture 'dumpsys-activities-task-moved.txt') -TaskId 't12985'
        $place.Found | Should Be $true
        $place.DisplayId | Should Be 0
    }

    It 'reads the same task while it is on the rear display' {
        $place = Get-ExTaskPlacement -DumpsysActivities (Get-ExFixture 'dumpsys-activities-dashboard.txt') -TaskId 12850
        $place.DisplayId | Should Be 1
        $place.RootTaskId | Should Be 12850
        $place.Component | Should Be 'com.rearcue.poc/.rear.RearDashboardActivity'
    }

    It 'reads the root task id of a nested task (rootTaskId= in the header)' {
        $place = Get-ExTaskPlacement -DumpsysActivities (Get-ExFixture 'dumpsys-activities-native.txt') -TaskId 12819
        $place.Found | Should Be $true
        $place.DisplayId | Should Be 1
        $place.RootTaskId | Should Be 3
        $place.Component | Should Be 'com.xiaomi.subscreencenter/.SubScreenLauncher'
        $place.Resumed | Should Be $false
    }

    It 'finds the task by component when its id is not known yet' {
        $place = Get-ExTaskPlacement -DumpsysActivities (Get-ExFixture 'dumpsys-activities-task-moved.txt') `
            -Component 'com.rearcue.poc/.rear.RearDashboardActivity'
        $place.TaskId | Should Be 12985
        $place.DisplayId | Should Be 0
    }

    It 'reports a task that is gone from the dump as absent, with no display' {
        $place = Get-ExTaskPlacement -DumpsysActivities (Get-ExFixture 'dumpsys-activities-dashboard.txt') -TaskId 99999
        $place.Found | Should Be $false
        $place.DisplayId | Should Be $null
        $place = Get-ExTaskPlacement -DumpsysActivities '' -TaskId 12985
        $place.Found | Should Be $false
    }

    It 'needs a task id or a component' {
        { Get-ExTaskPlacement -DumpsysActivities 'x' } | Should Throw
    }
}

Describe 'ConvertTo-ExServiceCallResult' {
    # E14 (ticket #18): the raw `service call` reply is archived evidence only -- the verdict never
    # reads it (the ticket is explicit). Fixture: verbatim replies of the 2026-09-22
    # fixture-collection round (service-call-task-move.txt): the void success of the transaction
    # that moved the task, and the reply of a transaction code that does not exist.
    $lines = @(Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'service-call-task-move.txt') |
        Where-Object { $_ -notmatch '^#' })

    It 'reads the void success reply of the real task-move transaction' {
        $result = ConvertTo-ExServiceCallResult -Line $lines[0]
        $result.Found | Should Be $true
        $result.IsError | Should Be $false
        $result.Status | Should Be '00000000'
        $result.Raw | Should Match '^Result: Parcel'
    }

    It 'reads the unknown-code reply as an error carrying its text' {
        $result = ConvertTo-ExServiceCallResult -Line $lines[1]
        $result.Found | Should Be $true
        $result.IsError | Should Be $true
        $result.ErrorText | Should Match 'Not a data message'
        $result.Status | Should Match 'ffffffb6'
    }

    It 'reports a command without a reply line instead of guessing' {
        (ConvertTo-ExServiceCallResult -Line 'error: device offline').Found | Should Be $false
        (ConvertTo-ExServiceCallResult -Line '').Found | Should Be $false
    }
}

Describe 'Get-ExTaskMoveEvents' {
    # E14 (ticket #18): system-side evidence of a task-move attempt. Fixtures are verbatim lines
    # from earlier archives (locked-state deny pair: session 20260922-162618-wake-keepalive;
    # task reap: session 20260922-125937-overlay-lock).

    It 'classifies the locked-state deny pair and keeps the lines verbatim' {
        $hits = Get-ExTaskMoveEvents -Logcat (Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'logcat-task-move-locked-deny.txt'))
        $hits.LockedDeny | Should Be $true
        $hits.DenyLines.Count | Should Be 2
        $hits.DenyLines[0] | Should Match 'rearDisplay check locked: com.rearcue.poc -> deny'
        $hits.DenyLines[1] | Should Match 'aborted activity = ActivityRecord'
    }

    It 'reads the system reclaiming the Dashboard task as a reap, not as a deny' {
        $hits = Get-ExTaskMoveEvents -Logcat (Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'logcat-task-move-reap.txt'))
        $hits.ReapLines.Count | Should Be 1
        $hits.LockedDeny | Should Be $false
        $hits.DenyLines.Count | Should Be 0
    }

    It 'reports nothing in a clean buffer' {
        $hits = Get-ExTaskMoveEvents -Logcat @('09-22 17:00:00.000  1000  1000 I foo: bar')
        $hits.DenyLines.Count | Should Be 0
        $hits.LockedDeny | Should Be $false
        $hits.ReapLines.Count | Should Be 0
    }

    It 'never counts unrelated system chatter as a refusal (real run noise)' {
        # Fixture: verbatim noise lines of the real E14 run (20260922-181414-task-move) -- a loose
        # `SecurityException` / `Not allow` anchor counted SettingsProvider, Java_Reflection, MIUI
        # data and lyra-discovery chatter into the deny counter there.
        $hits = Get-ExTaskMoveEvents -Logcat (Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'logcat-task-move-noise.txt'))
        $hits.DenyLines.Count | Should Be 0
        $hits.LockedDeny | Should Be $false
        $hits.ReapLines.Count | Should Be 0
    }
}

Describe 'Format-ExTaskMoveSampleLine' {
    It 'round-trips through Get-ExTaskMoveSampleFacts' {
        $line = Format-ExTaskMoveSampleLine -Elapsed 3 -TaskId 12985 -TaskDisplay 1 -Owner 'dashboard' `
            -StatePair 'ON/ON' -Last 'pc-e14-move-issued'
        $facts = Get-ExTaskMoveSampleFacts -SampleLines @($line) -RearDisplayId 1
        $facts.SampleCount | Should Be 1
        $facts.Samples[0].Elapsed | Should Be 3
        $facts.Samples[0].TaskId | Should Be 12985
        $facts.Samples[0].TaskDisplay | Should Be 1
        $facts.Samples[0].OnRear | Should Be $true
        $facts.Samples[0].Owner | Should Be 'dashboard'
        $facts.Samples[0].RearState | Should Be 'ON'
        $facts.Samples[0].Last | Should Be 'pc-e14-move-issued'
    }

    It 'encodes a missing task as task=absent' {
        $line = Format-ExTaskMoveSampleLine -Elapsed 5 -Owner 'native' -StatePair 'DOZE/DOZE_SUSPEND'
        $line | Should Match 'task=absent'
        $facts = Get-ExTaskMoveSampleFacts -SampleLines @($line) -RearDisplayId 1
        $facts.Samples[0].TaskId | Should Be $null
        $facts.Samples[0].OnRear | Should Be $false
    }
}

Describe 'Get-ExTaskMoveSampleFacts' {
    # E14 (ticket #18): the locked-phase watch samples task placement + rear owner + rear state
    # per point. Wire line:
    #   [+   3s] task=t12985@d1 owner=dashboard rear=ON/ON           last=<latest RearCue line>
    # The real run is asserted from the VERBATIM fixture `e14-samples-run.txt` (cases below). The
    # hand-written lines in the cases above cover only edge states the real run does not contain
    # (task vanished / never landed / malformed input); they are wire-format samples, not fixtures.
    It 'times the task landing on the rear display' {
        $lines = @(
            '[+   1s] task=t12985@d0 owner=other      rear=DOZE/DOZE_SUSPEND last=pc-e14-lock-issued',
            '[+   3s] task=t12985@d1 owner=dashboard rear=ON/ON           last=rear recreate',
            '[+   5s] task=t12985@d1 owner=dashboard rear=ON/ON           last='
        )
        $facts = Get-ExTaskMoveSampleFacts -SampleLines $lines -RearDisplayId 1
        $facts.SampleCount | Should Be 3
        $facts.TaskEverOnRear | Should Be $true
        $facts.TaskFirstOnRearSec | Should Be 3
        $facts.TaskLastOnRearSec | Should Be 5
        ($null -eq $facts.TaskMissingFirstSec) | Should Be $true
        $facts.OwnerFirstDashboardSec | Should Be 3
    }

    It 'separates "never landed" from "task vanished"' {
        $lines = @(
            '[+   1s] task=t12985@d0 owner=native     rear=DOZE/DOZE_SUSPEND last=a',
            '[+   3s] task=absent owner=native     rear=DOZE/DOZE_SUSPEND last=b',
            '[+   5s] task=absent owner=native     rear=DOZE/DOZE_SUSPEND last=c'
        )
        $facts = Get-ExTaskMoveSampleFacts -SampleLines $lines -RearDisplayId 1
        $facts.TaskEverOnRear | Should Be $false
        $facts.TaskFirstOnRearSec | Should Be $null
        $facts.TaskMissingFirstSec | Should Be 3
        $facts.TaskEndPlacement | Should Be 'absent'
    }

    It 'reports the end placement and the rear state facts' {
        $lines = @(
            '[+   1s] task=t12985@d1 owner=dashboard rear=ON/ON           last=a',
            '[+   4s] task=t12985@d0 owner=other      rear=DOZE/DOZE_SUSPEND last=b'
        )
        $facts = Get-ExTaskMoveSampleFacts -SampleLines $lines -RearDisplayId 1
        $facts.TaskLastOnRearSec | Should Be 1
        $facts.TaskEndPlacement | Should Be 'd0'
        $facts.RearEndStatePair | Should Be 'DOZE/DOZE_SUSPEND'
    }

    It 'ignores malformed lines and claims nothing about an empty watch' {
        $lines = @(
            '# samples (2)',
            '',
            'garbage that parses as nothing',
            '[+   2s] task=t12985@d1 owner=dashboard rear=ON/ON           last=x'
        )
        $facts = Get-ExTaskMoveSampleFacts -SampleLines $lines -RearDisplayId 1
        $facts.SampleCount | Should Be 1
        $empty = Get-ExTaskMoveSampleFacts -SampleLines @() -RearDisplayId 1
        $empty.SampleCount | Should Be 0
        $empty.TaskEverOnRear | Should Be $false
    }

    It 'keeps Samples flat: indexing hits one record, not a wrapped array' {
        $lines = @(
            '[+   1s] task=t12985@d0 owner=other      rear=ON/ON           last=a',
            '[+   4s] task=t12985@d1 owner=dashboard rear=ON/ON           last=b',
            '[+   9s] task=absent owner=native     rear=DOZE/DOZE_SUSPEND last=c'
        )
        $facts = Get-ExTaskMoveSampleFacts -SampleLines $lines -RearDisplayId 1
        $facts.Samples.Count | Should Be 3
        $facts.Samples[-1].Elapsed | Should Be 9
        $facts.Samples[-1].Owner | Should Be 'native'
    }

    It 'reads the real ticket #18 run: 35 points, rear ON the whole watch, task parked on the rear' {
        # Fixture: the verbatim sample lines of the real E14 run (20260922-181414-task-move). The
        # watch starts with the task ALREADY on the rear (the app re-projected inside the keyguard
        # window right after the lock) and ends with it moved back there by the locked transaction.
        $lines = Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'e14-samples-run.txt')
        $facts = Get-ExTaskMoveSampleFacts -SampleLines $lines -RearDisplayId 1
        $facts.SampleCount | Should Be 35
        $facts.TaskEverOnRear | Should Be $true
        $facts.TaskFirstOnRearSec | Should Be 2
        ($null -eq $facts.TaskMissingFirstSec) | Should Be $true
        $facts.TaskEndPlacement | Should Be 'd1'
        $facts.RearEndStatePair | Should Be 'ON/ON'
    }

    It 'round-trips a real sample line of the ticket #18 run without wire-format drift' {
        $real = Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'e14-samples-run.txt') |
            Where-Object { $_ -match '^\[\+' } | Select-Object -First 1
        $facts = Get-ExTaskMoveSampleFacts -SampleLines @($real) -RearDisplayId 1
        $facts.SampleCount | Should Be 1
        $s = $facts.Samples[0]
        Format-ExTaskMoveSampleLine -Elapsed $s.Elapsed -TaskId $s.TaskId -TaskDisplay $s.TaskDisplay `
            -Owner $s.Owner -StatePair ('{0}/{1}' -f $s.RearState, $s.RearCommitted) -Last $s.Last |
            Should Be $real
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

Describe 'Get-ExShuidProbeFacts' {
    # SH-UID probe (ticket #17): the device probe prints machine-readable facts to stdout
    # ("probe: <verb> k=v ...") and the verdict quotes them as the probe process's own report.
    # Fixtures are the VERBATIM probe outputs of the 2026-09-22 fixture-collection round
    # (docs/poc-logs/20260922-192356-shuid-collect):
    #   shuid-probe-run.txt          the display-0 control run: three glue strategies, every
    #                                WindowManager.addView refused by the device
    #   shuid-probe-registercheck.txt the app-attach registration check -- the process is KILLED
    #                                at that call, so the output just ENDS at `probe: register
    #                                attempt ...` (no result, no `probe: done`). That silence is
    #                                the fact this parser must not paper over.

    It 'reads the real control run: identity, attribution and the refusal' {
        $facts = Get-ExShuidProbeFacts -ProbeLines (Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'shuid-probe-run.txt'))
        $facts.Started | Should Be $true
        $facts.Mode | Should Be 'add'
        $facts.DisplayId | Should Be 0
        $facts.Pid | Should Be 21361
        $facts.Uid | Should Be 2000
        $facts.Title | Should Be 'RearCueShUidProbe'
        $facts.HoldSeconds | Should Be 6
        $facts.RequestedPackage | Should Be 'com.rearcue.poc'
        $facts.ContextOk | Should Be $true
        $facts.ContextSource | Should Be 'package:com.rearcue.poc'
        $facts.ContextOpPackage | Should Be 'android'
        $facts.AddOk | Should Be $false
        $facts.AddFailed | Should Be $true
        $facts.AddFailureReason | Should Match 'IllegalStateException: Unknown pid=21361 uid=2000'
        $facts.Done | Should Be $true
        $facts.DoneAdded | Should Be $false
        $facts.DoneRemoved | Should Be $false
        $facts.RegisterAttempted | Should Be $false
    }

    It 'keeps every refused attempt with its own failure reason' {
        $facts = Get-ExShuidProbeFacts -ProbeLines (Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'shuid-probe-run.txt'))
        $facts.AttemptFailures.Count | Should Be 3
        $facts.AttemptFailures[0] | Should Match 'Unknown pid=21361 uid=2000'
    }

    It 'reads the real register check as attempted with no result and no done' {
        $facts = Get-ExShuidProbeFacts -ProbeLines (Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'shuid-probe-registercheck.txt'))
        $facts.RegisterAttempted | Should Be $true
        $facts.RegisterResult | Should Be $null
        $facts.Done | Should Be $false
        $facts.AddFailed | Should Be $true
    }

    It 'reads a successful add, remove and register outcome' {
        # SYNTHETIC EDGE STATE -- NOT fixtures (real runs never got an accepted add, a `remove ok`
        # or a register success): wire-format lines covering only states absent from real output.
        $lines = @(
            'probe: start mode=remove display=1 pid=1 uid=2000 title=RearCueShUidProbe hold=1 pkg=com.rearcue.poc',
            'probe: context ok source=package:com.rearcue.poc opPackage=android',
            'probe: add ok display=1 title=RearCueShUidProbe strategy=display-context',
            'probe: remove ok',
            'probe: register ok method=attachApplication',
            'probe: done mode=remove added=true removed=true'
        )
        $facts = Get-ExShuidProbeFacts -ProbeLines $lines
        $facts.AddOk | Should Be $true
        $facts.AddStrategy | Should Be 'display-context'
        $facts.AddFailed | Should Be $false
        $facts.RemoveOk | Should Be $true
        $facts.RegisterResult | Should Be 'ok'
        $facts.DoneAdded | Should Be $true
        $facts.DoneRemoved | Should Be $true
    }

    It 'reads a probe self-failure instead of guessing' {
        # EDGE-STATE ONLY: the real runs never self-failed (the dex and the process came up fine).
        $lines = @(
            'probe: start mode=add display=0 pid=2 uid=2000 title=RearCueShUidProbe hold=6 pkg=com.rearcue.poc',
            'probe: self-fail reason=java.lang.IllegalStateException: boom',
            'probe: done mode=add added=false removed=false'
        )
        $facts = Get-ExShuidProbeFacts -ProbeLines $lines
        $facts.SelfFail | Should Be $true
        $facts.SelfFailureReason | Should Match 'boom'
    }

    It 'claims nothing about an empty or unreadable capture' {
        $empty = Get-ExShuidProbeFacts -ProbeLines @()
        $empty.Started | Should Be $false
        $empty.Done | Should Be $false
        $empty.AddOk | Should Be $false
        $empty.RegisterAttempted | Should Be $false
        $garbage = Get-ExShuidProbeFacts -ProbeLines @('Killed', '')
        $garbage.Started | Should Be $false
        $garbage.SelfFail | Should Be $false
    }
}

Describe 'Get-ExShuidProbeWindow' {
    # SH-UID probe (ticket #17): the decisive device fact is "is the probe window really on the
    # target display" -- read from `dumpsys window windows` (mDisplayId / ty= / owner uid), never
    # from the probe's own claim or a command exit code. On this build the probe window NEVER
    # lands (that is the finding), so the POSITIVE shape is asserted on the real ticket #10 E9
    # window block (dumpsys-window-overlay-probe.txt, verbatim device output) under its OWN real
    # title -- no renaming, no doctoring (ticket #18 review rule: fixtures are real output only);
    # the negative shape is that same real dump as-is.
    $dump = Get-ExFixture 'dumpsys-window-overlay-probe.txt'

    It 'reports the probe as absent in a dump that only carries other windows' {
        # Real device output: the E9 probe window block is there under ITS OWN title, so for
        # this parser the dump has no RearCueShUidProbe -- exactly the real ticket #17 shape.
        $probe = Get-ExShuidProbeWindow -DumpsysWindow $dump
        $probe.Found | Should Be $false
        $probe.DisplayId | Should Be $null
        $probe.OwnerUid | Should Be $null
        $probe.Type | Should Be $null
    }

    It 'reads display, owner uid/pid, type, package and appop of a real probe window block' {
        # Real device output (ticket #10 E9 display-0 control run), parsed under its real title.
        $probe = Get-ExShuidProbeWindow -DumpsysWindow $dump -Title 'RearCueOverlayProbe'
        $probe.Found | Should Be $true
        $probe.DisplayId | Should Be 0
        $probe.OwnerUid | Should Be 10333
        $probe.OwnerPid | Should Be 3075
        $probe.Type | Should Be 'APPLICATION_OVERLAY'
        $probe.Package | Should Be 'com.rearcue.poc'
        $probe.Appop | Should Be 'SYSTEM_ALERT_WINDOW'
        $probe.DisplayLine | Should Match 'mDisplayId=0 mSession=Session\{'
        $probe.OwnerLine | Should Match 'mOwnerUid=10333'
        $probe.Header | Should Match 'RearCueOverlayProbe\}:'
    }

    It 'reports the rear display when the block carries mDisplayId=1' {
        # SYNTHETIC EDGE STATE -- NOT a fixture (no real run ever landed any probe window on the
        # rear display): only the display id of the real block above is flipped, covering the
        # display-id parse of the rear-landing shape that real output does not contain.
        $flipped = $dump -replace '(RearCueOverlayProbe\}:\r?\n\s+mDisplayId=)0', '${1}1'
        $probe = Get-ExShuidProbeWindow -DumpsysWindow $flipped -Title 'RearCueOverlayProbe'
        $probe.Found | Should Be $true
        $probe.DisplayId | Should Be 1
    }

    It 'returns not-found for an empty dump' {
        $probe = Get-ExShuidProbeWindow -DumpsysWindow ''
        $probe.Found | Should Be $false
    }
}

Describe 'Get-ExShuidWindowEvents' {
    # SH-UID probe (ticket #17): system-side WindowManager lines around the shell-uid window
    # adds, classified so the verdict can quote the right one verbatim. Fixtures:
    #   logcat-shuid-window-unknown-pid.txt -- VERBATIM system lines of the 2026-09-22
    #     fixture-collection round (docs/poc-logs/20260922-192356-shuid-collect), rear phase:
    #     the window-context attach warning + the "Window Manager Crash" refusals + am_wtf echoes
    #   logcat-overlay-e9-rear-denied.txt -- the real ticket #10 rear-policy denial (this ticket
    #     never reaches that gate; the line is pinned here so BLOCKED stays quotable)

    It 'reads the real unknown-pid refusals as their own class, not as a rear-policy denial' {
        $hits = Get-ExShuidWindowEvents -Logcat (Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'logcat-shuid-window-unknown-pid.txt'))
        $hits.RearPolicyDeny | Should Be $false
        $hits.RearPolicyLine | Should Be $null
        $hits.DenyLines.Count | Should Be 0
        $hits.ProcessUnknown | Should Be $true
        $hits.ProcessUnknownLines.Count | Should Be 5
    }

    It 'reads the real rear-policy denial and quotes its line verbatim' {
        $hits = Get-ExShuidWindowEvents -Logcat (Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'logcat-overlay-e9-rear-denied.txt'))
        $hits.RearPolicyDeny | Should Be $true
        $hits.RearPolicyLine | Should Match 'Not allow non-system app com.rearcue.poc add system_window on rear display'
        $hits.DenyLines.Count | Should Be 1
    }

    It 'collects window lines that carry the probe title' {
        # SYNTHETIC EDGE STATE -- NOT a fixture: no real system line carries the probe title on
        # this build (the window never lands), so the title hit shape is wire-format only.
        $hits = Get-ExShuidWindowEvents -Logcat @('09-22 19:00:00.000  5157  5497 I WindowManager: added Window{1 u0 RearCueShUidProbe}')
        $hits.TitleLines.Count | Should Be 1
    }

    It 'reports nothing in a clean buffer' {
        $hits = Get-ExShuidWindowEvents -Logcat @('09-22 19:00:00.000  5157  5497 I foo: bar')
        $hits.RearPolicyDeny | Should Be $false
        $hits.DenyLines.Count | Should Be 0
        $hits.ProcessUnknown | Should Be $false
        $hits.TitleLines.Count | Should Be 0
    }
}

Describe 'Get-ExBatteryFacts' {
    # Ticket #21 cost legs: heat = temperature delta (measured); drain = charge counter delta
    # (measured only on battery) with `batterystats` estimates clearly labeled as estimates.
    It 'reads the verbatim real dumpsys battery fixture' {
        $facts = Get-ExBatteryFacts -Battery (Get-ExFixture 'dumpsys-battery.txt')
        $facts.Level | Should Be 100
        $facts.TemperatureDc | Should Be 355
        $facts.VoltageMv | Should Be 4409
        $facts.ChargeCounterUah | Should Be 6183000
        $facts.AcPowered | Should Be 'false'
        $facts.UsbPowered | Should Be 'true'
        $facts.WirelessPowered | Should Be 'false'
        $facts.Status | Should Be 5
    }

    It 'reports $null (not measured) instead of faking zeros on a blank reading' {
        $facts = Get-ExBatteryFacts -Battery @('nothing to see here')
        $facts.Level | Should Be $null
        $facts.TemperatureDc | Should Be $null
        $facts.ChargeCounterUah | Should Be $null
        $facts.UsbPowered | Should Be $null
    }

    It 'keeps only the power-estimate lines and stays a bare array on an empty match' {
        $excerpt = Get-ExPowerEstimateLines -Batterystats @(
            'Estimated power use (mAh):',
            '  Capacity: 6183 mAh',
            '  some unrelated line',
            '  Uid u0a333: 12.5 ( cpu 10.0 )'
        )
        $excerpt.Count | Should Be 3
        $empty = Get-ExPowerEstimateLines -Batterystats @('nothing', 'here')
        $empty.Count | Should Be 0
    }
}

Describe 'Get-ExAppKeepAliveFacts' {
    # Ticket #21 `-AppKeepAlive` mode: tick evidence is the app's own `wake-keep-alive` lines,
    # not the script loop's tick file. Verbatim markers from the 20260923-001402 clean round.
    It 'reads the app keep-alive markers from real round lines' {
        $facts = Get-ExAppKeepAliveFacts -Logcat @(
            '09-23 00:13:30.100 15604 15700 I RearCue : wake-keep-alive start displayId=1 intervalMs=500',
            '09-23 00:13:35.100 15604 15700 I RearCue : wake-keep-alive ok ticks=10 intervalMs=500',
            '09-23 00:13:40.100 15604 15700 I RearCue : wake-keep-alive ok ticks=20 intervalMs=500',
            '09-23 00:13:50.100 15604 15700 I RearCue : wake-keep-alive ok ticks=110 intervalMs=500',
            '09-23 00:15:50.500 15604 15700 I RearCue : wake-keep-alive stop ticks=110 failures=0'
        )
        $facts.Started | Should Be $true
        $facts.Heartbeats | Should Be 3
        $facts.MaxTicks | Should Be 110
        $facts.Fails | Should Be 0
        $facts.StopLine | Should Be '09-23 00:15:50.500 15604 15700 I RearCue : wake-keep-alive stop ticks=110 failures=0'
    }

    It 'counts failures and reports $null (not measured) when no stop line exists' {
        $facts = Get-ExAppKeepAliveFacts -Logcat @(
            'x wake-keep-alive fail consecutive=1 out=user service unavailable',
            'x wake-keep-alive tick-exception (degrade, continue)'
        )
        $facts.Started | Should Be $false
        $facts.Fails | Should Be 2
        $facts.Heartbeats | Should Be 0
        $facts.MaxTicks | Should Be 0
        $facts.StopLine | Should Be $null
    }
}

Describe 'Get-ExTaskMoveAppFacts' {
    # Ticket #22 lock-screen first cast: the attempt verdict word is the app's own ASCII marker.
    # Inputs SYNTHETIC (marker shape pinned from RearDisplayBackend.kt KDoc; no device round has
    # produced a real line yet).
    It 'reads attempt lines and keeps $null for an empty log (SYNTHETIC EDGE STATE -- NOT a fixture)' {
        $facts = Get-ExTaskMoveAppFacts -Logcat @(
            '09-23 01:00:00.000 1 1 I RearCue : task-move create-task exit=0 out=Starting: Intent...',
            '09-23 01:00:00.500 1 1 I RearCue : task-move txn-raw exit=0 out=Result: Parcel(00000000 00000000)',
            '09-23 01:00:01.000 1 1 I RearCue : task-move word=OK taskId=12988 displayId=1 onDisplay=True reason=x'
        )
        $facts.Attempts | Should Be 1
        $facts.LastWord | Should Be 'OK'
        $facts.TaskId | Should Be 12988
        $facts.OnDisplay | Should Be 'True'
        $facts.Creates | Should Be 1
        $facts.TxnRaws | Should Be 1

        $empty = Get-ExTaskMoveAppFacts -Logcat @('nothing here')
        $empty.Attempts | Should Be 0
        $empty.LastWord | Should Be $null
        $empty.TaskId | Should Be $null
    }

    It 'reads the failure words (SYNTHETIC EDGE STATE -- NOT a fixture)' {
        $facts = Get-ExTaskMoveAppFacts -Logcat @(
            'x RearCue : task-move word=NO-TASK taskId=none displayId=1 onDisplay=False reason=y'
        )
        $facts.LastWord | Should Be 'NO-TASK'
        $facts.TaskId | Should Be $null
        $facts.OnDisplay | Should Be 'False'
    }
}

Describe 'Get-ExSignedDeltaSeconds' {
    # One place owns the device-clock rollover rule (was inline at every parse call site).
    It 'returns the plain delta inside one run' {
        $from = [datetime]::ParseExact('09-22 22:47:58.613', 'MM-dd HH:mm:ss.fff', [Globalization.CultureInfo]::InvariantCulture)
        $to = [datetime]::ParseExact('09-22 22:47:59.171', 'MM-dd HH:mm:ss.fff', [Globalization.CultureInfo]::InvariantCulture)
        [math]::Round((Get-ExSignedDeltaSeconds -From $from -To $to), 3) | Should Be 0.558
    }

    It 'folds the delta across midnight into (-43200, 43200]' {
        $from = [datetime]::ParseExact('09-22 23:59:59.500', 'MM-dd HH:mm:ss.fff', [Globalization.CultureInfo]::InvariantCulture)
        $to = [datetime]::ParseExact('09-23 00:00:01.000', 'MM-dd HH:mm:ss.fff', [Globalization.CultureInfo]::InvariantCulture)
        [math]::Round((Get-ExSignedDeltaSeconds -From $from -To $to), 2) | Should Be 1.5
    }

    It 'keeps a slightly-early stamp slightly negative instead of a day long' {
        $from = [datetime]::ParseExact('09-22 22:00:01.000', 'MM-dd HH:mm:ss.fff', [Globalization.CultureInfo]::InvariantCulture)
        $to = [datetime]::ParseExact('09-22 22:00:00.500', 'MM-dd HH:mm:ss.fff', [Globalization.CultureInfo]::InvariantCulture)
        [math]::Round((Get-ExSignedDeltaSeconds -From $from -To $to), 2) | Should Be (-0.5)
    }
}

Describe 'Get-ExSurviveFacts' {
    # E13 (ticket #19): did the Dashboard survive the lock, read from the app's own logcat. The
    # lock is marked straight into the RearCue tag (`adb shell log -t RearCue pc-e13-lock-issued`),
    # so the marker, the racing re-projection and the `Dashboard detach` line share one device
    # clock. Fixtures are VERBATIM slices of real runs (see their headers):
    #   logcat-survive-cleared-returned.txt  -- ticket #18 round: cleared 0.9s after the lock
    #     marker and back on the rear display 6ms later (the lock-window re-projection won the race)
    #   logcat-survive-cleared-no-return.txt -- ticket #11 round: cleared 1.4s after the marker
    #     and never seen again (the same race lost)

    It 'reads the real ticket #18 round: cleared 0.9s after the lock, back 6ms later' {
        $lines = Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'logcat-survive-cleared-returned.txt')
        $facts = Get-ExSurviveFacts -Logcat $lines
        $facts.LockFound | Should Be $true
        $facts.LockAt | Should Be '09-22 18:14:41.852'
        $facts.ReprojectAfterLock | Should Be $true
        $facts.ReprojectSec | Should Be 0.9
        $facts.ClearedAfterLock | Should Be $true
        $facts.ClearedSec | Should Be 0.9
        $facts.ClearedInstances | Should Be 0
        $facts.ReturnedAfterClear | Should Be $true
        $facts.ReturnSec | Should Be 0.9
        $facts.ReturnGapMs | Should Be 6
        $facts.ReturnDisplayId | Should Be 1
        $facts.DetachCountAfterLock | Should Be 1
        $facts.AttachCountAfterLock | Should Be 1
        $facts.EndsAttached | Should Be $true
    }

    It 'reads the real ticket #11 round: cleared 1.4s after the lock marker, no return' {
        $lines = Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'logcat-survive-cleared-no-return.txt')
        $facts = Get-ExSurviveFacts -Logcat $lines
        $facts.LockFound | Should Be $true
        $facts.LockAt | Should Be '09-22 12:59:59.882'
        $facts.ReprojectAfterLock | Should Be $true
        $facts.ClearedAfterLock | Should Be $true
        $facts.ClearedSec | Should Be 1.4
        $facts.ClearedInstances | Should Be 0
        $facts.ReturnedAfterClear | Should Be $false
        ($null -eq $facts.ReturnGapMs) | Should Be $true
        ($null -eq $facts.ReturnDisplayId) | Should Be $true
        $facts.DetachCountAfterLock | Should Be 1
        $facts.AttachCountAfterLock | Should Be 0
        $facts.EndsAttached | Should Be $false
    }

    It 'keeps the post-lock event trail of the real ticket #18 round for the evidence file' {
        $lines = Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'logcat-survive-cleared-returned.txt')
        $facts = Get-ExSurviveFacts -Logcat $lines
        # marker -> two effect lines of the racing re-projection -> detach -> onCreate -> attach;
        # the pre-lock detach/attach pairs of the control phase are not part of the trail
        (($facts.Events | ForEach-Object { $_.Kind }) -join ',') | Should Be 'lock-marker,reproject,reproject,detach,oncreate,attach'
    }

    It 'pins the marker with the pattern parameter when the buffer carries several locks' {
        # SYNTHETIC EDGE STATE -- NOT a fixture: composed only to exercise marker selection and
        # the "pre-marker events are setup" rule; the wire format is the real device format.
        $lines = @(
            '09-22 20:00:00.000  1000  1000 I RearCue : pc-lock-issued',
            '09-22 20:00:02.000  1000  1000 I RearCue : Dashboard detach 0',
            '09-22 20:00:04.000  1000  1000 I RearCue : pc-e13-lock-issued',
            '09-22 20:00:05.500  1000  1000 I RearCue : Dashboard detach 0'
        )
        $facts = Get-ExSurviveFacts -Logcat $lines -MarkerPattern 'pc-e13-lock-issued'
        $facts.LockAt | Should Be '09-22 20:00:04.000'
        $facts.ClearedSec | Should Be 1.5
        $facts.DetachCountAfterLock | Should Be 1
    }

    It 'sees a lock where nothing was cleared and says so' {
        # SYNTHETIC EDGE STATE -- NOT a fixture (see above).
        $lines = @(
            '09-22 20:00:04.000  1000  1000 I RearCue : pc-e13-lock-issued',
            '09-22 20:00:05.000  1000  1000 I RearCue : update iconSet=[com.android.shell]'
        )
        $facts = Get-ExSurviveFacts -Logcat $lines
        $facts.LockFound | Should Be $true
        $facts.ClearedAfterLock | Should Be $false
        $facts.ReturnedAfterClear | Should Be $false
        ($null -eq $facts.ClearedSec) | Should Be $true
        ($null -eq $facts.EndsAttached) | Should Be $true
    }

    It 'does not read a post-lock attach without a detach as a return' {
        # SYNTHETIC EDGE STATE -- NOT a fixture (see above).
        $lines = @(
            '09-22 20:00:04.000  1000  1000 I RearCue : pc-e13-lock-issued',
            '09-22 20:00:05.000  1000  1000 I RearCue : Dashboard attach 1'
        )
        $facts = Get-ExSurviveFacts -Logcat $lines
        $facts.ClearedAfterLock | Should Be $false
        $facts.ReturnedAfterClear | Should Be $false
        $facts.AttachCountAfterLock | Should Be 1
        $facts.EndsAttached | Should Be $true
    }

    It 'reports an unmeasurable lock instead of a cleared one when the marker is missing' {
        # SYNTHETIC EDGE STATE -- NOT a fixture (see above). "Not measured" must not read as
        # "nothing was cleared" -- the same rule as Get-ExDelaySeconds returning null.
        $lines = @('09-22 20:00:05.000  1000  1000 I RearCue : Dashboard detach 0')
        $facts = Get-ExSurviveFacts -Logcat $lines
        $facts.LockFound | Should Be $false
        ($null -eq $facts.ClearedAfterLock) | Should Be $true
        ($null -eq $facts.ReturnedAfterClear) | Should Be $true
        ($null -eq $facts.EndsAttached) | Should Be $true
    }

    It 'rolls over midnight' {
        # SYNTHETIC EDGE STATE -- NOT a fixture (see above).
        $lines = @(
            '09-22 23:59:59.000  1000  1000 I RearCue : pc-e13-lock-issued',
            '09-23 00:00:00.500  1000  1000 I RearCue : Dashboard detach 0'
        )
        (Get-ExSurviveFacts -Logcat $lines).ClearedSec | Should Be 1.5
    }
}

Describe 'Select-ExExternalPollution' {
    # E12/E13 (tickets #16/#19): which flagged pollution lines were NOT our own doing. A
    # power-button transition within +-3s of one of our own device-clock-stamped presses is the
    # script's lock/reset toggle; a fingerprint wake is never ours. What survives this filter is
    # external interference and voids the round (erratum.md).

    It 'keeps every fingerprint wake as external interference, whatever we pressed' {
        $hits = @(
            [pscustomobject]@{ Time = '09-22 20:00:10.200'; Class = 'fingerprint'; Raw = 'x' }
        )
        $presses = @([pscustomobject]@{ Purpose = 'lock'; DeviceTime = '09-22 20:00:10' })
        $external = Select-ExExternalPollution -Hits $hits -PowerPresses $presses
        $external.Count | Should Be 1
        $external[0].Class | Should Be 'fingerprint'
    }

    It 'absolves a power-button transition right next to our own stamped press' {
        $hits = @(
            [pscustomobject]@{ Time = '09-22 20:00:05.100'; Class = 'power-button'; Raw = 'x' }
        )
        $presses = @([pscustomobject]@{ Purpose = 'lock'; DeviceTime = '09-22 20:00:05' })
        (Select-ExExternalPollution -Hits $hits -PowerPresses $presses).Count | Should Be 0
    }

    It 'keeps a power-button transition that is not one of our presses' {
        $hits = @(
            [pscustomobject]@{ Time = '09-22 20:00:30.500'; Class = 'power-button'; Raw = 'x' }
        )
        $presses = @([pscustomobject]@{ Purpose = 'lock'; DeviceTime = '09-22 20:00:05' })
        (Select-ExExternalPollution -Hits $hits -PowerPresses $presses).Count | Should Be 1
    }

    It 'treats a power-button transition with no stamped presses at all as external' {
        $hits = @(
            [pscustomobject]@{ Time = '09-22 20:00:30.500'; Class = 'power-button'; Raw = 'x' }
        )
        (Select-ExExternalPollution -Hits $hits -PowerPresses @()).Count | Should Be 1
    }

    It 'passes a clean round through untouched' {
        (Select-ExExternalPollution -Hits @() -PowerPresses @()).Count | Should Be 0
    }

    It 'returns an empty array, not $null, for a clean round under the bare capture convention' {
        # The `,$arr` seam convention: the function emits ONE array object, so captures stay
        # BARE (`$x = Select-...`), then compose. An `@(...)` wrap around the call double-wraps
        # that one object into a phantom one-element hit -- a real run misread an empty selection
        # as one external hit exactly that way (20260922-222257-lock-survive).
        $external = Select-ExExternalPollution -Hits @() -PowerPresses @()
        ($null -eq $external) | Should Be $false
        $external.Count | Should Be 0
    }

    It 'absolves the wake-key mode flip of the real ticket #19 round' {
        # logcat-e13-wake-flip.txt -- VERBATIM lines of the 2026-09-22 E13 round: the directed
        # rear-display KEYCODE_WAKEUP woke group 1 and HyperOS powered the MAIN group off with a
        # `power_button` line 3ms later. The wake key only ever comes from our own injections, so
        # a power-off within 200ms after a WAKE_REASON_WAKE_KEY event is the key's own mode flip,
        # not a hand on the phone. Composed through the two parsers -- no fabricated lines.
        $lines = Get-Content -Encoding UTF8 (Join-Path $script:Fixtures 'logcat-e13-wake-flip.txt')
        $hits = Get-ExWakePollution -Logcat $lines
        $events = Get-ExPowerGroupEvents -Logcat $lines
        $wakeEvents = @($events | Where-Object { $_.Kind -eq 'wake' })
        $hits.Count | Should Be 1
        $wakeEvents.Count | Should Be 1
        $external = Select-ExExternalPollution -Hits $hits -PowerPresses @() -WakeEvents $wakeEvents
        $external.Count | Should Be 0
    }

    It 'keeps a power-button transition that no wake key just preceded, even with wake events around' {
        # SYNTHETIC EDGE STATE -- NOT a fixture: only pins the boundary of the flip rule (a real
        # power press must stay external); the wire format is the real device format.
        $hits = @([pscustomobject]@{
                Time  = '09-22 22:30:10.500'
                Class = 'power-button'
                Raw   = '09-22 22:30:10.500  5157  5409 I PowerGroup: Powering off display group due to power_button (groupId= 1, uid= 1000)...'
            })
        $wakeEvents = @([pscustomobject]@{
                Time = '09-22 22:30:05.100'; Kind = 'wake'; GroupId = 1; Reason = 'WAKE_REASON_WAKE_KEY'; Details = 'android.policy:KEY'; Raw = 'x'
            })
        (Select-ExExternalPollution -Hits $hits -PowerPresses @() -WakeEvents $wakeEvents).Count | Should Be 1
    }
}
