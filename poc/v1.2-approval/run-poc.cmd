@echo off
rem Goms V1.2 PoC launcher. All arguments are passed to run-poc.ps1 (e.g. run-poc.cmd -Mode deny)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-poc.ps1" %*
