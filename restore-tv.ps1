# Put the TV back the way it was before the debloat.
# Re-enables every package that was enabled in the backup, brings back the Google TV launcher and its
# setup app, makes the stock launcher the default home again and (with -RemoveLauncher) uninstalls tv-home.
#
#   powershell -ExecutionPolicy Bypass -File restore-tv.ps1
#   powershell -ExecutionPolicy Bypass -File restore-tv.ps1 -RemoveLauncher
#   powershell -ExecutionPolicy Bypass -File restore-tv.ps1 -TvHost 192.168.x.y -Backup backup\<date-time>
param(
    [string]$TvHost = "",
    [string]$Backup = "",
    [switch]$RemoveLauncher
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$adbExe = Join-Path $here "platform-tools\adb.exe"
if (-not $TvHost) { $TvHost = (Get-Content (Join-Path $here "tools\.tv_host")).Trim() }
if (-not $Backup) { $Backup = (Get-ChildItem (Join-Path $here "backup") -Directory | Sort-Object Name | Select-Object -First 1).FullName }
$serial = "$TvHost`:5555"
$stockHome = "com.google.android.apps.tv.launcherx/.home.HomeActivity"

function Adb([string[]]$a) { & $adbExe -s $serial @a }

& $adbExe connect $serial | Out-Host
if ((& $adbExe -s $serial get-state 2>$null) -ne "device") {
    throw "Can't reach the TV at $serial. Is it on, on this Wi-Fi, with USB debugging on? Accept the prompt on the TV if one appears."
}
Write-Host "Restoring from $Backup"

# Everything that was enabled before, and is disabled now, gets enabled again.
$wasEnabled = Get-Content (Join-Path $Backup "packages-enabled.txt") | ForEach-Object { $_ -replace '^package:', '' } | Where-Object { $_ }
$nowDisabled = Adb @("shell", "pm", "list", "packages", "-d") | ForEach-Object { ($_ -replace '^package:', '').Trim() } | Where-Object { $_ }
$toEnable = $nowDisabled | Where-Object { $wasEnabled -contains $_ }
# Always bring these back, even if the backup is missing them.
$toEnable = @($toEnable) + @("com.google.android.tungsten.setupwraith", ($stockHome -split '/')[0]) | Select-Object -Unique
foreach ($p in $toEnable) {
    # One pm call per adb call: chained pm commands can stop after the first.
    Write-Host ("enable  {0,-46} {1}" -f $p, ((Adb @("shell", "pm", "enable", $p)) -join ' '))
}

Adb @("shell", "cmd", "package", "set-home-activity", "--user", "0", $stockHome) | Out-Host
# Screensaver back to the TV's default (Google's Ambient mode).
Adb @("shell", "settings", "delete", "secure", "screensaver_components") | Out-Null
if ($RemoveLauncher) { Adb @("uninstall", "com.mihir.tvhome") | Out-Host }
Adb @("shell", "am", "force-stop", "com.android.tv.settings") | Out-Null
Adb @("shell", "input", "keyevent", "KEYCODE_HOME") | Out-Null

$left = Adb @("shell", "pm", "list", "packages", "-d") | ForEach-Object { ($_ -replace '^package:', '').Trim() } | Where-Object { $_ }
Write-Host "`nStill disabled (these were already disabled before the debloat):"
$left | ForEach-Object { Write-Host "  $_" }
Write-Host "`nHome is now: $((Adb @('shell','cmd','package','resolve-activity','--brief','-a','android.intent.action.MAIN','-c','android.intent.category.HOME') | Select-Object -Last 1))"
Write-Host "Done. Press Home on the remote."
