# sks_configdb - Maximo 数据库配置工具

Maximo ConfigDB / RestoreFromBackup GUI 工具，支持多环境配置切换和常用数据库操作。

## 前提条件

- JDK 17+
- 以下 JAR 文件需从 Maximo 安装目录复制到本项目的指定位置

## 依赖 JAR 清单

以下 JAR 文件来自 Maximo 安装包，非公开，需手动复制：

### 从 Maximo 安装目录复制

将 Maximo 安装目录下的 `applications/maximo/businessobjects.jar` 复制到：

```
maximo/
└── businessobjects.jar
```

将 Maximo 安装目录下的 `applications/maximo/lib/` 中的以下 JAR 复制到：

```
maximo/lib/
├── db2jcc.jar               # DB2 JDBC 驱动
├── db2jcc_license_cu.jar    # DB2 JDBC 许可
├── icu4j.jar                # Unicode/国际化支持
├── j2ee.jar                 # Java EE API
└── javax.mail-api-1.6.2.jar # JavaMail API
```

### 其他依赖

```
lib/
└── snakeyaml-2.5.jar        # YAML 解析（已包含在仓库中）
```

## JDK 配置

在 `config.properties` 中设置 JDK 路径：

```properties
jdk.home=D:/usr/java/jdk-17.0.19x64
```

路径格式：
- Windows 使用盘符加正斜杠，如 `D:/java/jdk-17`
- Linux/Mac 使用绝对路径，如 `/usr/lib/jvm/java-17-openjdk`
- 路径中不要包含 `bin/`，工具会自动拼接 `bin/java`（Windows 下为 `bin/java.exe`）

> `config.properties` 包含本地路径信息，建议添加到 `.gitignore`。

## 环境配置

复制 `config/app-example.yml` 为 `config/app.yml`，然后编辑数据库连接信息：

```bash
cp config/app-example.yml config/app.yml   # Linux/Mac
copy config\app-example.yml config\app.yml # Windows
```

`app.yml` 中的示例环境：

| 环境 | 描述 | 用途 |
|------|------|------|
| `local` | 本机 | 连接本地数据库 |
| `hd` | hd | 远程数据库 |
| `dev` | dev环境 | 开发环境 |

> `app.yml` 包含数据库密码等敏感信息，已被 `.gitignore` 忽略，不会提交到仓库。

## 快速开始

```bash
# 1. 编译
build.bat          # Windows
# 或
build.sh           # Linux/Mac

# 2. 运行
run.bat            # Windows
# 或
run.sh             # Linux/Mac
```
