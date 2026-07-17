/*
 * sks_configdb - Maximo 数据库配置工具
 * 
 * 直接调用 Java 进程执行 psdi.configure.ConfigDB 和
 * psdi.configure.RestoreFromBackup，无需 Shell 脚本。
 * 支持数据库 SQL 操作（JDBC 直连）。
 *
 * 使用方式:
 *   1. 编辑 config.properties 设置 jdk.home
 *   2. 编译: javac -encoding UTF-8 ConfigDBUI.java
 *   3. 运行: java ConfigDBUI
 *   4. 或双击 run.bat (Windows) / ./run.sh (Linux)
 */

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.sql.*;

import org.yaml.snakeyaml.Yaml;

public class ConfigDBUI {

    // 颜色
    private static final Color COLOR_BG         = new Color(30, 30, 30);
    private static final Color COLOR_FG         = new Color(212, 212, 212);
    private static final Color COLOR_STDOUT     = new Color(212, 212, 212);
    private static final Color COLOR_STDERR     = new Color(244, 135, 113);
    private static final Color COLOR_INFO       = new Color(108, 182, 255);
    private static final Color COLOR_SUCCESS    = new Color(126, 203, 161);
    private static final Color COLOR_START      = new Color(108, 182, 255);
    private static final Color COLOR_STATUS_IDLE   = new Color(158, 158, 158);
    private static final Color COLOR_STATUS_RUN    = new Color(26, 115, 232);
    private static final Color COLOR_STATUS_OK     = new Color(52, 168, 83);
    private static final Color COLOR_STATUS_ERR    = new Color(234, 67, 53);
    private static final Color COLOR_WARN          = new Color(251, 188, 4);

    // 按钮配色
    private static final Color COLOR_DB   = new Color(147, 101, 184);  // 紫色 - 数据库操作
    private static final Color COLOR_DB2  = new Color(122, 134, 202);  // 蓝紫色

    private static final Font LOG_FONT = new Font("Consolas", Font.PLAIN, 13);
    private static final Font BTN_FONT = new Font("Microsoft YaHei", Font.BOLD, 13);

    // UI 组件
    private JFrame frame;
    private JTextPane logPaneCfg = new JTextPane();
    private StyledDocument docCfg = logPaneCfg.getStyledDocument();
    private JTextPane logPaneSql = new JTextPane();
    private StyledDocument docSql = logPaneSql.getStyledDocument();
    private JTabbedPane logTabs;
    private JLabel statusLabel;
    private JLabel statusDot;
    private JLabel infoLabel;
    private JButton btnConfigDB;
    private JButton btnRestore;
    private JButton btnStop;
    private JButton btnClear;
    private JButton btnClearSession;
    private JButton btnSetConfiguring;
    private JButton btnSetConfirmLabTrans;
    private JButton btnSetAdminRestart;
    private JButton btnQueryVars;
    private JLabel lineCountLabel;
    private JLabel taskStatusLabel;

    // 状态
    private volatile Process currentProcess;
    private volatile boolean running = false;
    private int cfgLineCount = 0;
    private int sqlLineCount = 0;

    // 配置
    private File sksRoot;
    private String javaCmd;
    private String classPath;
    private String propDir;

    // 环境切换
    private String currentEnvName;
    private String defaultEnv;
    private Map<String, String> globalProps = new LinkedHashMap<>();
    private Map<String, EnvConfig> environments = new LinkedHashMap<>();
    private JButton btnSwitchEnv;

    private static class EnvConfig {
        String desc;
        Map<String, String> props = new LinkedHashMap<>();
    }

