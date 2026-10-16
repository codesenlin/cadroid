@echo off
set CORE_PATH=%CD%\..\ca
set SHELL_PATH=%CD%
cd /d %CORE_PATH%
cabuild shellPrepareDebug
echo Package has been prepared for debugging.
cd /d %SHELL_PATH%