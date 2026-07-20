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
├── javax.mail-api-1.6.2.jar # JavaMail API
├── log4j-core-2.25.3.jar    # Apache Log4j 核心库```

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

### 1. 编译 Java 源码

```bash
build.bat          # Windows
# 或
build.sh           # Linux/Mac
```

### 2. 启动

**方式一：直接双击 exe（推荐）**

`sks_configdb.exe` 是 Windows 原生可执行文件，使用 C# 编写（源码 [launcher.cs](launcher.cs)），读取 `config.properties` 中的 JDK 路径后自动启动 Java UI。可直接双击运行，无需打开命令行。

**方式二：批处理启动**

```bash
run.bat            # Windows
# 或
run.sh             # Linux/Mac
```

## 使用 Launch4j 打包 EXE

[Launch4j](https://launch4j.sourceforge.net/) 是一款将 JAR 包装为 Windows EXE 的工具，生成的 exe 文件更小、启动更快。

### 安装 Launch4j

从 [SourceForge](https://sourceforge.net/projects/launch4j/files/launch4j-3/3.50/) 下载 `launch4j-3.50-win32.exe` 并安装。

### 打包步骤

```bash
# 1. 先编译 Java 类
build.bat

# 2. 创建 JAR 包
jar cfm build\sks_configdb.jar launch4j-manifest.mf -C bin .

# 3. 用 Launch4j 生成 EXE
#    方式 A：双击 launch4j.exe → 打开 launch4j-config.xml → 点击齿轮图标（Build）
#    方式 B：命令行
java -jar "C:\Program Files (x86)\Launch4j\launch4j.jar" launch4j-config.xml
```

执行成功后，`sks_configdb.exe` 会更新为 Launch4j 打包的版本，双击即可启动。

> [launch4j-config.xml](launch4j-config.xml) 已预配置了 classpath 和 JRE 选项（要求 JDK 17+），可直接使用。
