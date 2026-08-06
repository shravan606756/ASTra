@echo off
setlocal

REM Set terminal code page to UTF-8 to prevent '?' character rendering issues
chcp 65001 > nul

REM Enable virtual terminal processing for ANSI escape sequences (colors) in Windows Console
REM Note: Modern Windows Terminal handles this automatically, but this ensures legacy conhost compatibility.
for /f "tokens=*" %%A in ('reg query HKCU\Console /v VirtualTerminalLevel 2^>nul ^| find /i "0x1"') do set VTL_ENABLED=1
if not defined VTL_ENABLED (
    reg add HKCU\Console /v VirtualTerminalLevel /t REG_DWORD /d 1 /f > nul 2>&1
)

REM FUTURE READY LAUNCHER
REM In the future, when the CLI is packaged into a standalone JAR, 
REM simply replace the 'call .\mvnw.cmd' line below with:
REM java -jar target\astra-cli.jar %*

REM Change directory to the location of this script so Maven finds the pom.xml
pushd "%~dp0"

call .\mvnw.cmd -q exec:java -Dexec.mainClass="com.shravan.jcode_intelligence.cli.AstraCli"

popd

endlocal
