@echo off
setlocal EnableDelayedExpansion

echo ------------------------------------------------
echo ASTra CLI Installer
echo ------------------------------------------------
echo.

echo [1/3] Checking for Java...
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Java is not installed or not in your PATH.
    echo Please install Java 21 or newer and try again.
    pause
    exit /b 1
)
echo [OK] Java is installed.
echo.

echo [2/3] Checking for Maven Wrapper...
if not exist "%~dp0mvnw.cmd" (
    echo [ERROR] mvnw.cmd not found in %~dp0
    echo Please run this script from the ASTra project root.
    pause
    exit /b 1
)
echo [OK] Maven Wrapper found.
echo.

echo [3/3] Registering ASTra in your PATH...
set "ASTRA_DIR=%~dp0"
:: Remove trailing backslash if present
if "%ASTRA_DIR:~-1%"=="\" set "ASTRA_DIR=%ASTRA_DIR:~0,-1%"

:: Use PowerShell to safely update the User PATH environment variable
powershell -NoProfile -ExecutionPolicy Bypass -Command "$path = [Environment]::GetEnvironmentVariable('Path', 'User'); if ($path -split ';' -contains '%ASTRA_DIR%') { Write-Host '[OK] ASTra is already in your PATH.' } else { $newPath = $path + ';%ASTRA_DIR%'; [Environment]::SetEnvironmentVariable('Path', $newPath, 'User'); Write-Host '[SUCCESS] Added ASTra to your PATH!' }"

echo.
echo ------------------------------------------------
echo Installation Complete! 🐇
echo ------------------------------------------------
echo You can now use the 'astra' command from anywhere.
echo.
echo IMPORTANT: You MUST restart your terminal for the new PATH to take effect.
echo.
pause
endlocal
