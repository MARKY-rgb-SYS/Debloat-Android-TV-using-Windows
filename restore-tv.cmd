@echo off
rem Double-click to undo the debloat and bring back the Google TV home screen.
rem Add -RemoveLauncher to also uninstall the tv-home launcher.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0restore-tv.ps1" %*
pause
