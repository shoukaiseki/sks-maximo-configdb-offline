@echo off
title sks_configdb
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
cd /d "%SCRIPT_DIR%"

if not exist "logs" mkdir logs

set JAVA_CMD=
set JDK_HOME=

if exist config.properties (
  for /f "tokens=1,2 delims==" %%a in (config.properties) do (
    if %%a==jdk.home set JDK_HOME=%%b
  )
)

if not "%JDK_HOME%"=="" (
  if exist "%JDK_HOME%\bin\java.exe" set JAVA_CMD=%JDK_HOME%\bin\java.exe
)

if "%JAVA_CMD%"=="" (
  where java >nul 2>nul
  if !errorlevel! equ 0 set JAVA_CMD=java.exe
)

if "%JAVA_CMD%"=="" (
  echo ERROR: Java not found. Edit config.properties
  exit /b 1
)

echo Java: %JAVA_CMD%

if not exist "bin\ConfigDBUI.class" (
  echo.
  echo ERROR: bin\ConfigDBUI.class not found.
  echo Please run build.bat first to compile the source.
  echo.
  exit /b 1
)

echo Starting ...

REM Build classpath - auto scan maximo\lib\*.jar
set CLASSPATH=bin;maximo\properties;maximo\resources;maximo\businessobjects.jar;lib\snakeyaml-2.5.jar
for %%j in (maximo\lib\*.jar) do set CLASSPATH=!CLASSPATH!;%%j

"%JAVA_CMD%" -cp "!CLASSPATH!" ConfigDBUI
exit /b 0
