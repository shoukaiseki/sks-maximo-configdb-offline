@echo off
title sks_configdb - build
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
cd /d "%SCRIPT_DIR%"

set JDK_HOME=

if exist config.properties (
  for /f "tokens=1,2 delims==" %%a in (config.properties) do (
    if %%a==jdk.home set JDK_HOME=%%b
  )
)

set JAVAC_CMD=javac
if not "%JDK_HOME%"=="" (
  if exist "%JDK_HOME%\bin\javac.exe" set JAVAC_CMD=%JDK_HOME%\bin\javac.exe
)

echo Compiling src\ConfigDBUI.java to bin\ ...

if not exist bin mkdir bin

"%JAVAC_CMD%" -encoding UTF-8 -cp "lib\snakeyaml-2.5.jar" -d "bin" "src\ConfigDBUI.java"
if !errorlevel! neq 0 (
  echo.
  echo Build FAILED!
  pause
  exit /b 1
)

echo Build successful - bin\ConfigDBUI.class
