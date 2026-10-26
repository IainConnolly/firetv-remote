# End-to-end test on a real Fire TV: drives the app through its own screens the
# way the remote would, and checks what comes to the front.
#
#   scripts\e2e.ps1 [-Device 192.168.0.158:5555] [-Adb path\to\adb.exe]
#
# Needs the app installed with keep-on granted, and Netflix, VLC and Plex
# installed. It adds its own mappings and deletes them again; mappings already
# on the device are left alone, but avoid ones that use those three apps.
#
# A remote button is stood in for by `am start` of the app it opens. Nothing
# that needs a key press is tested here: on an unrooted Fire TV, keys sent with
# `input keyevent` never reach an accessibility service, and writing to the
# remote's input device is refused by SELinux. Those checks are in the README's
# manual list.
param(
    [string]$Device = '192.168.0.158:5555',
    [string]$Adb = (Join-Path $PSScriptRoot '..\..\..\tools\android-sdk\platform-tools\adb.exe')
)
$ErrorActionPreference = 'Stop'

$app = 'com.firetvremote'
$netflix = 'com.netflix.ninja'
$vlc = 'org.videolan.vlc'
$plex = 'com.plexapp.android'
$script:failures = 0

function Shell([string]$cmd) { & $Adb -s $Device shell $cmd }

# Reading the screen with uiautomator suppresses every other accessibility
# service while it runs, so the remap service drops and reconnects after each
# read. Wait for it before anything that needs it, and never read the screen
# while it is listening for a button.
function Bound { (Shell 'dumpsys accessibility' | Select-String 'Bound services' | Out-String) -match 'Remote Remap' }

function WaitService([int]$seconds = 10) {
    Start-Sleep -Milliseconds 500
    $deadline = (Get-Date).AddSeconds($seconds)
    do { if (Bound) { Start-Sleep -Milliseconds 500; return $true }; Start-Sleep -Milliseconds 500 } while ((Get-Date) -lt $deadline)
    return $false
}

function Screen {
    # A failed dump leaves the previous file in place, so remove it first. Dumps
    # fail for a while after an app that opens through an invisible trampoline
    # activity (VLC does), so keep trying for up to about half a minute.
    for ($i = 0; $i -lt 12; $i++) {
        $xml = (Shell 'rm -f /sdcard/e2e.xml; uiautomator dump /sdcard/e2e.xml >/dev/null 2>&1; cat /sdcard/e2e.xml 2>/dev/null') -join ''
        if ($xml -match '<hierarchy') { return $xml }
        Start-Sleep 1
    }
    throw 'uiautomator could not read the screen'
}

function Texts { [regex]::Matches((Screen), 'text="([^"]+)"') | ForEach-Object { [Net.WebUtility]::HtmlDecode($_.Groups[1].Value) } }
function Shows([string]$pattern) { [bool]((Texts) | Where-Object { $_ -match $pattern }) }