    // ============================================================
    // 入口
    // ============================================================
    public static void main(String[] args) {
        // 检查是否以 SQL 模式启动
        String sqlArg = null;
        String sksRootArg = null;
        for (String a : args) {
            if (a.startsWith("--sql=")) sqlArg = a.substring(6);
            if (a.startsWith("--sks-root=")) sksRootArg = a.substring(11);
        }
        if (sqlArg != null) {
            headlessRunSql(sqlArg, sksRootArg);
            return;
        }

        // 正常 UI 模式
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> {
            ConfigDBUI app = new ConfigDBUI();
            if (app.init()) app.show();
        });
    }

    // ============================================================
    // 清除 SERVERSESSION（不锁定 running，始终可用）
    // ============================================================
    private void runClearSession() {
        String sql = "delete from SERVERSESSION where 1=1; commit;";
        appendLog(">> 开始清除 SERVERSESSION", COLOR_START, 1);
        appendLog(">> SQL: " + sql, COLOR_INFO, 1);
        infoLabel.setText("清除 SERVERSESSION 执行中...");

        new Thread(() -> {
            try {
                String escapedSql = sql.replace("\"", "\\\"");
                ArrayList<String> cmdList = new ArrayList<>();
                cmdList.add(javaCmd);
                cmdList.add("-cp");
                cmdList.add(sksRoot.getAbsolutePath() + File.pathSeparator + classPath);
                cmdList.add("ConfigDBUI");
                cmdList.add("--sql=" + escapedSql);
                cmdList.add("--sks-root=" + sksRoot.getAbsolutePath());

                ProcessBuilder pb = new ProcessBuilder(cmdList);
                pb.directory(sksRoot);
                pb.environment().putAll(System.getenv());
                pb.redirectErrorStream(false);
                Process p = pb.start();

                Thread outT = new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()), 8192)) {
                        String l; while ((l = r.readLine()) != null) { final String ll = l; SwingUtilities.invokeLater(() -> appendLog(ll, COLOR_STDOUT, 1)); }
                    } catch (IOException ignored) {}
                });
                Thread errT = new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()), 8192)) {
                        String l; while ((l = r.readLine()) != null) { final String ll = l; SwingUtilities.invokeLater(() -> appendLog(ll, COLOR_STDERR, 1)); }
                    } catch (IOException ignored) {}
                });
                outT.start(); errT.start();

                int exitCode = p.waitFor();
                outT.join(3000); errT.join(3000);

                final int code = exitCode;
                SwingUtilities.invokeLater(() -> {
                    appendLog("-- 清除 SERVERSESSION 完毕，退出码: " + code, code == 0 ? COLOR_SUCCESS : COLOR_STDERR, 1);
                    infoLabel.setText("清除 SERVERSESSION " + (code == 0 ? "完成" : "失败"));
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("!! 清除出错: " + e.getMessage(), COLOR_STDERR, 1);
                    infoLabel.setText("清除 SERVERSESSION 出错");
                });
            }
        }, "clear-session").start();
    }

    // ============================================================
    // 查询三个变量的值，显示在状态栏
    // ============================================================
    private void runQueryVars() {
        String sql = "select varname, varvalue from maxvars where varname in ('CONFIGURING','CONFIRMLABTRANS','ADMINRESTART') order by varname";
        String countSql = "select count(1) as CNT from SERVERSESSION";
        appendLog(">> 查询变量值", COLOR_START, 1);
        appendLog(">> SQL: " + sql, COLOR_INFO, 1);
        appendLog(">> SQL: " + countSql, COLOR_INFO, 1);
        infoLabel.setText("正在查询...");

        new Thread(() -> {
            try {
                // ---- 查询 1：三个变量值 ----
                String escapedSql = sql.replace("\"", "\\\"");
                ArrayList<String> cmdList = new ArrayList<>();
                cmdList.add(javaCmd);
                cmdList.add("-cp");
                cmdList.add(sksRoot.getAbsolutePath() + File.pathSeparator + classPath);
                cmdList.add("ConfigDBUI");
                cmdList.add("--sql=" + escapedSql);
                cmdList.add("--sks-root=" + sksRoot.getAbsolutePath());

                ProcessBuilder pb = new ProcessBuilder(cmdList);
                pb.directory(sksRoot);
                pb.environment().putAll(System.getenv());
                pb.redirectErrorStream(true);
                Process p = pb.start();

                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()), 8192)) {
                    String l; while ((l = r.readLine()) != null) { sb.append(l).append("\n"); }
                }

                int exitCode = p.waitFor();

                // 解析结果行（格式: "  VARNAME=xxx, VARVALUE=yyy"）
                StringBuilder status = new StringBuilder();
                for (String line : sb.toString().split("\n")) {
                    if (line.startsWith("  VARNAME=")) {
                        String value = line.replaceAll(".*VARVALUE=", "").trim();
                        String varname = line.replaceAll(".*VARNAME=", "").replaceAll(",.*", "").trim();
                        if (value.length() > 0) {
                            if (status.length() > 0) status.append(" | ");
                            status.append(varname).append("=").append(value);
                        }
                    }
                }

                // ---- 查询 2：SERVERSESSION 行数 ----
                String countOutput = "";
                if (exitCode == 0) {
                    try {
                        String escapedCount = countSql.replace("\"", "\\\"");
                        ArrayList<String> countCmd = new ArrayList<>();
                        countCmd.add(javaCmd);
                        countCmd.add("-cp");
                        countCmd.add(sksRoot.getAbsolutePath() + File.pathSeparator + classPath);
                        countCmd.add("ConfigDBUI");
                        countCmd.add("--sql=" + escapedCount);
                        countCmd.add("--sks-root=" + sksRoot.getAbsolutePath());

                        ProcessBuilder pb2 = new ProcessBuilder(countCmd);
                        pb2.directory(sksRoot);
                        pb2.environment().putAll(System.getenv());
                        pb2.redirectErrorStream(true);
                        Process p2 = pb2.start();

                        StringBuilder sb2 = new StringBuilder();
                        try (BufferedReader r = new BufferedReader(new InputStreamReader(p2.getInputStream()), 8192)) {
                            String l; while ((l = r.readLine()) != null) { sb2.append(l).append("\n"); }
                        }
                        int cntExit = p2.waitFor();
                        if (cntExit == 0) {
                            for (String line : sb2.toString().split("\n")) {
                                if (line.startsWith("  CNT=")) {
                                    countOutput = " | SERVERSESSION=" + line.replaceAll(".*CNT=", "").trim();
                                    break;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }

                final String result = status.length() > 0 ? status.toString() : "(无数据)";
                final String sessionCount = countOutput;
                final int code = exitCode;

                SwingUtilities.invokeLater(() -> {
                    if (code == 0) {
                        appendLog("  变量结果: " + result, COLOR_SUCCESS, 1);
                        appendLog("  SERVERSESSION 行数" + (sessionCount.isEmpty() ? ": 查询失败" : sessionCount), COLOR_SUCCESS, 1);
                        infoLabel.setText(result + sessionCount);
                    } else {
                        appendLog("  查询失败", COLOR_STDERR, 1);
                        infoLabel.setText("查询失败");
                    }
                    appendLog("-- 查询变量完毕，退出码: " + code, code == 0 ? COLOR_SUCCESS : COLOR_STDERR, 1);
                });

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("!! 查询出错: " + e.getMessage(), COLOR_STDERR, 1);
                    infoLabel.setText("查询出错");
                });
            }
        }, "query-vars").start();
    }

    // ============================================================
    // 环境切换
    // ============================================================
    private void parseAppYml() {
        File ymlFile = new File(sksRoot, "config/app.yml");
        if (!ymlFile.exists()) {
            appendLog("!! 环境配置文件不存在: " + ymlFile, COLOR_STDERR);
            return;
        }
        globalProps.clear();
        environments.clear();
        try (InputStream in = new FileInputStream(ymlFile)) {
            Yaml yaml = new Yaml();
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) yaml.load(in);

            @SuppressWarnings("unchecked")
            Map<String, Object> mp = (Map<String, Object>) root.get("sks");
            if (mp == null) { appendLog("!! app.yml 缺少 sks 节点", COLOR_STDERR); return; }

            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) mp.get("maximo-properties");
            if (props == null) { appendLog("!! app.yml 缺少 maximo-properties 节点", COLOR_STDERR); return; }

            // 读取 default 字段
            Object defObj = props.get("default");
            defaultEnv = (defObj != null) ? defObj.toString() : "";

            // 解析全局属性
            @SuppressWarnings("unchecked")
            Map<String, Object> global = (Map<String, Object>) props.get("global");
            if (global != null) {
                for (Map.Entry<String, Object> e : global.entrySet()) {
                    globalProps.put(e.getKey(), e.getValue() != null ? e.getValue().toString() : "");
                }
            }

            // 解析环境配置
            @SuppressWarnings("unchecked")
            Map<String, Object> envs = (Map<String, Object>) props.get("environments");
            if (envs != null) {
                for (Map.Entry<String, Object> entry : envs.entrySet()) {
                    String envName = entry.getKey();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> envData = (Map<String, Object>) entry.getValue();
                    EnvConfig ec = new EnvConfig();
                    for (Map.Entry<String, Object> pe : envData.entrySet()) {
                        String val = pe.getValue() != null ? pe.getValue().toString() : "";
                        if (pe.getKey().equals("sks.desc")) {
                            ec.desc = val;
                        } else {
                            ec.props.put(pe.getKey(), val);
                        }
                    }
                    environments.put(envName, ec);
                }
            }

            appendLog("[INIT] 已加载 " + environments.size() + " 个环境配置", COLOR_INFO);
        } catch (Exception e) {
            appendLog("!! 解析 app.yml 出错: " + e.getMessage(), COLOR_STDERR);
        }
    }

    private void showEnvDialog() {
        if (environments.isEmpty()) {
            appendLog("!! 无可用环境配置", COLOR_STDERR);
            return;
        }

        String[] envKeys = environments.keySet().toArray(new String[0]);

        // 构建表数据
        String[] columns = {"环境名称", "描述"};
        Object[][] data = new Object[envKeys.length][2];
        for (int i = 0; i < envKeys.length; i++) {
            EnvConfig ec = environments.get(envKeys[i]);
            data[i][0] = envKeys[i];
            data[i][1] = (ec.desc != null && !ec.desc.isEmpty()) ? ec.desc : "";
        }

        JTable table = new JTable(data, columns);
        table.setFillsViewportHeight(true);
        table.setRowSelectionInterval(0, 0);
        table.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(100);
        table.getColumnModel().getColumn(1).setPreferredWidth(200);

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(380, 180));

        // 按钮
        JButton btnView = new JButton("查看配置内容");
        JButton btnLoad = new JButton("加载");
        JButton btnTest = new JButton("测试连接");
        JButton btnClose = new JButton("关闭");

        // 使用 BasicButtonUI 让自定义颜色不受 Windows LAF 影响
        javax.swing.plaf.basic.BasicButtonUI btnUI = new javax.swing.plaf.basic.BasicButtonUI();
        btnView.setUI(btnUI);
        btnView.setBackground(new Color(26, 76, 110));
        btnView.setForeground(Color.WHITE);
        btnView.setOpaque(true);
        btnLoad.setUI(btnUI);
        btnLoad.setBackground(new Color(24, 100, 50));
        btnLoad.setForeground(Color.WHITE);
        btnLoad.setOpaque(true);
        btnTest.setUI(btnUI);
        btnTest.setBackground(new Color(180, 130, 0));
        btnTest.setForeground(Color.WHITE);
        btnTest.setOpaque(true);
        btnClose.setUI(btnUI);
        btnClose.setBackground(new Color(100, 100, 100));
        btnClose.setForeground(Color.WHITE);
        btnClose.setOpaque(true);
        // 禁用焦点（让按钮不抢回车键）
        btnView.setFocusable(false);
        btnLoad.setFocusable(false);
        btnTest.setFocusable(false);
        btnClose.setFocusable(false);

        String[] finalEnvKeys = envKeys;
        btnView.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) return;
            String envName = finalEnvKeys[row];
            EnvConfig ec = environments.get(envName);
            Properties merged = mergeProperties(envName, ec);
            // 显示在对话框中
            StringBuilder sb = new StringBuilder();
            for (String key : merged.stringPropertyNames()) {
                sb.append(key).append("=").append(merged.getProperty(key)).append("\n");
            }
            JTextArea ta = new JTextArea(sb.toString());
            ta.setEditable(false);
            ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JScrollPane sp = new JScrollPane(ta);
            sp.setPreferredSize(new Dimension(500, 350));
            JOptionPane.showMessageDialog(frame, sp, "环境: " + envName + " 配置内容", JOptionPane.PLAIN_MESSAGE);
        });

        btnLoad.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) return;
            switchEnvironment(finalEnvKeys[row]);
            // 关闭对话框
            Window w = SwingUtilities.getWindowAncestor((JComponent) e.getSource());
            if (w != null) w.dispose();
        });

        btnTest.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) return;
            String envName = finalEnvKeys[row];
            EnvConfig ec = environments.get(envName);
            Properties merged = mergeProperties(envName, ec);
            testDbConnection(merged, envName, (ec.desc != null ? ec.desc : envName));
        });

        btnClose.addActionListener(e -> {
            Window w = SwingUtilities.getWindowAncestor((JComponent) e.getSource());
            if (w != null) w.dispose();
        });

        // 布局
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        btnPanel.add(btnView);
        btnPanel.add(btnLoad);
        btnPanel.add(btnTest);
        btnPanel.add(btnClose);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(btnPanel, BorderLayout.SOUTH);

        JDialog dialog = new JDialog(frame, "环境切换", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.getContentPane().add(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private Properties mergeProperties(String envName, EnvConfig ec) {
        Properties merged = new Properties();
        for (Map.Entry<String, String> e : globalProps.entrySet()) {
            merged.setProperty(e.getKey(), e.getValue());
        }
        if (ec != null) {
            for (Map.Entry<String, String> e : ec.props.entrySet()) {
                merged.setProperty(e.getKey(), e.getValue());
            }
        }
        return merged;
    }

    private void testDbConnection(Properties merged, String envName, String displayName) {
        String url = merged.getProperty("mxe.db.url", "");
        String user = merged.getProperty("mxe.db.user", merged.getProperty("mxe.db.username", ""));
        String pass = merged.getProperty("mxe.db.password", "");
        String driver = merged.getProperty("mxe.db.driver", "com.ibm.db2.jcc.DB2Driver");
        String schema = merged.getProperty("mxe.db.schemaowner", "");

        appendLog(">> 测试连接: " + displayName + " (" + envName + ")", COLOR_START);
        appendLog("   URL: " + url, COLOR_INFO);
        appendLog("   用户: " + user, COLOR_INFO);
        appendLog("   Schema: " + schema, COLOR_INFO);

        new Thread(() -> {
            try {
                Class.forName(driver);
                try (Connection conn = DriverManager.getConnection(url, user, pass)) {
                    if (!schema.isEmpty()) {
                        conn.createStatement().execute("SET SCHEMA " + schema);
                    }
                    appendLog("   连接成功!", COLOR_SUCCESS);
                    appendLog("   " + displayName + " 连接测试通过", COLOR_SUCCESS);
                    // 状态栏闪一下
                    SwingUtilities.invokeLater(() -> {
                        setStatus("连接测试: " + displayName + " 成功", COLOR_STATUS_OK);
                    });
                }
            } catch (Exception ex) {
                String msg = ex.getMessage();
                if (msg == null) msg = ex.getClass().getSimpleName();
                appendLog("   连接失败: " + msg, COLOR_STDERR);
                SwingUtilities.invokeLater(() -> {
                    setStatus("连接测试: " + displayName + " 失败", COLOR_STATUS_ERR);
                });
            } finally {
                SwingUtilities.invokeLater(() -> {
                    updateButtons();
                });
            }
        }).start();
    }

    private void switchEnvironment(String envName) {
        EnvConfig ec = environments.get(envName);
        if (ec == null) return;

        Properties merged = mergeProperties(envName, ec);

        // 写入 maximo.properties
        File propFile = new File(sksRoot, "maximo/properties/maximo.properties");
        File parent = propFile.getParentFile();
        if (!parent.exists()) parent.mkdirs();

        try (OutputStream os = new FileOutputStream(propFile)) {
            merged.store(os, "Generated by sks_configdb env: " + envName);
        } catch (Exception ex) {
            appendLog("!! 写入 maximo.properties 失败: " + ex.getMessage(), COLOR_STDERR);
            return;
        }

        currentEnvName = envName;
        if (btnSwitchEnv != null) {
            btnSwitchEnv.setText("环境: " + (ec.desc != null ? ec.desc : envName));
        }
        appendLog(">> 环境已切换为: " + (ec.desc != null ? ec.desc : envName), COLOR_SUCCESS);
        appendLog(">> 已写入 " + merged.size() + " 条属性到 " + propFile, COLOR_INFO);
        setStatus("环境: " + (ec.desc != null ? ec.desc : envName), COLOR_STATUS_OK);
        infoLabel.setText("当前环境: " + envName);
    }

    // ============================================================
    // 无界面模式：直接执行 SQL
    // ============================================================
    private static void headlessRunSql(String sql, String sksRootStr) {
        PrintStream out = System.out;
        PrintStream err = System.err;

        // 确定根目录
        File root;
        if (sksRootStr != null && !sksRootStr.isEmpty()) {
            root = new File(sksRootStr);
        } else {
            root = new File(System.getProperty("user.dir"));
        }

        // 读取 maximo.properties
        File propFile = new File(root, "maximo/properties/maximo.properties");
        if (!propFile.exists()) {
            err.println("[ERROR] 找不到 maximo.properties: " + propFile.getAbsolutePath());
            System.exit(1);
        }

        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(propFile)) {
            props.load(fis);
        } catch (IOException e) {
            err.println("[ERROR] 读取 maximo.properties 失败: " + e.getMessage());
            System.exit(1);
        }

        String dbUrl = props.getProperty("mxe.db.url");
        String dbUser = props.getProperty("mxe.db.user");
        String dbPass = props.getProperty("mxe.db.password");
        String schemaOwner = props.getProperty("mxe.db.schemaowner");

        if (dbUrl == null || dbUser == null || dbPass == null) {
            err.println("[ERROR] maximo.properties 缺少数据库连接信息 (mxe.db.url/user/password)");
            System.exit(1);
        }

        out.println("[INFO] 数据库: " + dbUrl);
        out.println("[INFO] 用户: " + dbUser);
        if (schemaOwner != null && !schemaOwner.trim().isEmpty()) {
            out.println("[INFO] Schema: " + schemaOwner.trim());
        }

        // 获取驱动类
        String driverClass = "com.ibm.db2.jcc.DB2Driver";
        if (dbUrl.startsWith("jdbc:oracle:")) driverClass = "oracle.jdbc.OracleDriver";
        else if (dbUrl.startsWith("jdbc:sqlserver:")) driverClass = "com.microsoft.sqlserver.jdbc.SQLServerDriver";
        else if (dbUrl.startsWith("jdbc:mysql:")) driverClass = "com.mysql.cj.jdbc.Driver";

        Connection conn = null;
        try {
            Class.forName(driverClass);
            conn = DriverManager.getConnection(dbUrl, dbUser, dbPass);
            conn.setAutoCommit(true);

            // 设置 Schema（统一大小写匹配）
            String schema = (schemaOwner != null) ? schemaOwner.trim() : "";
            if (!schema.isEmpty()) {
                try (Statement ss = conn.createStatement()) {
                    ss.execute("SET SCHEMA " + schema);
                } catch (SQLException se) {
                    // 某些数据库不支持 SET SCHEMA，查表时加前缀
                    out.println("[WARN] 设置 Schema 失败: " + se.getMessage());
                }
            }

            out.println("[INFO] 连接成功");

            // 分割多条 SQL
            String[] statements = sql.split(";");
            int total = 0;

            for (String s : statements) {
                String trimmed = s.trim();
                if (trimmed.isEmpty()) continue;

                // 过滤掉纯 commit / rollback（自动提交已开启）
                String upper = trimmed.toUpperCase();
                if (upper.equals("COMMIT") || upper.equals("ROLLBACK")) {
                    continue;
                }

                boolean isQuery = upper.startsWith("SELECT")
                    || upper.startsWith("WITH")
                    || trimmed.matches("(?is)^\\s*SELECT\\b.*");

                try (Statement stmt = conn.createStatement()) {
                    if (isQuery) {
                        try (ResultSet rs = stmt.executeQuery(trimmed)) {
                            ResultSetMetaData meta = rs.getMetaData();
                            int cols = meta.getColumnCount();
                            int rows = 0;
                            while (rs.next()) {
                                rows++;
                                StringBuilder rowStr = new StringBuilder("  ");
                                for (int i = 1; i <= cols; i++) {
                                    if (i > 1) rowStr.append(", ");
                                    rowStr.append(meta.getColumnName(i)).append("=").append(rs.getString(i));
                                }
                                out.println(rowStr);
                            }
                            total += rows;
                            out.println("[OK] " + trimmed.substring(0, Math.min(trimmed.length(), 60)) + " -> " + rows + " 行");
                        }
                    } else {
                        int rows = stmt.executeUpdate(trimmed);
                        total += rows;
                        out.println("[OK] " + trimmed.substring(0, Math.min(trimmed.length(), 60)) + " -> " + rows + " 行");
                    }
                } catch (SQLException e) {
                    out.println("[WARN] 语句跳过: " + e.getMessage());
                }
            }

            out.println("[DONE] 完成，共影响 " + total + " 行");

        } catch (ClassNotFoundException e) {
            err.println("[ERROR] JDBC 驱动未找到: " + driverClass);
            err.println("[ERROR] 请检查 lib/ 目录是否包含对应数据库的 JDBC 驱动");
            System.exit(1);
        } catch (SQLException e) {
            err.println("[ERROR] 数据库错误: " + e.getMessage());
            System.exit(1);
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (SQLException ignored) {}
            }
        }
    }

    // ============================================================
    // 初始化
    // ============================================================
    private boolean init() {
        sksRoot = findSksRoot();

        if (!readConfig()) {
            JOptionPane.showMessageDialog(null,
                "请编辑 config.properties 设置 jdk.home\n" +
                "当前路径: " + new File(sksRoot, "config.properties").getAbsolutePath(),
                "配置错误", JOptionPane.ERROR_MESSAGE);
            return false;
        }

        if (!new File(javaCmd).exists()) {
            JOptionPane.showMessageDialog(null,
                "JDK 不可用: " + javaCmd + "\n请检查 config.properties 中的 jdk.home",
                "Java 未找到", JOptionPane.ERROR_MESSAGE);
            return false;
        }

        classPath = buildClassPath();
        return true;
    }

    private void show() {
        buildUI();
        frame.setVisible(true);

        // 加载环境配置
        parseAppYml();
        if (!environments.isEmpty()) {
            // 使用 default 指定的环境，不存在则用第一个
            String target = defaultEnv;
            if (target == null || !environments.containsKey(target)) {
                target = environments.keySet().iterator().next();
            }
            switchEnvironment(target);
        } else {
            appendLog("[INIT] 未加载到环境配置", COLOR_WARN);
        }

        appendLog("[INIT] 根目录: " + sksRoot.getAbsolutePath(), COLOR_INFO);
        appendLog("[INIT] JDK: " + javaCmd, COLOR_INFO);
        appendLog("[INIT] Properties: " + propDir, COLOR_INFO);
        appendLog("[INIT] Classpath: " + classPath.length() + " 字符", COLOR_INFO);
        appendLog(">> sks_configdb v2.0 - 就绪", COLOR_START);
    }

    private File findSksRoot() {
        try {
            String path = ConfigDBUI.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI()
                .getPath();
            File f = new File(path);
            if (f.isFile()) return f.getParentFile();
            // 如果是 bin/ 目录，返回上级（sks_root）
            String name = f.getName();
            if ("bin".equals(name) || "classes".equals(name) || "build".equals(name)) {
                return f.getParentFile();
            }
            return f;
        } catch (Exception e) {
            return new File(System.getProperty("user.dir"));
        }
    }

    private boolean readConfig() {
        File configFile = new File(sksRoot, "config.properties");
        if (!configFile.exists()) return false;

        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(configFile)) {
            props.load(fis);
        } catch (IOException e) { return false; }

        String jdkHome = props.getProperty("jdk.home");
        if (jdkHome == null || jdkHome.trim().isEmpty()) return false;

        jdkHome = jdkHome.trim().replace("\\", "/");
        File jdkDir = new File(jdkHome);
        String javabin = new File(jdkDir, "bin").getAbsolutePath();
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            javaCmd = javabin + "/java.exe";
        } else {
            javaCmd = javabin + "/java";
        }
        return true;
    }

    private String buildClassPath() {
        StringBuilder cp = new StringBuilder();

        File libDir = new File(sksRoot, "maximo/lib");
        File propDirFile = new File(sksRoot, "maximo/properties");
        File resDir = new File(sksRoot, "maximo/resources");
        File boJar = new File(sksRoot, "maximo/businessobjects.jar");
        File boDir = new File(sksRoot, "maximo/businessobjects/classes");

        this.propDir = propDirFile.getAbsolutePath();

        appendIfExists(cp, propDirFile);
        appendIfExists(cp, resDir);
        if (boJar.exists()) {
            cp.append(boJar.getAbsolutePath()).append(File.pathSeparator);
        } else {
            appendIfExists(cp, boDir);
        }

        File snakeyamlJar = new File(sksRoot, "lib/snakeyaml-2.5.jar");
        if (snakeyamlJar.exists()) cp.append(snakeyamlJar.getAbsolutePath()).append(File.pathSeparator);

        File binDir = new File(sksRoot, "bin");
        appendIfExists(cp, binDir);

        if (libDir.exists() && libDir.isDirectory()) {
            File[] jars = libDir.listFiles((d, n) -> n.endsWith(".jar"));
            if (jars != null) {
                Arrays.sort(jars);
                for (File j : jars) cp.append(j.getAbsolutePath()).append(File.pathSeparator);
            }
        }

        return cp.toString();
    }

    private void appendIfExists(StringBuilder cp, File dir) {
        if (dir.exists() && dir.isDirectory())
            cp.append(dir.getAbsolutePath()).append(File.pathSeparator);
    }

    // ============================================================
    // 构建 UI
    // ============================================================
    private void buildUI() {
        frame = new JFrame("sks_configdb - Maximo 数据库配置工具");
        frame.setIconImage(new ImageIcon("images/icon.png").getImage());
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                if (running) {
                    JOptionPane.showMessageDialog(frame,
                        "ConfigDB 或 RestoreFromBackup 正在执行中，请先点击「停止」按钮。",
                        "执行中", JOptionPane.WARNING_MESSAGE);
                } else {
                    frame.dispose();
                    System.exit(0);
                }
            }
        });
        frame.setSize(1000, 700);
        frame.setMinimumSize(new Dimension(700, 500));
        frame.setLocationRelativeTo(null);

        JPanel mainPanel = new JPanel(new BorderLayout(0, 8));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        mainPanel.setBackground(new Color(240, 242, 245));

        // ---- 顶部标题 ----
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(new Color(26, 115, 232));
        headerPanel.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        JLabel titleLabel = new JLabel("sks_configdb");
        titleLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 20));
        titleLabel.setForeground(Color.WHITE);
        headerPanel.add(titleLabel, BorderLayout.WEST);
        JLabel subtitleLabel = new JLabel("Maximo 数据库配置工具");
        subtitleLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        subtitleLabel.setForeground(new Color(200, 220, 255));
        headerPanel.add(subtitleLabel, BorderLayout.EAST);

        // ---- 工具栏（两行） ----
        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.Y_AXIS));
        toolbar.setBackground(Color.WHITE);
        toolbar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(220, 220, 220)),
            BorderFactory.createEmptyBorder(10, 14, 10, 14)
        ));

        // 第一行：ConfigDB 操作
        btnConfigDB = createButton("ConfigDB", new Color(26, 115, 232), e -> runJavaClass("configdb"));
        btnRestore = createButton("RestoreFromBackup", new Color(52, 168, 83), e -> runJavaClass("restorefrombackup"));
        btnStop = createButton("停止", new Color(234, 67, 53), e -> stopProcess(), false);
        btnClear = createButton("清空日志", new Color(95, 99, 104), e -> clearLog());
        btnSwitchEnv = createButton("环境: ...", new Color(40, 130, 180), e -> showEnvDialog());

        JPanel row1 = new JPanel(new BorderLayout());
        row1.setBackground(Color.WHITE);
        JPanel leftRow1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftRow1.setBackground(Color.WHITE);
        leftRow1.add(btnConfigDB);
        leftRow1.add(btnRestore);
        leftRow1.add(Box.createHorizontalStrut(12));
        leftRow1.add(btnStop);
        leftRow1.add(btnClear);
        row1.add(leftRow1, BorderLayout.WEST);
        JPanel rightRow1 = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        rightRow1.setBackground(Color.WHITE);
        rightRow1.add(btnSwitchEnv);
        row1.add(rightRow1, BorderLayout.EAST);

        // 第二行：数据库快速操作
        btnClearSession     = createButton("清除 SERVERSESSION", COLOR_DB,  e -> runClearSession());
        btnSetConfiguring   = createButton("CONFIGURING=0", COLOR_DB2, e -> runSqlTask("设置 CONFIGURING=0",
            "update maxvars set VARVALUE='0' where varname in ('CONFIGURING')", true));
        btnSetConfirmLabTrans = createButton("CONFIRMLABTRANS=0", COLOR_DB2, e -> runSqlTask("CONFIRMLABTRANS=0",
            "update maxvars set VARVALUE='0' where varname in ('CONFIRMLABTRANS')", true));
        btnSetAdminRestart    = createButton("ADMINRESTART=OFF", new Color(160, 120, 100), e -> runSqlTask("ADMINRESTART=OFF",
            "update maxvars set VARVALUE='OFF' where varname in ('ADMINRESTART')", true));
        btnQueryVars = createButton("查询变量", new Color(90, 170, 140), e -> runQueryVars());

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        row2.setBackground(Color.WHITE);
        row2.add(btnClearSession);
        row2.add(btnSetConfiguring);
        row2.add(btnSetConfirmLabTrans);
        row2.add(btnSetAdminRestart);
        row2.add(Box.createHorizontalStrut(12));
        row2.add(btnQueryVars);

        toolbar.add(row1);
        toolbar.add(row2);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(headerPanel, BorderLayout.NORTH);
        topPanel.add(toolbar, BorderLayout.SOUTH);

        // ---- 状态栏 ----
        JPanel statusBar = new JPanel(new BorderLayout(10, 0));
        statusBar.setBackground(Color.WHITE);
        statusBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(220, 220, 220)),
            BorderFactory.createEmptyBorder(8, 14, 8, 14)
        ));
        JPanel statusLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        statusLeft.setBackground(Color.WHITE);
        statusDot = new JLabel("\u25CF");
        statusDot.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        statusDot.setForeground(COLOR_STATUS_IDLE);
        statusLeft.add(statusDot);
        statusLabel = new JLabel("就绪");
        statusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        statusLabel.setForeground(new Color(60, 60, 60));
        statusLeft.add(statusLabel);
        statusBar.add(statusLeft, BorderLayout.WEST);
        infoLabel = new JLabel("等待操作...");
        infoLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        infoLabel.setForeground(new Color(130, 130, 130));
        statusBar.add(infoLabel, BorderLayout.CENTER);
        lineCountLabel = new JLabel("0 行");
        lineCountLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        lineCountLabel.setForeground(new Color(130, 130, 130));
        statusBar.add(lineCountLabel, BorderLayout.EAST);

        // ---- 日志区域（带标签页）----
        logPaneCfg.setEditable(false);
        logPaneCfg.setBackground(COLOR_BG);
        logPaneCfg.setFont(LOG_FONT);
        logPaneCfg.setMargin(new Insets(12, 14, 12, 14));

        logPaneSql.setEditable(false);
        logPaneSql.setBackground(COLOR_BG);
        logPaneSql.setFont(LOG_FONT);
        logPaneSql.setMargin(new Insets(12, 14, 12, 14));

        logTabs = new JTabbedPane();
        logTabs.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        logTabs.addTab("ConfigDB / RestoreFromBackup", new JScrollPane(logPaneCfg));
        logTabs.addTab("数据库操作", new JScrollPane(logPaneSql));
        logTabs.setBorder(BorderFactory.createLineBorder(new Color(200, 200, 200)));

        // ---- 底部（内置任务状态栏）----
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(new Color(45, 45, 45));
        footer.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
        taskStatusLabel = new JLabel("就绪");
        taskStatusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        taskStatusLabel.setForeground(new Color(150, 150, 150));
        footer.add(taskStatusLabel, BorderLayout.WEST);
        JLabel footerRight = new JLabel("sks_configdb v2.0");
        footerRight.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        footerRight.setForeground(new Color(100, 100, 100));
        footer.add(footerRight, BorderLayout.EAST);

        // ---- 组装 ----
        JPanel centerPanel = new JPanel(new BorderLayout(0, 0));
        centerPanel.add(statusBar, BorderLayout.NORTH);
        centerPanel.add(logTabs, BorderLayout.CENTER);
        centerPanel.add(footer, BorderLayout.SOUTH);

        mainPanel.add(topPanel, BorderLayout.NORTH);
        mainPanel.add(centerPanel, BorderLayout.CENTER);
        frame.setContentPane(mainPanel);
    }

    // ============================================================
    // 创建按钮
    // ============================================================
    private JButton createButton(String text, Color bg, ActionListener listener) {
        return createButton(text, bg, listener, true);
    }

    private JButton createButton(String text, Color bg, ActionListener listener, boolean enabled) {
        JButton btn = new JButton(text);
        btn.setFont(BTN_FONT);
        btn.setForeground(Color.WHITE);
        btn.setBackground(bg);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setOpaque(true);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
        btn.setEnabled(enabled);
        btn.addActionListener(listener);
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { if (btn.isEnabled()) btn.setBackground(bg.darker()); }
            public void mouseExited(MouseEvent e) { btn.setBackground(bg); }
        });
        return btn;
    }

    // ============================================================
    // 执行 Java 类（ConfigDB / RestoreFromBackup）
    // ============================================================
    private void runJavaClass(String type) {
        if (running) return;

        String mainClass;
        String displayName;
        String[] extraArgs;

        if (type.equals("configdb")) {
            mainClass = "psdi.configure.ConfigDB";
            displayName = "ConfigDB";
            extraArgs = new String[]{"-l", "-e", "-r"};
        } else {
            mainClass = "psdi.configure.RestoreFromBackup";
            displayName = "RestoreFromBackup";
            extraArgs = new String[]{"-l", "-e"};
        }

        running = true;
        updateButtons();
        setStatus("正在执行 " + displayName + "...", COLOR_STATUS_RUN);
        infoLabel.setText("正在执行 " + displayName + "...");
        setTaskStatus(">> " + displayName + " 执行中...", new Color(26, 115, 232));

        appendLog(">> 开始执行 " + displayName, COLOR_START);
        appendLog(">> Java: " + javaCmd, COLOR_INFO);
        appendLog(">> 类: " + mainClass, COLOR_INFO);

        new Thread(() -> {
            try {
                String[] cmd = buildCommand(mainClass, extraArgs);
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(sksRoot);
                pb.environment().putAll(System.getenv());
                pb.redirectErrorStream(false);
                currentProcess = pb.start();

                Thread outT = new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(currentProcess.getInputStream()), 8192)) {
                        String l; while ((l = r.readLine()) != null) { final String ll = l; SwingUtilities.invokeLater(() -> appendLog(ll, COLOR_STDOUT)); }
                    } catch (IOException ignored) {}
                });
                Thread errT = new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(currentProcess.getErrorStream()), 8192)) {
                        String l; while ((l = r.readLine()) != null) { final String ll = l; SwingUtilities.invokeLater(() -> appendLog(ll, COLOR_STDERR)); }
                    } catch (IOException ignored) {}
                });
                outT.start(); errT.start();

                int exitCode = currentProcess.waitFor();
                outT.join(3000); errT.join(3000);

                final int code = exitCode;
                SwingUtilities.invokeLater(() -> {
                    appendLog("-- " + displayName + " 执行完毕，退出码: " + code, code == 0 ? COLOR_SUCCESS : COLOR_STDERR);
                    setStatus(code == 0 ? "执行成功" : "执行失败 (退出码: " + code + ")", code == 0 ? COLOR_STATUS_OK : COLOR_STATUS_ERR);
                    infoLabel.setText(displayName + (code == 0 ? " - 完成" : " - 失败"));
                    setTaskStatus(displayName + (code == 0 ? " 完成 (0)" : " 失败 (" + code + ")"), code == 0 ? COLOR_STATUS_OK : COLOR_STATUS_ERR);
                    running = false; currentProcess = null; updateButtons();
                });

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("!! 执行出错: " + e.getMessage(), COLOR_STDERR);
                    setStatus("出错", COLOR_STATUS_ERR);
                    infoLabel.setText("执行出错");
                    setTaskStatus(displayName + " 出错", COLOR_STATUS_ERR);
                    running = false; currentProcess = null; updateButtons();
                });
            }
        }, "java-runner").start();
    }

    private String[] buildCommand(String mainClass, String[] extraArgs) {
        ArrayList<String> args = new ArrayList<>();
        args.add(javaCmd); args.add("-Xmx1024m"); args.add("-cp");
        args.add(sksRoot.getAbsolutePath() + File.pathSeparator + classPath);
        args.add(mainClass);
        if (extraArgs != null) Collections.addAll(args, extraArgs);
        args.add("-k" + propDir);
        return args.toArray(new String[0]);
    }

    // ============================================================
    // 执行 SQL（通过子进程）
    // ============================================================
    private void runSqlTask(String displayName, String sql, boolean needsConfirm) {
        if (running) return;

        if (needsConfirm) {
            int r = JOptionPane.showConfirmDialog(frame,
                "确定执行: " + displayName + "?\nSQL: " + sql.substring(0, Math.min(sql.length(), 80)),
                "确认操作", JOptionPane.YES_NO_OPTION);
            if (r != JOptionPane.YES_OPTION) return;
        }

        if (logTabs != null) logTabs.setSelectedIndex(1);
        running = true;
        updateButtons();
        infoLabel.setText(displayName + " 执行中...");

        appendLog(">> 开始执行 " + displayName, COLOR_START, 1);
        appendLog(">> SQL: " + sql, COLOR_INFO, 1);

        new Thread(() -> {
            try {
                // 构建命令: java -cp sks_root:classPath ConfigDBUI --sql="..." --sks-root="..."
                String escapedSql = sql.replace("\"", "\\\"");
                ArrayList<String> cmdList = new ArrayList<>();
                cmdList.add(javaCmd);
                cmdList.add("-cp");
                cmdList.add(sksRoot.getAbsolutePath() + File.pathSeparator + classPath);
                cmdList.add("ConfigDBUI");
                cmdList.add("--sql=" + escapedSql);
                cmdList.add("--sks-root=" + sksRoot.getAbsolutePath());

                ProcessBuilder pb = new ProcessBuilder(cmdList);
                pb.directory(sksRoot);
                pb.environment().putAll(System.getenv());
                pb.redirectErrorStream(false);
                currentProcess = pb.start();

                Thread outT = new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(currentProcess.getInputStream()), 8192)) {
                        String l; while ((l = r.readLine()) != null) { final String ll = l; SwingUtilities.invokeLater(() -> appendLog(ll, COLOR_STDOUT, 1)); }
                    } catch (IOException ignored) {}
                });
                Thread errT = new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(currentProcess.getErrorStream()), 8192)) {
                        String l; while ((l = r.readLine()) != null) { final String ll = l; SwingUtilities.invokeLater(() -> appendLog(ll, COLOR_STDERR, 1)); }
                    } catch (IOException ignored) {}
                });
                outT.start(); errT.start();

                int exitCode = currentProcess.waitFor();
                outT.join(3000); errT.join(3000);

                final int code = exitCode;
                SwingUtilities.invokeLater(() -> {
                    appendLog("-- " + displayName + " 执行完毕，退出码: " + code, code == 0 ? COLOR_SUCCESS : COLOR_STDERR, 1);
                    infoLabel.setText(displayName + (code == 0 ? " - 完成" : " - 失败"));
                    running = false; currentProcess = null; updateButtons();
                });

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("!! 执行出错: " + e.getMessage(), COLOR_STDERR, 1);
                    infoLabel.setText("执行出错");
                    running = false; currentProcess = null; updateButtons();
                });
            }
        }, "sql-runner").start();
    }

    // ============================================================
    // 停止进程
    // ============================================================
    private void stopProcess() {
        if (currentProcess != null && currentProcess.isAlive()) {
            currentProcess.destroyForcibly();
            appendLog("!! 进程已强制终止", COLOR_STDERR);
            setStatus("已停止", COLOR_STATUS_IDLE);
            infoLabel.setText("已停止");
            setTaskStatus("已停止", COLOR_STATUS_IDLE);
            running = false; currentProcess = null; updateButtons();
        }
    }

    // ============================================================
    // 清空日志 / 追加日志 / 状态
    // ============================================================
    private void clearLog() {
        try { docCfg.remove(0, docCfg.getLength()); cfgLineCount = 0; } catch (BadLocationException ignored) {}
        try { docSql.remove(0, docSql.getLength()); sqlLineCount = 0; } catch (BadLocationException ignored) {}
        lineCountLabel.setText("0 行");
    }

    private void appendLog(String text, Color color) { appendLogToTab(text, color, false, 0); }
    private void appendLog(String text, Color color, boolean bold) { appendLogToTab(text, color, bold, 0); }
    private void appendLog(String text, Color color, int tabIndex) { appendLogToTab(text, color, false, tabIndex); }

    private void appendLogToTab(String text, Color color, boolean bold, int tabIndex) {
        JTextPane pane = (tabIndex == 0) ? logPaneCfg : logPaneSql;
        StyledDocument targetDoc = (tabIndex == 0) ? docCfg : docSql;
        try {
            StyleContext sc = StyleContext.getDefaultStyleContext();
            AttributeSet attr = sc.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, color);
            if (bold) attr = sc.addAttribute(attr, StyleConstants.Bold, true);
            String ts = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
            targetDoc.insertString(targetDoc.getLength(), "[" + ts + "] " + text + "\n", attr);
            if (tabIndex == 0) cfgLineCount++; else sqlLineCount++;
            lineCountLabel.setText((cfgLineCount + sqlLineCount) + " 行");
            pane.setCaretPosition(targetDoc.getLength());
        } catch (BadLocationException ignored) {}
    }

    private void setStatus(String text, Color dotColor) {
        statusLabel.setText(text);
        statusDot.setForeground(dotColor);
    }

    private void setTaskStatus(String text, Color color) {
        if (taskStatusLabel != null) {
            taskStatusLabel.setText(text);
            taskStatusLabel.setForeground(color != null ? color : new Color(100, 100, 100));
        }
    }

    private void updateButtons() {
        btnConfigDB.setEnabled(!running);
        btnRestore.setEnabled(!running);
        btnStop.setEnabled(running);
        btnClear.setEnabled(!running);
        btnClearSession.setEnabled(true);         // 始终可用
        btnSetConfiguring.setEnabled(!running);
        btnSetConfirmLabTrans.setEnabled(!running);
        btnSetAdminRestart.setEnabled(!running);
        btnQueryVars.setEnabled(true);            // 始终可用
    }
}
