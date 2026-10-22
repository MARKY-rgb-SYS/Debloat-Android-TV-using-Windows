@echo off
rem Double-click to copy the photos in "TV Wallpapers" to the TV's slideshow.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0sync-wallpapers.ps1" %*
pause
