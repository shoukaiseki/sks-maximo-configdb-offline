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

set "LAUNCH4J_DIR=launch4j"

REM C# compiler
set "CSC_CMD=%SystemRoot%\Microsoft.NET\Framework\v4.0.30319\csc.exe"
if not exist "!CSC_CMD!" set "CSC_CMD="

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
echo === 3. Generate EXE ===

REM Try Launch4j first, fallback to C# compiler

REM Read JDK path from config.properties and inject into launch4j config
set JDK_PATH=
if exist config.properties (
  for /f "tokens=1,2 delims==" %%a in (config.properties) do (
    if %%a==jdk.home set JDK_PATH=%%b
  )
)
if not "!JDK_PATH!"=="" (
  powershell -NoProfile -Command ^
    "(Get-Content 'launch4j-config.xml' -Raw) -replace '@JDK_HOME@', '!JDK_PATH!' | Set-Content 'launch4j-config.xml'"
) else (
  powershell -NoProfile -Command ^
    "(Get-Content 'launch4j-config.xml' -Raw) -replace '<path>@JDK_HOME@</path>', '' | Set-Content 'launch4j-config.xml'"
)

if exist "%LAUNCH4J_DIR%\launch4jc.exe" (
  "%LAUNCH4J_DIR%\launch4jc.exe" launch4j-config.xml
  if !errorlevel! equ 0 (
    echo EXE generated via Launch4j: sks_configdb.exe
    goto :exe_done
  ) else (
    echo Launch4j CLI FAILED, trying JAR version...
  )
)
if exist "%LAUNCH4J_DIR%\launch4j.jar" (
  "%JAVA_CMD%" -jar "%LAUNCH4J_DIR%\launch4j.jar" launch4j-config.xml
  if !errorlevel! equ 0 (
    echo EXE generated via Launch4j JAR: sks_configdb.exe
    goto :exe_done
  ) else (
    echo Launch4j JAR FAILED, trying C# compiler...
  )
)

if defined CSC_CMD (
  echo Compiling launcher.cs with C# compiler...
  "!CSC_CMD!" /target:winexe /win32icon:images\icon.ico /out:sks_configdb.exe launcher.cs
  if !errorlevel! equ 0 (
    echo EXE generated via C# compiler: sks_configdb.exe
    goto :exe_done
  ) else (
    echo C# compiler FAILED!
  )
) else (
  echo [SKIP] No Launch4j or C# compiler available
  echo        JAR created: build\sks_configdb.jar
)

:exe_done

REM Restore launch4j config placeholder (undo JDK path injection)
powershell -NoProfile -Command ^
  "(Get-Content 'launch4j-config.xml' -Raw) -replace '<path>[^<]+</path>', '<path>@JDK_HOME@</path>' | Set-Content 'launch4j-config.xml'"

echo.
echo ===== PACKAGE COMPLETE =====
if exist "sks_configdb.exe" (
  echo EXE: sks_configdb.exe
) else (
  echo JAR: build\sks_configdb.jar  (EXE skipped - Launch4j not available)
)
echo.

goto :check_dist

REM === 4. Distribution ZIP ====================================
:do_zip
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
goto :end

:check_dist
if /i "%1"=="dist" goto :do_zip

:end
exit /b 0
