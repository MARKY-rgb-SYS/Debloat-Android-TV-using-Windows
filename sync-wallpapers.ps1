# Copy the photos in "TV Wallpapers" (next to this repo) to the TV's slideshow folder, Pictures/Wallpapers.
# New or changed photos are copied. Photos you delete from the PC folder are removed from the TV too, but
# only ones this script put there; anything you sent to the TV another way is left alone.
#
#   double-click sync-wallpapers.cmd
#   powershell -ExecutionPolicy Bypass -File sync-wallpapers.ps1 [-Folder "D:\Family"] [-TvHost 192.168.x.y]
param(
    [string]$Folder = "",
    [string]$TvHost = ""
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$adbExe = Join-Path $here "platform-tools\adb.exe"
if (-not $Folder) { $Folder = Join-Path (Split-Path -Parent $here) "TV Wallpapers" }
if (-not $TvHost) { $TvHost = (Get-Content (Join-Path $here "tools\.tv_host")).Trim() }
$serial = "$TvHost`:5555"
$tvDir = "/sdcard/Pictures/Wallpapers"
$ledger = Join-Path $Folder ".synced.txt"

function Adb([string[]]$a) { & $adbExe -s $serial @a }
# Single-quote for the TV's shell. (Windows PowerShell drops embedded double quotes when calling adb.)
function Q([string]$s) { "'" + $s.Replace("'", "'\''") + "'" }

& $adbExe connect $serial | Out-Null
if ((& $adbExe -s $serial get-state 2>$null) -ne "device") {
    throw "Can't reach the TV at $serial. Is it on, on this Wi-Fi, with USB debugging on?"
}

$photos = Get-ChildItem -LiteralPath $Folder -File | Where-Object { $_.Extension -match '^\.(jpe?g|png|webp|heic)$' }
Write-Host "PC folder: $Folder ($($photos.Count) photos)"

Adb @("shell", "mkdir -p $tvDir") | Out-Null
# name -> size of what's on the TV now
$onTv = @{}
foreach ($line in (Adb @("shell", "cd $tvDir && stat -c '%s %n' * 2>/dev/null"))) {
    $parts = $line -split ' ', 2
    if ($parts.Count -eq 2 -and $parts[0] -match '^\d+$') { $onTv[$parts[1]] = [long]$parts[0] }
}

$copied = 0
foreach ($p in $photos) {
    if ($onTv.ContainsKey($p.Name) -and $onTv[$p.Name] -eq $p.Length) { continue }
    Write-Host "  copy    $($p.Name)"
    Adb @("push", $p.FullName, "$tvDir/$($p.Name)") | Out-Null
    # Add it to the media library; the launcher can only see photos Android has indexed.
    Adb @("shell", "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d $(Q "file://$tvDir/$($p.Name)")") | Out-Null
    $copied++
}

$removed = 0
$before = if (Test-Path -LiteralPath $ledger) { Get-Content -LiteralPath $ledger } else { @() }
$names = $photos | ForEach-Object { $_.Name }
foreach ($n in $before) {
    if ($n -and ($names -notcontains $n) -and $onTv.ContainsKey($n)) {
        Write-Host "  remove  $n"
        Adb @("shell", "rm -f $(Q "$tvDir/$n")") | Out-Null
        Adb @("shell", "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d $(Q "file://$tvDir/$n")") | Out-Null
        $removed++
    }
}
Set-Content -LiteralPath $ledger -Value $names -Encoding utf8
(Get-Item -LiteralPath $ledger -Force).Attributes += 'Hidden'

# Restart the slideshow so it picks up the changes the next time the home screen shows.
# Nothing on screen changes if you're watching something.
Adb @("shell", "am force-stop com.mihir.tvhome") | Out-Null

Write-Host "`nDone: $copied copied, $removed removed. Press Home on the remote to see them."