function Find([string]$text) {
    $m = [regex]::Match((Screen), "text=`"$([regex]::Escape([Net.WebUtility]::HtmlEncode($text)))`"[^>]*?bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"")
    if (-not $m.Success) { return $null }
    $g = $m.Groups
    return @{ X = ([int]$g[1].Value + [int]$g[3].Value) / 2; Y = ([int]$g[2].Value + [int]$g[4].Value) / 2 }
}

function Tap([string]$text, [int]$scroll = 0) {
    $at = Find $text
    # Each screen read is slow, so scroll a few rows between reads.
    for ($i = 0; -not $at -and $i -lt $scroll; $i += 6) {
        Shell ((1..6 | ForEach-Object { 'input keyevent KEYCODE_DPAD_DOWN' }) -join '; ')
        $at = Find $text
    }
    if (-not $at) { throw "nothing on screen says '$text'" }
    if (-not (WaitService)) { throw 'the remap service did not come back after reading the screen' }
    Shell "input tap $($at.X) $($at.Y)"
    Start-Sleep -Milliseconds 1500
}

function Front {
    $line = Shell 'dumpsys activity activities' | Select-String 'mResumedActivity' | Select-Object -First 1
    if ($line -match ' u0 ([^/ ]+)/') { return $Matches[1] }
}

function WaitFront([string]$pkg, [int]$seconds = 8) {
    $deadline = (Get-Date).AddSeconds($seconds)
    do { $now = Front; if ($now -eq $pkg) { return $now }; Start-Sleep -Milliseconds 500 } while ((Get-Date) -lt $deadline)
    return $now
}

function Check([string]$name, [bool]$ok, [string]$detail = '') {
    if ($ok) { Write-Host "PASS  $name" -ForegroundColor Green }
    else { $script:failures++; Write-Host "FAIL  $name  $detail" -ForegroundColor Red }
}

function Component([string]$pkg) {
    $c = Shell "cmd package resolve-activity --brief -c android.intent.category.LEANBACK_LAUNCHER -a android.intent.action.MAIN $pkg" | Select-Object -Last 1
    if ($c -notmatch '/') { throw "$pkg has no TV launcher activity" }
    return $c.Trim()
}
$components = @{}
function Open([string]$pkg) { Shell "am start -n $($components[$pkg])" | Out-Null }

function Home { Shell 'input keyevent KEYCODE_HOME'; Start-Sleep 2 }
function StopOthers { foreach ($p in $netflix, $vlc, $plex) { Shell "am force-stop $p" } }
function OpenMain { Shell "am start -n $app/.MainActivity" | Out-Null; Start-Sleep 2 }

function NewMapping { OpenMain; Tap 'Add mapping'; Tap 'Detect button' }

function ChooseTarget([string]$label) {
    Tap 'Choose app'
    Tap $label 40
    Check "the mapping opens $label" (Shows "^Opens: $label$")
}

function DeleteMapping([string]$summary) {
    OpenMain
    if (Find $summary) { Tap $summary; Tap 'Delete this mapping' }
}

# --- preconditions
foreach ($p in $netflix, $vlc, $plex) { $components[$p] = Component $p }
OpenMain
$ok = WaitService 15
Check 'the remap service is bound' $ok 'turn it on (open the app with keep-on granted) and run again'
if (-not $ok) { exit 1 }
StopOthers
$created = 'Netflix button  →  VLC', 'VLC button  →  Netflix', 'Plex button  →  Netflix'

# Detect, then the button's app opening; the edit screen comes back once it has
# learned the app (from 1 s to 10 s, depending on how fast that app starts).
function LearnApp([string]$pkg) {
    NewMapping
    Open $pkg
    WaitFront $pkg 10 | Out-Null
    return WaitFront $app 15
}

try {
    # 1. Detect learns the app a button opens, and brings the edit screen back.
    $now = LearnApp $netflix
    Check 'the edit screen comes back after the app opened' ($now -eq $app) "front: $now"
    Check 'it learned the app' (Shows 'App it normally opens: Netflix') ((Texts) -join ' | ')
    Check 'it says it got it' (Shows '^Got it\.$')

    # 2. The app to open instead can't be the one the button already opens.
    Tap 'Choose app'
    Tap 'Netflix' 40
    Check 'the button''s own app is refused as its target' (Shows '^No app chosen yet\.$')
    ChooseTarget 'VLC'

    # 3. The system opening Netflix is replaced by VLC.
    Home
    WaitService | Out-Null
    Open $netflix
    $now = WaitFront $vlc
    Check 'Netflix opened without Select is replaced by VLC' ($now -eq $vlc) "front: $now"
    StopOthers

    # 4. Two mappings pointing at each other don't bounce: VLC -> Netflix as well.
    $now = LearnApp $vlc
    $t = Texts
    Check 'a second mapping learned VLC' ($now -eq $app -and [bool]($t | Where-Object { $_ -match 'App it normally opens: VLC' })) "front: $now; screen: $($t -join ' | ')"
    ChooseTarget 'Netflix'
    Home
    WaitService | Out-Null
    Open $netflix
    $first = WaitFront $vlc
    Start-Sleep 6
    $settled = Front
    Check 'mappings that point at each other redirect once and stop' ($first -eq $vlc -and $settled -eq $vlc) "first: $first, after 6 s: $settled"
    Home
    StopOthers

    # 5. Detecting again replaces the button: the VLC mapping becomes Plex's.
    OpenMain
    Tap 'VLC button  →  Netflix'
    Tap 'Detect button'
    Open $plex
    WaitFront $plex 10 | Out-Null
    $now = WaitFront $app 15
    Check 'detecting again replaces the button' ($now -eq $app -and (Shows 'App it normally opens: Plex')) "front: $now"
    Home
    WaitService | Out-Null
    Open $vlc
    Start-Sleep 4
    $now = Front
    Check 'the old button no longer redirects' ($now -eq $vlc) "front: $now"
    StopOthers

    # 6. Detect with nothing pressed gives up after 15 seconds and keeps the old button.
    OpenMain
    Tap 'Plex button  →  Netflix'
    Tap 'Detect button'
    Start-Sleep 17
    $t = Texts
    Check 'Detect with no button times out' ($t -contains 'No button detected. Choose Detect button and try again.')
    Check 'it keeps the old button' ([bool]($t | Where-Object { $_ -match 'App it normally opens: Plex' })) ($t -join ' | ')

    # 7. Backing out of a new mapping leaves no empty entry.
    OpenMain
    $before = @(Texts | Where-Object { $_ -match '^New mapping' }).Count
    Tap 'Add mapping'
    Shell 'input keyevent KEYCODE_BACK'
    Start-Sleep 2
    $after = @(Texts | Where-Object { $_ -match '^New mapping' }).Count
    Check 'backing out of Add mapping leaves nothing behind' ($after -eq $before) "unfinished mappings before: $before, after: $after"

    # 8. A force-stopped service is brought back when the app is opened.
    Home
    Shell "am force-stop $app"
    Start-Sleep 2
    $down = -not (Bound)
    OpenMain
    $back = WaitService 15
    Check 'a force-stopped service comes back when the app opens' ($down -and $back) "stopped: $down, back: $back"
} finally {
    # Remove what this test made.
    if ((& $Adb -s $Device get-state 2>$null) -ne 'device') {
        Check 'the device is still connected for cleanup' $false 'remove the test mappings by hand'
        exit 1
    }
    StopOthers
    foreach ($m in $created) { DeleteMapping $m }
    OpenMain
    $left = Texts
    Check 'the test mappings are gone' (-not ($left | Where-Object { $created -contains $_ }))
    Home
    Shell 'rm -f /sdcard/e2e.xml'
}

Write-Host ''
if ($script:failures) { Write-Host "$($script:failures) check(s) failed" -ForegroundColor Red; exit 1 }
Write-Host 'All checks passed' -ForegroundColor Green
