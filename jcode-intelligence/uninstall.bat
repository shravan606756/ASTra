@echo off
setlocal EnableDelayedExpansion

echo ------------------------------------------------
echo ASTra CLI Uninstaller
echo ------------------------------------------------
echo.

echo Removing ASTra from your PATH...
set "ASTRA_DIR=%~dp0"
:: Remove trailing backslash if present
if "%ASTRA_DIR:~-1%"=="\" set "ASTRA_DIR=%ASTRA_DIR:~0,-1%"

:: Use PowerShell to safely remove the directory from User PATH
powershell -NoProfile -ExecutionPolicy Bypass -Command "$path = [Environment]::GetEnvironmentVariable('Path', 'User'); $entries = $path -split ';'; $newEntries = $entries | Where-Object { $_ -ne '%ASTRA_DIR%' }; if ($entries.Count -eq $newEntries.Count) { Write-Host '[OK] ASTra was not in your PATH.' } else { $newPath = $newEntries -join ';'; [Environment]::SetEnvironmentVariable('Path', $newPath, 'User'); Write-Host '[SUCCESS] Removed ASTra from your PATH!' }"

echo.
echo ------------------------------------------------
echo Uninstallation Complete!
echo ------------------------------------------------
echo The 'astra' command has been cleanly removed from your PATH.
echo.
echo IMPORTANT: You MUST restart your terminal for the PATH changes to take effect.
echo.
pause
endlocal
