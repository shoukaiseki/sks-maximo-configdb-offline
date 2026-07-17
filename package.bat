@echo off
title sks_configdb - package
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

set JAVA_CMD=java.exe
set JAVAC_CMD=javac.exe
set JAR_CMD=jar.exe
set JPACKAGE_CMD=jpackage.exe
if not "%JDK_HOME%"=="" (
  if exist "%JDK_HOME%\bin\java.exe" set JAVA_CMD=%JDK_HOME%\bin\java.exe
  if exist "%JDK_HOME%\bin\javac.exe" set JAVAC_CMD=%JDK_HOME%\bin\javac.exe
  if exist "%JDK_HOME%\bin\jar.exe" set JAR_CMD=%JDK_HOME%\bin\jar.exe
  if exist "%JDK_HOME%\bin\jpackage.exe" set JPACKAGE_CMD=%JDK_HOME%\bin\jpackage.exe
)

REM Clean build directory
if exist build rmdir /s /q build >nul 2>nul

echo.
echo === 1. Compile source ===
if not exist bin mkdir bin
"%JAVAC_CMD%" -encoding UTF-8 -cp "lib\snakeyaml-2.5.jar" -d "bin" "src\ConfigDBUI.java"
if !errorlevel! neq 0 (
  echo Compile FAILED!
  pause
  exit /b 1
)

echo.
echo === 2. Create JAR ===
mkdir build\input
mkdir build\input\lib

echo Main-Class: ConfigDBUI> build\MANIFEST.MF
echo Class-Path: lib/snakeyaml-2.5.jar lib/db2jcc.jar lib/db2jcc_license_cu.jar lib/icu4j.jar>> build\MANIFEST.MF

"%JAR_CMD%" cfm build\input\sks_configdb.jar build\MANIFEST.MF -C bin .
if !errorlevel! neq 0 (
  echo Create JAR FAILED!
  pause
  exit /b 1
)

echo.
echo === 3. Copy dependency JARs ===
copy /Y lib\snakeyaml-2.5.jar build\input\lib\ >nul
copy /Y maximo\lib\db2jcc.jar build\input\lib\ >nul
copy /Y maximo\lib\db2jcc_license_cu.jar build\input\lib\ >nul
copy /Y maximo\lib\icu4j.jar build\input\lib\ >nul

echo.
echo === 4. Generate app image (jpackage) ===

"%JPACKAGE_CMD%" ^
  --type app-image ^
  --input build\input ^
  --main-jar sks_configdb.jar ^
  --main-class ConfigDBUI ^
  --name sks_configdb ^
  --dest build\output

if !errorlevel! equ 0 (
  echo.
  echo ===== SUCCESS =====
  echo App image created at: build\output\sks_configdb\
  echo Portable EXE: build\output\sks_configdb\sks_configdb.exe
  echo.
  echo To distribute, zip the "build\output\sks_configdb" folder.
  echo.
  echo Run with: build\output\sks_configdb\sks_configdb.exe
  echo.
) else (
  echo.
  echo Package FAILED!
)

pause
