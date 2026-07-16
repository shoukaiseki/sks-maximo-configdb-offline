#!/bin/sh

# sks_configdb - Maximo 数据库配置工具 (Linux/Mac 启动脚本)

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

echo "========================================"
echo " sks_configdb - Maximo 数据库配置工具"
echo "========================================"

# ---- 从 config.properties 读取 JDK 路径 ----
JAVA_CMD=""
JDK_HOME=""

if [ -f "$SCRIPT_DIR/config.properties" ]; then
    JDK_HOME=$(grep "^jdk.home" "$SCRIPT_DIR/config.properties" | head -1 | sed 's/^jdk.home[[:space:]]*=[[:space:]]*//' | sed 's/[[:space:]]*$//')
fi

if [ -n "$JDK_HOME" ]; then
    JDK_HOME=$(echo "$JDK_HOME" | sed 's|\\\\|/|g')
    if [ -f "$JDK_HOME/bin/java" ]; then
        JAVA_CMD="$JDK_HOME/bin/java"
        echo "[信息] JDK 路径: $JDK_HOME"
    fi
fi

# 后备：检查 PATH
if [ -z "$JAVA_CMD" ]; then
    if command -v java >/dev/null 2>&1; then
        echo "[信息] 使用系统 PATH 中的 Java"
        JAVA_CMD="java"
    fi
fi

if [ -z "$JAVA_CMD" ]; then
    echo "[错误] 未找到 Java Runtime！"
    echo ""
    echo "请编辑 config.properties，设置 jdk.home 指向你的 JDK 安装路径"
    echo "示例: jdk.home=/usr/lib/jvm/java-17-openjdk"
    exit 1
fi

echo "[信息] Java: $(basename "$JAVA_CMD")"
$JAVA_CMD -version 2>&1

cd "$SCRIPT_DIR"

# 确保 logs 目录存在
mkdir -p logs

# ---- 检查编译产物 ----
if [ ! -f "bin/ConfigDBUI.class" ]; then
    echo ""
    echo "[错误] bin/ConfigDBUI.class 未找到！"
    echo "请先运行 build.sh 编译源码。"
    exit 1
fi

# ---- 启动 UI ----
echo ""
echo "[信息] 启动应用程序..."
$JAVA_CMD -cp "bin:lib/snakeyaml-2.5.jar:maximo/lib/db2jcc.jar:maximo/lib/db2jcc_license_cu.jar:maximo/lib/icu4j.jar" ConfigDBUI
