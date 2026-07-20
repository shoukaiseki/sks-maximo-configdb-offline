@echo off
title sks_configdb - package
setlocal enabledelayedexpansion

cd /d "%~dp0"

set JDK_HOME=
if exist config.properties (
  for /f "tokens=1,2 delims==" %%a in (config.properties) do (
    if %%a==jdk.home set JDK_HOME=%%b
  )
)

set JAVAC_CMD=javac
set JAVA_CMD=java
set JAR_CMD=jar
if not "%JDK_HOME%"=="" (
  if exist "%JDK_HOME%\bin\javac.exe" set JAVAC_CMD=%JDK_HOME%\bin\javac.exe
  if exist "%JDK_HOME%\bin\java.exe" set JAVA_CMD=%JDK_HOME%\bin\java.exe
  if exist "%JDK_HOME%\bin\jar.exe" set JAR_CMD=%JDK_HOME%\bin\jar.exe
)

set "LAUNCH4J=C:\Program Files (x86)\Launch4j\launch4j.jar"

echo.
echo === 1. Compile Java source ===
if not exist bin mkdir bin
"%JAVAC_CMD%" -encoding UTF-8 -cp "lib\snakeyaml-2.5.jar" -d "bin" "src\ConfigDBUI.java"
if !errorlevel! neq 0 (
  echo Compile FAILED!
  pause
  exit /b 1
)

echo.
echo === 2. Create JAR ===
if not exist build mkdir build
"%JAR_CMD%" cfm build\sks_configdb.jar launch4j-manifest.mf -C bin .
if !errorlevel! neq 0 (
  echo Create JAR FAILED!
  pause
  exit /b 1
)

echo.
echo === 3. Generate EXE (Launch4j) ===
if exist "%LAUNCH4J%" (
  "%JAVA_CMD%" -jar "%LAUNCH4J%" launch4j-config.xml
  if !errorlevel! equ 0 (
    echo EXE generated: sks_configdb.exe
  ) else (
    echo Launch4j FAILED!
  )
) else (
  echo [SKIP] Launch4j not installed at "%LAUNCH4J%"
  echo        JAR created: build\sks_configdb.jar
  echo        To generate EXE, install Launch4j or use: csc /target:winexe /win32icon:images\icon.ico /out:sks_configdb.exe launcher.cs
)

echo.
echo ===== PACKAGE COMPLETE =====
if exist "sks_configdb.exe" (
  echo EXE: sks_configdb.exe
) else (
  echo JAR: build\sks_configdb.jar  (EXE skipped - Launch4j not available)
)
echo.

REM === 4. Distribution ZIP (package.bat dist) ===
if /i "%1"=="dist" (
  echo === 4. Create distribution ZIP ===
  set "ZIP_FILE=build\sks_configdb.zip"
  if exist "!ZIP_FILE!" del "!ZIP_FILE!"

  REM git archive only includes tracked files (ignores .gitignore entries)
  powershell -NoProfile -Command ^
    "$src='%~dp0'; ^
     $src=$src.TrimEnd('\'); ^
     $zip=Join-Path $src 'build\sks_configdb.zip'; ^
     Write-Host '  Packing source files (git tracked)...'; ^
     git -C $src archive --format=zip -o $zip HEAD; ^
     Write-Host '  Adding EXE...'; ^
     Compress-Archive -Update -Path (Join-Path $src 'sks_configdb.exe') -DestinationPath $zip; ^
     Write-Host '  ZIP created:' $zip; ^
     $len=(Get-Item $zip).Length; ^
     Write-Host ('  Size: {0:N0} KB' -f ($len / 1KB))"
  if !errorlevel! equ 0 (
    echo ===== DIST COMPLETE =====
  ) else (
    echo Create ZIP FAILED!
  )
)

pause
exit /b 0
