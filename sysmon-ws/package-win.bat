@echo off
:: ============================================================
:: package-win.bat — sysmon-ws Windows 打包脚本
::
:: 用法（在 sysmon-ws 目录下运行）：
::   package-win.bat [release|debug]
::
:: 前置条件：
::   1. 安装 Rust + cargo
::   2. 安装 Windows 编译目标：
::      rustup target add x86_64-pc-windows-msvc
::   3. 安装 Visual Studio Build Tools（MSVC 工具链）
::      或使用 GNU 工具链：
::      rustup target add x86_64-pc-windows-gnu
::      并安装 mingw-w64
::
:: 输出目录：dist\windows\
:: ============================================================

setlocal enabledelayedexpansion

:: ── 参数处理 ──────────────────────────────────────────────────
set BUILD_MODE=%1
if "%BUILD_MODE%"=="" set BUILD_MODE=release

set TARGET=x86_64-pc-windows-msvc
set DIST_DIR=dist\windows

echo [sysmon-ws] Building for Windows (%BUILD_MODE%)...
echo Target: %TARGET%
echo.

:: ── 生成 icon.ico（如果不存在）────────────────────────────────
if not exist "assets\icon.ico" (
    echo [icon] assets\icon.ico not found, generating default icon...
    if exist "gen_icon.py" (
        python gen_icon.py
        if errorlevel 1 (
            echo [warn] Icon generation failed, will use built-in fallback icon
        )
    ) else (
        echo [warn] gen_icon.py not found, will use built-in fallback icon
    )
)

:: ── 编译 ──────────────────────────────────────────────────────
if "%BUILD_MODE%"=="release" (
    cargo build --release --target %TARGET%
) else (
    cargo build --target %TARGET%
)

if errorlevel 1 (
    echo.
    echo [ERROR] Build failed!
    exit /b 1
)

:: ── 创建输出目录 ──────────────────────────────────────────────
if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"

:: ── 复制可执行文件 ────────────────────────────────────────────
if "%BUILD_MODE%"=="release" (
    set EXE_SRC=target\%TARGET%\release\sysmon-ws.exe
) else (
    set EXE_SRC=target\%TARGET%\debug\sysmon-ws.exe
)

copy /Y "%EXE_SRC%" "%DIST_DIR%\sysmon-ws.exe"
if errorlevel 1 (
    echo [ERROR] Failed to copy executable
    exit /b 1
)

:: ── 复制图标（可选）──────────────────────────────────────────
if exist "assets\icon.ico" (
    copy /Y "assets\icon.ico" "%DIST_DIR%\icon.ico"
    echo [ok] Copied icon.ico
)

:: ── 输出结果 ──────────────────────────────────────────────────
echo.
echo ============================================================
echo  Build complete!
echo  Output: %DIST_DIR%\sysmon-ws.exe
for %%F in ("%DIST_DIR%\sysmon-ws.exe") do echo  Size:   %%~zF bytes
echo ============================================================
echo.
echo  Usage:
echo    %DIST_DIR%\sysmon-ws.exe
echo    WebSocket: ws://localhost:9001
echo ============================================================

endlocal
