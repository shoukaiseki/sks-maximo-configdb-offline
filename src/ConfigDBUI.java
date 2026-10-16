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
    private static final Font BTN_FONT = new Font("Microsoft YaHei", Font.BOLD, 12);

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
    private JButton btnShowJars;
    private JButton btnLogLevel;
    private JButton btnShowUsers;
    private JButton btnValidateCrypto;
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
    private java.util.List<String> loadedJars = new java.util.ArrayList<>();

    // 环境切换
    private String currentEnvName;
    private String defaultEnv;
    private Map<String, String> globalProps = new LinkedHashMap<>();
    private Map<String, EnvConfig> environments = new LinkedHashMap<>();
    // 验证全库密码: 表->主键列映射（sks.validate-cryptokey.tableid-map）
    private Map<String, String> cryptoTableIdMap = new LinkedHashMap<>();
    private JButton btnSwitchEnv;

    // 调试模式
    private volatile boolean debugEnabled = false;
    private String debugPort = "5005";
    private JToggleButton btnDebug;
    private Map<String, String> extraEnv = new LinkedHashMap<>();
    private java.util.List<String> configDBArgs = new java.util.ArrayList<>();

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
                cmdList.add("-Dfile.encoding=UTF-8");
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
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"), 8192)) {
                        String l; while ((l = r.readLine()) != null) { final String ll = l; SwingUtilities.invokeLater(() -> appendLog(ll, COLOR_STDOUT, 1)); }
                    } catch (IOException ignored) {}
                });
                Thread errT = new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), "UTF-8"), 8192)) {
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
                cmdList.add("-Dfile.encoding=UTF-8");
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
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"), 8192)) {
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
                        countCmd.add("-Dfile.encoding=UTF-8");
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
                        try (BufferedReader r = new BufferedReader(new InputStreamReader(p2.getInputStream(), "UTF-8"), 8192)) {
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

            // 读取调试端口（sks.debug-port）
            Object portObj = mp.get("debug-port");
            if (portObj != null) debugPort = portObj.toString();
            appendLog("[INIT] 调试端口: " + debugPort, COLOR_INFO);

            // 读取额外环境变量（sks.env）
            extraEnv.clear();
            @SuppressWarnings("unchecked")
            Map<String, Object> envMap = (Map<String, Object>) mp.get("env");
            if (envMap != null) {
                for (Map.Entry<String, Object> e : envMap.entrySet()) {
                    extraEnv.put(e.getKey(), e.getValue() != null ? e.getValue().toString() : "");
                }
                appendLog("[INIT] 额外环境变量: " + extraEnv.size() + " 个", COLOR_INFO);
            }

            // 读取 ConfigDB 额外参数（sks.configDBArgs）
            configDBArgs.clear();
            @SuppressWarnings("unchecked")
            java.util.List<Object> argList = (java.util.List<Object>) mp.get("configDBArgs");
            if (argList != null) {
                for (Object a : argList) {
                    String s = a.toString().trim();
                    if (!s.isEmpty()) configDBArgs.add(s);
                }
                if (!configDBArgs.isEmpty()) {
                    appendLog("[INIT] ConfigDB 额外参数: " + String.join(" ", configDBArgs), COLOR_INFO);
                }
            }

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

            // 读取验证全库密码的主键映射（sks.validate-cryptokey.tableid-map）
            cryptoTableIdMap.clear();
            @SuppressWarnings("unchecked")
            Map<String, Object> vc = (Map<String, Object>) mp.get("validate-cryptokey");
            if (vc != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> tm = (Map<String, Object>) vc.get("tableid-map");
                if (tm != null) {
                    for (Map.Entry<String, Object> e : tm.entrySet()) {
                        if (e.getValue() == null) continue;
                        cryptoTableIdMap.put(e.getKey().trim().toUpperCase(), e.getValue().toString().trim());
                    }
                    if (!cryptoTableIdMap.isEmpty()) {
                        appendLog("[INIT] 加密字段主键映射: " + cryptoTableIdMap.size() + " 个", COLOR_INFO);
                    }
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
    // 显示已加载的 JAR 列表
    // ============================================================
    private void showLoadedJars() {
        StringBuilder sb = new StringBuilder();
        sb.append("已加载 ").append(loadedJars.size()).append(" 个 JAR:\n\n");
        for (int i = 0; i < loadedJars.size(); i++) {
            sb.append(String.format("  %2d. %s%n", i + 1, loadedJars.get(i)));
        }

        JTextArea textArea = new JTextArea(sb.toString());
        textArea.setEditable(false);
        textArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        textArea.setBackground(new Color(245, 245, 245));
        JScrollPane scroll = new JScrollPane(textArea);
        scroll.setPreferredSize(new Dimension(600, 300));

        JOptionPane.showMessageDialog(frame, scroll, "已加载 JAR", JOptionPane.PLAIN_MESSAGE);
    }

    // ============================================================
    // MXLogger 日志级别查询
    // ============================================================
    private void runMXLoggerQuery() {
        appendLog(">> 查询 MXLogger 日志级别", COLOR_START, 1);
        infoLabel.setText("正在查询日志级别...");

        new Thread(() -> {
            try {
                StringBuilder cp = new StringBuilder();
                cp.append(classPath);
                // 确保 properties 目录在前面（logging.properties）
                cp.insert(0, propDir + File.pathSeparator);
                cp.insert(0, sksRoot.getAbsolutePath() + File.pathSeparator);
                cp.append(sksRoot.getAbsolutePath()).append(File.pathSeparator).append("bin");

                ArrayList<String> cmdList = new ArrayList<>();
                cmdList.add(javaCmd);
                cmdList.add("-Dfile.encoding=UTF-8");
                cmdList.add("-cp");
                cmdList.add(cp.toString());
                cmdList.add("MXLoggerQuery");

                ProcessBuilder pb = new ProcessBuilder(cmdList);
                pb.directory(sksRoot);
                pb.environment().putAll(System.getenv());
                pb.redirectErrorStream(true);
                Process p = pb.start();

                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"), 8192)) {
                    String l; while ((l = r.readLine()) != null) { sb.append(l).append("\n"); }
                }
                int exitCode = p.waitFor();

                final String output = sb.toString();
                final int code = exitCode;

                SwingUtilities.invokeLater(() -> {
                    // 先输出原始结果到日志（摘要形式）
                    for (String l : output.split("\n")) {
                        if (l.startsWith("LOGGER|")) {
                            String[] parts = l.substring(7).split("\\|", 2);
                            if (parts.length >= 2) appendLog("  " + parts[0] + " = " + parts[1], COLOR_INFO, 1);
                        } else if (l.startsWith("ERROR|") || l.startsWith("WARN|")) {
                            appendLog("  " + l.substring(l.indexOf('|') + 1), l.startsWith("ERROR") ? COLOR_STDERR : COLOR_WARN, 1);
                        }
                    }
                    appendLog("-- MXLogger 查询完毕，退出码: " + code, code == 0 ? COLOR_SUCCESS : COLOR_STDERR, 1);
                    infoLabel.setText(code == 0 ? "日志级别查询完成" : "日志级别查询失败");
                    if (code == 0) {
                        showLoggerDialog(output);
                    } else {
                        appendLog(output, COLOR_STDERR, 1);
                    }
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("!! 查询日志级别出错: " + e.getMessage(), COLOR_STDERR, 1);
                    infoLabel.setText("查询日志级别出错");
                });
            }
        }, "logger-query").start();
    }

    private void showLoggerDialog(String rawOutput) {
        // 解析输出行: LOGGER|name|level [来源]
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        java.util.List<String> infoLines = new java.util.ArrayList<>();
        for (String line : rawOutput.split("\n")) {
            line = line.trim();
            if (line.startsWith("LOGGER|")) {
                int lastPipe = line.lastIndexOf('|');
                int firstParen = line.indexOf('(');
                String level = firstParen > lastPipe ? line.substring(lastPipe + 1, firstParen).trim() : line.substring(lastPipe + 1);
                String name = line.substring(7, lastPipe);
                rows.add(new String[]{name, level});
            } else if (line.startsWith("ERROR|") || line.startsWith("WARN|") || line.startsWith("INFO|")) {
                infoLines.add(line.substring(line.indexOf('|') + 1));
            } else if (line.startsWith("CONFIG|")) {
                infoLines.add(line);
            }
        }

        if (rows.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "未获取到任何日志级别信息。\n日志记录器缓存为空。",
                "日志级别", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // 创建表格
        String[] columns = {"Logger Name", "Level"};
        Object[][] data = new Object[rows.size()][2];
        for (int i = 0; i < rows.size(); i++) {
            data[i][0] = rows.get(i)[0];
            data[i][1] = rows.get(i)[1];
        }

        javax.swing.table.DefaultTableModel model = new javax.swing.table.DefaultTableModel(data, columns) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable table = new JTable(model);
        table.setFont(new Font("Consolas", Font.PLAIN, 12));
        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setPreferredWidth(350);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);

        // 搜索框
        JTextField searchField = new JTextField();
        searchField.putClientProperty("JTextField.placeholderText", "输入 Logger 名称过滤...");
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void filter() {
                String text = searchField.getText().trim().toLowerCase();
                javax.swing.table.TableRowSorter<javax.swing.table.DefaultTableModel> sorter =
                    (javax.swing.table.TableRowSorter<javax.swing.table.DefaultTableModel>) table.getRowSorter();
                if (text.isEmpty()) {
                    sorter.setRowFilter(null);
                } else {
                    sorter.setRowFilter(javax.swing.RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(text)));
                }
            }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { filter(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { filter(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { filter(); }
        });

        // 设置排序/过滤
        javax.swing.table.TableRowSorter<javax.swing.table.DefaultTableModel> sorter =
            new javax.swing.table.TableRowSorter<>(model);
        table.setRowSorter(sorter);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(700, 400));

        // 底部信息栏
        StringBuilder infoText = new StringBuilder("共 " + rows.size() + " 个日志记录器");
        for (String info : infoLines) {
            infoText.append(" | ").append(info);
        }
        JLabel countLabel = new JLabel(infoText.toString());
        countLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.add(searchField, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(countLabel, BorderLayout.SOUTH);

        JOptionPane.showMessageDialog(frame, panel, "MXLogger 日志级别", JOptionPane.PLAIN_MESSAGE);
    }

    // ============================================================
    // 备份表管理
    // ============================================================
    private void runBackupTableList() {
        runBackupTableQuery();
    }

    private void runBackupTableQuery() {
        appendLog(">> 查询备份表列表", COLOR_START, 1);
        infoLabel.setText("正在查询备份表...");

        new Thread(() -> {
            try {
                StringBuilder cp = new StringBuilder();
                cp.append(classPath);
                cp.append(File.pathSeparator).append(sksRoot.getAbsolutePath()).append(File.pathSeparator).append("bin");

                ArrayList<String> cmdList = new ArrayList<>();
                cmdList.add(javaCmd);
                cmdList.add("-Dfile.encoding=UTF-8");
                cmdList.add("-cp");
                cmdList.add(cp.toString());
                cmdList.add("BackupTableQuery");
                cmdList.add("--sks-root=" + sksRoot.getAbsolutePath());
                cmdList.add("--action=list");

                ProcessBuilder pb = new ProcessBuilder(cmdList);
                pb.directory(sksRoot);
                pb.redirectErrorStream(true);
                Process p = pb.start();

                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"), 8192)) {
                    String l; while ((l = r.readLine()) != null) { sb.append(l).append("\n"); }
                }
                int exitCode = p.waitFor();

                final String output = sb.toString();
                final int code = exitCode;
                SwingUtilities.invokeLater(() -> {
                    if (code == 0) {
                        java.util.List<String[]> tables = new java.util.ArrayList<>();
                        StringBuilder initLog = new StringBuilder();
                        for (String line : output.split("\n")) {
                            if (line.startsWith("TABLE|")) {
                                String[] parts = line.substring(6).split("\\|", 2);
                                if (parts.length == 2) {
                                    tables.add(new String[]{parts[0], parts[1]});
                                }
                            } else if (line.startsWith("SQL|")) {
                                initLog.append(line).append("\n");
                            } else if (line.startsWith("INFO|")) {
                                initLog.append(line.substring(5)).append("\n");
                            }
                        }
                        initLog.append("找到 ").append(tables.size()).append(" 个备份表\n");
                        showBackupTableDialog(tables, initLog.toString());
                    } else {
                        appendLog("  查询备份表失败", COLOR_STDERR, 1);
                        for (String l : output.split("\n")) appendLog("  " + l, COLOR_STDERR, 1);
                    }
                    infoLabel.setText(code == 0 ? "备份表查询完成" : "备份表查询失败");
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("!! 查询备份表出错: " + e.getMessage(), COLOR_STDERR, 1);
                    infoLabel.setText("查询备份表出错");
                });
            }
        }, "backup-list").start();
    }

    private void showBackupTableDialog(java.util.List<String[]> tableData, String initLog) {
        // 表格：选择（复选框）、序号、原表名称、备份表名称
        String[] columns = {"选择", "序号", "原表名称", "备份表名称"};
        Object[][] data = new Object[tableData.size()][4];
        for (int i = 0; i < tableData.size(); i++) {
            data[i][0] = Boolean.FALSE;
            data[i][1] = (i + 1);
            data[i][2] = tableData.get(i)[0];
            data[i][3] = tableData.get(i)[1];
        }

        javax.swing.table.DefaultTableModel model = new javax.swing.table.DefaultTableModel(data, columns) {
            @Override public boolean isCellEditable(int r, int c) { return c == 0; }
            @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : Object.class; }
        };
        JTable table = new JTable(model);
        table.setFont(new Font("Consolas", Font.PLAIN, 12));
        table.setRowHeight(22);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(50);
        table.getColumnModel().getColumn(0).setMaxWidth(60);
        table.getColumnModel().getColumn(1).setPreferredWidth(50);
        table.getColumnModel().getColumn(1).setMaxWidth(60);
        table.getColumnModel().getColumn(2).setPreferredWidth(180);
        table.getColumnModel().getColumn(3).setPreferredWidth(180);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setRowSelectionAllowed(true);

        // 日志框
        JTextPane logPane = new JTextPane();
        logPane.setEditable(false);
        logPane.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        logPane.setBackground(new Color(30, 30, 30));
        logPane.setForeground(new Color(212, 212, 212));
        StyledDocument logDoc = logPane.getStyledDocument();

        // 帮助说明框（可选中复制）
        JTextPane helpPane = new JTextPane();
        helpPane.setEditable(false);
        helpPane.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        helpPane.setBackground(new Color(30, 30, 30));
        helpPane.setForeground(new Color(212, 212, 212));
        helpPane.setText(
            "--BMXAA0353E - 现有的表未能从备份数据恢复。 必须在配置数据库之前恢复表数据。\n\n"
            + "select TABLENAME from MAXTABLECFG where RESTOREDATA=1;\n\n"
            + "删除流程: 逐表校验备份表存在 -> DROP TABLE 备份表 -> 更新 maxtablecfg.restoredata = 0\n"
            + "(备份表不存在时无需 DROP，直接更新 restoredata = 0)\n");

        // 按钮
        JButton btnSelectAll = createDialogBtn("全选", new Color(95, 99, 104));
        JButton btnCount    = createDialogBtn("查询数据量", new Color(26, 115, 232));
        JButton btnGenInsert = createDialogBtn("插入原表SQL生成", new Color(52, 168, 83));
        JButton btnGenDrop  = createDialogBtn("删除备份表SQL生成", new Color(200, 80, 60));
        JButton btnDrop     = createDialogBtn("删除备份表", new Color(180, 50, 40));
        JButton btnClose    = createDialogBtn("关闭", new Color(100, 100, 100));

        btnGenInsert.setEnabled(false);
        btnGenDrop.setEnabled(false);
        btnDrop.setEnabled(false);

        // 非模态对话框
        JDialog dialog = new JDialog(frame, "备份表管理", false);

        // ---- helper: 追加日志 ----
        java.util.function.BiConsumer<String, Color> appendLogFn = (text, color) -> {
            StyleContext sc = StyleContext.getDefaultStyleContext();
            AttributeSet attr = sc.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, color);
            try {
                logDoc.insertString(logDoc.getLength(), text + "\n", attr);
                logPane.setCaretPosition(logDoc.getLength());
            } catch (BadLocationException ignored) {}
        };

        // ---- helper: 运行 BackupTableQuery 子进程 ----
        java.util.function.Consumer<String> runAction = (fullAction) -> {
            new Thread(() -> {
                try {
                    StringBuilder cp = new StringBuilder();
                    cp.append(classPath);
                    cp.append(File.pathSeparator).append(sksRoot.getAbsolutePath()).append(File.pathSeparator).append("bin");

                    ArrayList<String> cmdList = new ArrayList<>();
                    cmdList.add(javaCmd);
                    cmdList.add("-Dfile.encoding=UTF-8");
                    cmdList.add("-cp");
                    cmdList.add(cp.toString());
                    cmdList.add("BackupTableQuery");
                    cmdList.add("--sks-root=" + sksRoot.getAbsolutePath());
                    // fullAction 格式: "actionName" 或 "actionName|--param=value"
                    int pipeIdx = fullAction.indexOf('|');
                    if (pipeIdx > 0) {
                        cmdList.add("--action=" + fullAction.substring(0, pipeIdx));
                        cmdList.add(fullAction.substring(pipeIdx + 1));
                    } else {
                        cmdList.add("--action=" + fullAction);
                    }

                    ProcessBuilder pb = new ProcessBuilder(cmdList);
                    pb.directory(sksRoot);
                    pb.redirectErrorStream(true);
                    Process p = pb.start();

                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"), 8192)) {
                        String l; while ((l = r.readLine()) != null) {
                            final String ll = l;
                            SwingUtilities.invokeLater(() -> {
                                if (ll.startsWith("INFO|")) {
                                    appendLogFn.accept("  " + ll.substring(5), COLOR_INFO);
                                } else if (ll.startsWith("SQL|")) {
                                    appendLogFn.accept("  " + ll, COLOR_SUCCESS);
                                } else if (ll.startsWith("ERROR|")) {
                                    appendLogFn.accept("  " + ll.substring(6), COLOR_STDERR);
                                } else if (ll.startsWith("COUNT|")) {
                                    String[] parts = ll.substring(6).split("\\|");
                                    if (parts.length >= 4) {
                                        appendLogFn.accept("  " + parts[0] + " (" + parts[1] + "): 原表=" + parts[2] + ", 备份表=" + parts[3],
                                            parts[2].equals("0") && !parts[3].equals("0") ? COLOR_WARN : COLOR_STDOUT);
                                    }
                                } else {
                                    appendLogFn.accept("  " + ll, COLOR_STDOUT);
                                }
                            });
                        }
                    }
                    int ec = p.waitFor();
                    final int exitCode = ec;
                    SwingUtilities.invokeLater(() -> {
                        appendLogFn.accept("-- 操作完毕，退出码: " + exitCode, exitCode == 0 ? COLOR_SUCCESS : COLOR_STDERR);
                    });
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        appendLogFn.accept("!! 操作出错: " + e.getMessage(), COLOR_STDERR);
                    });
                }
            }).start();
        };

        // ---- 按钮事件 ----
        btnSelectAll.addActionListener(e -> {
            boolean anyUnchecked = false;
            for (int i = 0; i < model.getRowCount(); i++) {
                if (!Boolean.TRUE.equals(model.getValueAt(i, 0))) { anyUnchecked = true; break; }
            }
            boolean target = anyUnchecked;
            for (int i = 0; i < model.getRowCount(); i++) model.setValueAt(target, i, 0);
        });

        btnCount.addActionListener(e -> {
            int[] rows = table.getSelectedRows();
            if (rows.length == 0) {
                // 全选
                rows = new int[model.getRowCount()];
                for (int i = 0; i < rows.length; i++) rows[i] = i;
            }
            StringBuilder params = new StringBuilder();
            for (int i = 0; i < rows.length; i++) {
                if (i > 0) params.append(",");
                params.append(model.getValueAt(rows[i], 2)).append("|").append(model.getValueAt(rows[i], 3));
            }
            btnGenInsert.setEnabled(true);
            btnGenDrop.setEnabled(true);
            btnDrop.setEnabled(true);
            appendLogFn.accept(">> 查询数据量 (" + rows.length + " 个表)...", COLOR_START);
            runAction.accept("count|--tables=" + params.toString());
        });

        btnGenInsert.addActionListener(e -> {
            int[] rows = table.getSelectedRows();
            if (rows.length == 0) { appendLogFn.accept("!! 请先选择备份表行", COLOR_STDERR); return; }
            appendLogFn.accept(">> 生成 INSERT SQL...", COLOR_START);
            for (int r : rows) {
                String orig = (String) model.getValueAt(r, 2);
                runAction.accept("sql-insert|--table=" + orig);
            }
        });

        btnGenDrop.addActionListener(e -> {
            int[] rows = table.getSelectedRows();
            if (rows.length == 0) { appendLogFn.accept("!! 请先选择备份表行", COLOR_STDERR); return; }
            appendLogFn.accept(">> 生成 DROP SQL...", COLOR_START);
            for (int r : rows) {
                String orig = (String) model.getValueAt(r, 2);
                runAction.accept("sql-drop|--table=" + orig);
            }
        });

        btnDrop.addActionListener(e -> {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (int i = 0; i < model.getRowCount(); i++) {
                if (Boolean.TRUE.equals(model.getValueAt(i, 0))) {
                    names.add((String) model.getValueAt(i, 2));
                }
            }
            if (names.isEmpty()) { appendLogFn.accept("!! 请先勾选要删除的备份表", COLOR_STDERR); return; }
            int confirm = JOptionPane.showConfirmDialog(dialog,
                "确定要删除选中的 " + names.size() + " 个备份表吗？\n"
                + "备份表存在则 DROP 后更新 maxtablecfg.restoredata = 0\n"
                + "备份表不存在则直接更新 maxtablecfg.restoredata = 0",
                "确认删除", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) return;

            btnDrop.setEnabled(false);
            appendLogFn.accept(">> 删除备份表 (" + names.size() + " 个)...", COLOR_START);
            new Thread(() -> {
                try {
                    StringBuilder cp = new StringBuilder();
                    cp.append(classPath);
                    cp.append(File.pathSeparator).append(sksRoot.getAbsolutePath()).append(File.pathSeparator).append("bin");

                    ArrayList<String> cmdList = new ArrayList<>();
                    cmdList.add(javaCmd);
                    cmdList.add("-Dfile.encoding=UTF-8");
                    cmdList.add("-cp");
                    cmdList.add(cp.toString());
                    cmdList.add("BackupTableQuery");
                    cmdList.add("--sks-root=" + sksRoot.getAbsolutePath());
                    cmdList.add("--action=drop-new");
                    cmdList.add("--tables=" + String.join(",", names));

                    ProcessBuilder pb = new ProcessBuilder(cmdList);
                    pb.directory(sksRoot);
                    pb.redirectErrorStream(true);
                    Process p = pb.start();

                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"), 8192)) {
                        String l; while ((l = r.readLine()) != null) {
                            final String ll = l;
                            SwingUtilities.invokeLater(() -> {
                                if (ll.startsWith("DONE|")) {
                                    String tb = ll.substring(5).trim();
                                    appendLogFn.accept("  " + tb + " 处理完成", COLOR_SUCCESS);
                                    // 从表格中移除该行
                                    for (int i = model.getRowCount() - 1; i >= 0; i--) {
                                        if (tb.equalsIgnoreCase(String.valueOf(model.getValueAt(i, 2)))) {
                                            model.removeRow(i);
                                        }
                                    }
                                } else if (ll.startsWith("SQL|")) {
                                    appendLogFn.accept("  " + ll, COLOR_SUCCESS);
                                } else if (ll.startsWith("INFO|")) {
                                    appendLogFn.accept("  " + ll.substring(5), COLOR_INFO);
                                } else if (ll.startsWith("ERROR|")) {
                                    appendLogFn.accept("  " + ll.substring(6), COLOR_STDERR);
                                } else {
                                    appendLogFn.accept("  " + ll, COLOR_STDOUT);
                                }
                            });
                        }
                    }
                    int ec = p.waitFor();
                    final int exitCode = ec;
                    SwingUtilities.invokeLater(() -> {
                        btnDrop.setEnabled(true);
                        appendLogFn.accept("-- 删除完毕，退出码: " + exitCode, exitCode == 0 ? COLOR_SUCCESS : COLOR_STDERR);
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        btnDrop.setEnabled(true);
                        appendLogFn.accept("!! 删除出错: " + ex.getMessage(), COLOR_STDERR);
                    });
                }
            }, "backup-drop").start();
        });

        btnClose.addActionListener(e -> dialog.dispose());

        // ---- 布局 ----
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        btnPanel.add(btnSelectAll);
        btnPanel.add(btnCount);
        btnPanel.add(btnGenInsert);
        btnPanel.add(btnGenDrop);
        btnPanel.add(btnDrop);
        btnPanel.add(Box.createHorizontalGlue());
        btnPanel.add(btnClose);

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new Dimension(380, 350));
        JScrollPane logScroll = new JScrollPane(logPane);
        JScrollPane helpScroll = new JScrollPane(helpPane);

        JTabbedPane rightTabs = new JTabbedPane();
        rightTabs.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        rightTabs.addTab("日志", logScroll);
        rightTabs.addTab("帮助说明", helpScroll);
        rightTabs.setPreferredSize(new Dimension(380, 350));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScroll, rightTabs);
        splitPane.setDividerLocation(380);
        splitPane.setResizeWeight(0.45);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.add(btnPanel, BorderLayout.NORTH);
        panel.add(splitPane, BorderLayout.CENTER);

        dialog.getContentPane().add(panel);
        dialog.setSize(850, 500);
        dialog.setMinimumSize(new Dimension(700, 400));
        dialog.setLocationRelativeTo(frame);

        // 写入初始日志（备份表查询结果 SQL）
        if (initLog != null && !initLog.isEmpty()) {
            StyleContext sc = StyleContext.getDefaultStyleContext();
            AttributeSet attr = sc.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, COLOR_INFO);
            try {
                logDoc.insertString(0, initLog, attr);
            } catch (BadLocationException ignored) {}
        }

        dialog.setVisible(true);
    }

    // ============================================================
    // 验证全库密码 — 参照 psdi.tools.ValidateCryptoKey(-l -e -k) 验证逻辑
    //   1. 查出所有 CRYPTO 字段
    //   2. 按 UPGRADEALGORITHM 选择 AES(新)/DESede(旧) 算法, 用 mxe.security.crypto.key 构建 MXCipher 同款加解密器
    //   3. 逐行 解密+回加密 round-trip 验证, 成功/失败分类显示
    // ============================================================
    private void runValidateCryptoKey() {
        showValidateCryptoKeyDialog();
    }

    private void showValidateCryptoKeyDialog() {
        // 成功表: 表名 / 字段名 / 主键 / 字段内容(字节码)
        javax.swing.table.DefaultTableModel okModel = new javax.swing.table.DefaultTableModel(
            new Object[]{"表名", "字段名", "主键", "字段内容(字节码)"}, 0);
        JTable okTable = new JTable(okModel);
        okTable.setFont(new Font("Consolas", Font.PLAIN, 12));
        okTable.setRowHeight(22);
        okTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        okTable.getColumnModel().getColumn(0).setPreferredWidth(180);
        okTable.getColumnModel().getColumn(1).setPreferredWidth(180);
        okTable.getColumnModel().getColumn(2).setPreferredWidth(160);
        okTable.getColumnModel().getColumn(3).setPreferredWidth(400);
        addTableCopySupport(okTable);

        // 失败表: 勾选列 + 表名/字段名/主键/字段内容/错误信息
        javax.swing.table.DefaultTableModel failModel = new javax.swing.table.DefaultTableModel(
            new Object[]{"选择", "表名", "字段名", "主键", "字段内容(字节码)", "错误信息"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return c == 0; }
            @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : Object.class; }
        };
        JTable failTable = new JTable(failModel);
        failTable.setFont(new Font("Consolas", Font.PLAIN, 12));
        failTable.setRowHeight(22);
        failTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        failTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        failTable.getColumnModel().getColumn(1).setPreferredWidth(160);
        failTable.getColumnModel().getColumn(2).setPreferredWidth(160);
        failTable.getColumnModel().getColumn(3).setPreferredWidth(120);
        failTable.getColumnModel().getColumn(4).setPreferredWidth(300);
        failTable.getColumnModel().getColumn(5).setPreferredWidth(260);
        // 表名过滤用行排序器（过滤只作用于视图, 缓存数据不变）
        javax.swing.table.TableRowSorter<javax.swing.table.DefaultTableModel> failSorter =
            new javax.swing.table.TableRowSorter<>(failModel);
        failTable.setRowSorter(failSorter);
        final java.util.Set<String> failFilterTables = new java.util.LinkedHashSet<>();
        addTableCopySupport(failTable);

        // 日志框
        JTextPane logPane = new JTextPane();
        logPane.setEditable(false);
        logPane.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        logPane.setBackground(new Color(30, 30, 30));
        logPane.setForeground(new Color(212, 212, 212));
        StyledDocument logDoc = logPane.getStyledDocument();

        // 分类标签页
        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        tabs.addTab("成功(0)", new JScrollPane(okTable));
        tabs.addTab("失败(0)", new JScrollPane(failTable));
        tabs.addTab("日志", new JScrollPane(logPane));

        // 按钮
        JButton btnValidate = createDialogBtn("验证", new Color(26, 115, 232));
        JButton btnSelectAllFail = createDialogBtn("全选", new Color(95, 99, 104));
        JButton btnFilterTable = createDialogBtn("表名过滤", new Color(120, 90, 170));
        JButton btnResetPwd = createDialogBtn("重置密码", new Color(52, 168, 83));
        JButton btnResetApikey = createDialogBtn("重置APIKEY", new Color(200, 130, 40));
        JButton btnClose    = createDialogBtn("关闭", new Color(100, 100, 100));

        // 非模态对话框
        JDialog dialog = new JDialog(frame, "验证全库密码", false);

        // ---- helper: 追加日志 ----
        java.util.function.BiConsumer<String, Color> appendLogFn = (text, color) -> {
            StyleContext sc = StyleContext.getDefaultStyleContext();
            AttributeSet attr = sc.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, color);
            try {
                logDoc.insertString(logDoc.getLength(), text + "\n", attr);
                logPane.setCaretPosition(logDoc.getLength());
            } catch (BadLocationException ignored) {}
        };

        btnValidate.addActionListener(e -> {
            okModel.setRowCount(0);
            failModel.setRowCount(0);
            try { logDoc.remove(0, logDoc.getLength()); } catch (BadLocationException ignored) {}
            tabs.setTitleAt(0, "成功(0)");
            tabs.setTitleAt(1, "失败(0)");

            btnValidate.setEnabled(false);
            btnResetPwd.setEnabled(false);
            btnResetApikey.setEnabled(false);
            tabs.setSelectedIndex(2); // 切到日志看进度
            appendLogFn.accept(">> 验证全库密码...", COLOR_START);

            final int[] okCount = {0};
            final int[] failCount = {0};
            new Thread(() -> {
                try {
                    StringBuilder cp = new StringBuilder();
                    cp.append(classPath);
                    cp.append(File.pathSeparator).append(sksRoot.getAbsolutePath()).append(File.pathSeparator).append("bin");

                    ArrayList<String> cmdList = new ArrayList<>();
                    cmdList.add(javaCmd);
                    cmdList.add("-Dfile.encoding=UTF-8");
                    cmdList.add("-cp");
                    cmdList.add(cp.toString());
                    cmdList.add("ValidateCryptoKeyTool");
                    cmdList.add("--sks-root=" + sksRoot.getAbsolutePath());
                    if (!cryptoTableIdMap.isEmpty()) {
                        StringBuilder pm = new StringBuilder();
                        for (Map.Entry<String, String> en : cryptoTableIdMap.entrySet()) {
                            if (pm.length() > 0) pm.append(",");
                            pm.append(en.getKey()).append(":").append(en.getValue());
                        }
                        cmdList.add("--pkmap=" + pm);
                    }

                    ProcessBuilder pb = new ProcessBuilder(cmdList);
                    pb.directory(sksRoot);
                    pb.redirectErrorStream(true);
                    Process p = pb.start();

                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"), 8192)) {
                        String l; while ((l = r.readLine()) != null) {
                            final String ll = l;
                            SwingUtilities.invokeLater(() -> {
                                if (ll.startsWith("OK|")) {
                                    String[] s = ll.split("\\|", 5);
                                    if (s.length >= 5) {
                                        okModel.addRow(new Object[]{s[1], s[2], s[3], s[4]});
                                        okCount[0]++;
                                        tabs.setTitleAt(0, "成功(" + okCount[0] + ")");
                                    }
                                } else if (ll.startsWith("FAIL|")) {
                                    String[] s = ll.split("\\|", 6);
                                    if (s.length >= 6) {
                                        failModel.addRow(new Object[]{Boolean.FALSE, s[1], s[2], s[3], s[4], s[5]});
                                        failCount[0]++;
                                        tabs.setTitleAt(1, "失败(" + failCount[0] + ")");
                                    }
                                } else if (ll.startsWith("SQL|")) {
                                    appendLogFn.accept("  " + ll, COLOR_SUCCESS);
                                } else if (ll.startsWith("INFO|")) {
                                    appendLogFn.accept("  " + ll.substring(5), COLOR_INFO);
                                } else if (ll.startsWith("ERROR|")) {
                                    appendLogFn.accept("  " + ll.substring(6), COLOR_STDERR);
                                } else if (ll.startsWith("STAT|")) {
                                    appendLogFn.accept("  " + ll.substring(5), COLOR_INFO);
                                } else if (ll.startsWith("DONE|")) {
                                    String[] s = ll.split("\\|", 3);
                                    if (s.length >= 3) {
                                        appendLogFn.accept("-- 验证完毕: 成功 " + s[1] + " 条, 失败 " + s[2] + " 条",
                                            "0".equals(s[2]) ? COLOR_SUCCESS : COLOR_STDERR);
                                    }
                                } else {
                                    appendLogFn.accept("  " + ll, COLOR_STDOUT);
                                }
                            });
                        }
                    }
                    int ec = p.waitFor();
                    final int exitCode = ec;
                    SwingUtilities.invokeLater(() -> {
                        btnValidate.setEnabled(true);
                        btnResetPwd.setEnabled(true);
                        btnResetApikey.setEnabled(true);
                        if (exitCode != 0) {
                            appendLogFn.accept("-- 验证异常，退出码: " + exitCode, COLOR_STDERR);
                        }
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        btnValidate.setEnabled(true);
                        btnResetPwd.setEnabled(true);
                        btnResetApikey.setEnabled(true);
                        appendLogFn.accept("!! 验证出错: " + ex.getMessage(), COLOR_STDERR);
                    });
                }
            }, "validate-cryptokey").start();
        });

        // ---- 按钮事件 ----
        // 全选: 作用于当前过滤后可见的行（切换式: 有未选则全选, 已全选则取消全选）
        btnSelectAllFail.addActionListener(e -> {
            boolean anyUnchecked = false;
            for (int v = 0; v < failTable.getRowCount(); v++) {
                if (!Boolean.TRUE.equals(failModel.getValueAt(failTable.convertRowIndexToModel(v), 0))) { anyUnchecked = true; break; }
            }
            boolean target = anyUnchecked;
            for (int v = 0; v < failTable.getRowCount(); v++) {
                failModel.setValueAt(target, failTable.convertRowIndexToModel(v), 0);
            }
        });

        // 表名过滤: 在缓存数据中按表名多选过滤（只影响视图, 不重新查询）
        btnFilterTable.addActionListener(e -> {
            java.util.TreeSet<String> names = new java.util.TreeSet<>();
            for (int i = 0; i < failModel.getRowCount(); i++) {
                names.add(String.valueOf(failModel.getValueAt(i, 1)));
            }
            if (names.isEmpty()) {
                appendLogFn.accept("!! 失败列表为空, 无表名可过滤", COLOR_STDERR);
                return;
            }
            javax.swing.JList<String> list = new javax.swing.JList<>(names.toArray(new String[0]));
            // 保留当前过滤状态
            if (!failFilterTables.isEmpty()) {
                java.util.List<String> arr = new ArrayList<>(names);
                int[] sel = arr.stream().filter(failFilterTables::contains).mapToInt(arr::indexOf).toArray();
                if (sel.length > 0) list.setSelectedIndices(sel);
            }
            JScrollPane sp = new JScrollPane(list);
            sp.setPreferredSize(new Dimension(260, 320));
            int rc = JOptionPane.showConfirmDialog(dialog, sp,
                "表名过滤(可多选; 不选直接确定 = 清除过滤)", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (rc != JOptionPane.OK_OPTION) return;
            failFilterTables.clear();
            failFilterTables.addAll(list.getSelectedValuesList());
            if (failFilterTables.isEmpty()) {
                failSorter.setRowFilter(null);
                appendLogFn.accept(">> 已清除表名过滤, 显示全部 " + failModel.getRowCount() + " 行", COLOR_INFO);
            } else {
                failSorter.setRowFilter(new javax.swing.RowFilter<javax.swing.table.DefaultTableModel, Integer>() {
                    @Override public boolean include(Entry<? extends javax.swing.table.DefaultTableModel, ? extends Integer> entry) {
                        return failFilterTables.contains(entry.getStringValue(1));
                    }
                });
                appendLogFn.accept(">> 表名过滤: " + String.join(", ", failFilterTables)
                    + " (" + failTable.getRowCount() + "/" + failModel.getRowCount() + " 行)", COLOR_INFO);
            }
        });

        // 重置密码: 勾选行 -> 按表.字段分组并收集主键值 -> 输入明文密码 -> 子进程按字段类型加密 -> 生成带 where 的 update（仅生成, 不执行）
        btnResetPwd.addActionListener(e -> {
            // key: TABLE.COLUMN, value: 该字段勾选行的主键值列表
            java.util.LinkedHashMap<String, java.util.List<String>> picked = new java.util.LinkedHashMap<>();
            for (int i = 0; i < failModel.getRowCount(); i++) {
                if (Boolean.TRUE.equals(failModel.getValueAt(i, 0))) {
                    String fkey = failModel.getValueAt(i, 1) + "." + failModel.getValueAt(i, 2);
                    java.util.List<String> pks = picked.computeIfAbsent(fkey, k -> new ArrayList<>());
                    pks.add(String.valueOf(failModel.getValueAt(i, 3)));
                }
            }
            if (picked.isEmpty()) {
                appendLogFn.accept("!! 请先在\"失败\"列表勾选要重置密码的行", COLOR_STDERR);
                tabs.setSelectedIndex(1);
                return;
            }
            // 密码输入框
            javax.swing.JPasswordField pwdField = new javax.swing.JPasswordField(22);
            JPanel pwdPanel = new JPanel(new java.awt.GridLayout(2, 1, 0, 4));
            pwdPanel.add(new JLabel("输入新密码（勾选字段的整表数据都将重置为该密码）:"));
            pwdPanel.add(pwdField);
            int rc = JOptionPane.showConfirmDialog(dialog, pwdPanel,
                "重置密码 - " + picked.size() + " 个字段", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (rc != JOptionPane.OK_OPTION) return;
            final String newPwd = new String(pwdField.getPassword());
            if (newPwd.isEmpty()) {
                appendLogFn.accept("!! 密码不能为空", COLOR_STDERR);
                return;
            }

            long totalRows = picked.values().stream().mapToInt(java.util.List::size).sum();
            btnResetPwd.setEnabled(false);
            tabs.setSelectedIndex(2);
            appendLogFn.accept(">> 重置密码 (" + picked.size() + " 个字段, " + totalRows + " 条记录: " + String.join(", ", picked.keySet()) + ")...", COLOR_START);
            new Thread(() -> {
                try {
                    StringBuilder cp = new StringBuilder();
                    cp.append(classPath);
                    cp.append(File.pathSeparator).append(sksRoot.getAbsolutePath()).append(File.pathSeparator).append("bin");

                    ArrayList<String> cmdList = new ArrayList<>();
                    cmdList.add(javaCmd);
                    cmdList.add("-Dfile.encoding=UTF-8");
                    cmdList.add("-cp");
                    cmdList.add(cp.toString());
                    cmdList.add("ValidateCryptoKeyTool");
                    cmdList.add("--sks-root=" + sksRoot.getAbsolutePath());
                    cmdList.add("--action=encrypt");
                    cmdList.add("--value=" + newPwd);
                    cmdList.add("--fields=" + String.join(",", picked.keySet()));

                    ProcessBuilder pb = new ProcessBuilder(cmdList);
                    pb.directory(sksRoot);
                    pb.redirectErrorStream(true);
                    Process proc = pb.start();

                    try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"), 8192)) {
                        String l; while ((l = r.readLine()) != null) {
                            final String ll = l;
                            SwingUtilities.invokeLater(() -> {
                                if (ll.startsWith("ENC|")) {
                                    String[] s = ll.split("\\|", 4);
                                    if (s.length >= 4) {
                                        String hex = s[3];
                                        // where 条件: 勾选行的主键值 in (...), 避免整表误更新
                                        java.util.List<String> pks = picked.get(s[1] + "." + s[2]);
                                        String where = "";
                                        if (pks != null && !pks.isEmpty()) {
                                            String pkCol = cryptoTableIdMap.getOrDefault(s[1].toUpperCase(), s[1].toUpperCase() + "ID");
                                            StringBuilder inList = new StringBuilder();
                                            for (String pv : pks) {
                                                if (inList.length() > 0) inList.append(",");
                                                inList.append(pv.matches("\\d+") ? pv : "'" + pv + "'");
                                            }
                                            where = " where " + pkCol + " in (" + inList + ")";
                                        }
                                        String sql = "update " + s[1] + " set " + s[2]
                                            + " = CAST(x'" + hex + "' AS BLOB(" + (hex.length() / 2) + "))" + where + ";";
                                        appendLogFn.accept("  " + sql, COLOR_SUCCESS);
                                    }
                                } else if (ll.startsWith("ERROR|")) {
                                    appendLogFn.accept("  " + ll.substring(6), COLOR_STDERR);
                                } else if (ll.startsWith("INFO|")) {
                                    appendLogFn.accept("  " + ll.substring(5), COLOR_INFO);
                                } else if (ll.startsWith("DONE|")) {
                                    appendLogFn.accept("-- 共生成 " + ll.substring(5) + " 条 update 语句（未执行，请人工核对后执行）", COLOR_INFO);
                                } else {
                                    appendLogFn.accept("  " + ll, COLOR_STDOUT);
                                }
                            });
                        }
                    }
                    int ec = proc.waitFor();
                    final int exitCode = ec;
                    SwingUtilities.invokeLater(() -> {
                        btnResetPwd.setEnabled(true);
                        if (exitCode != 0) {
                            appendLogFn.accept("-- 加密异常，退出码: " + exitCode, COLOR_STDERR);
                        }
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        btnResetPwd.setEnabled(true);
                        appendLogFn.accept("!! 重置密码出错: " + ex.getMessage(), COLOR_STDERR);
                    });
                }
            }, "reset-password").start();
        });

        // 重置APIKEY: 勾选的 APIKEYTOKEN.APIKEY 失败行, 每行随机 UUID(无-)加密后 base64 生成 update（仅生成, 不执行）
        btnResetApikey.addActionListener(e -> {
            java.util.List<String> pkVals = new ArrayList<>();
            for (int i = 0; i < failModel.getRowCount(); i++) {
                if (Boolean.TRUE.equals(failModel.getValueAt(i, 0))
                        && "APIKEYTOKEN".equalsIgnoreCase(String.valueOf(failModel.getValueAt(i, 1)))
                        && "APIKEY".equalsIgnoreCase(String.valueOf(failModel.getValueAt(i, 2)))) {
                    pkVals.add(String.valueOf(failModel.getValueAt(i, 3)));
                }
            }
            if (pkVals.isEmpty()) {
                appendLogFn.accept("!! 请先在\"失败\"列表勾选 APIKEYTOKEN.APIKEY 的失败行", COLOR_STDERR);
                tabs.setSelectedIndex(1);
                return;
            }
            btnResetApikey.setEnabled(false);
            tabs.setSelectedIndex(2);
            appendLogFn.accept(">> 重置APIKEY (" + pkVals.size() + " 条, 每行随机 UUID, 无-符号)...", COLOR_START);
            new Thread(() -> {
                try {
                    StringBuilder cp = new StringBuilder();
                    cp.append(classPath);
                    cp.append(File.pathSeparator).append(sksRoot.getAbsolutePath()).append(File.pathSeparator).append("bin");

                    ArrayList<String> cmdList = new ArrayList<>();
                    cmdList.add(javaCmd);
                    cmdList.add("-Dfile.encoding=UTF-8");
                    cmdList.add("-cp");
                    cmdList.add(cp.toString());
                    cmdList.add("ValidateCryptoKeyTool");
                    cmdList.add("--sks-root=" + sksRoot.getAbsolutePath());
                    cmdList.add("--action=apikey");
                    cmdList.add("--pkvals=" + String.join(",", pkVals));

                    ProcessBuilder pb = new ProcessBuilder(cmdList);
                    pb.directory(sksRoot);
                    pb.redirectErrorStream(true);
                    Process proc = pb.start();

                    try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"), 8192)) {
                        String l; while ((l = r.readLine()) != null) {
                            final String ll = l;
                            SwingUtilities.invokeLater(() -> {
                                if (ll.startsWith("ENC|")) {
                                    String[] s = ll.split("\\|", 5);
                                    if (s.length >= 5) {
                                        // ENC|APIKEYTOKEN|APIKEY|pk|b64
                                        String pkCol = cryptoTableIdMap.getOrDefault(s[1].toUpperCase(), s[1].toUpperCase() + "ID");
                                        String pkCond = s[3].matches("\\d+") ? s[3] : "'" + s[3] + "'";
                                        String sql = "update " + s[1] + " set " + s[2]
                                            + " = '" + s[4] + "'"
                                            + " where " + pkCol + " = " + pkCond + ";";
                                        appendLogFn.accept("  " + sql, COLOR_SUCCESS);
                                    }
                                } else if (ll.startsWith("INFO|")) {
                                    // 明文 UUID 行用 SQL 注释格式, 便于整段复制执行
                                    appendLogFn.accept("-- " + ll.substring(5), COLOR_INFO);
                                } else if (ll.startsWith("ERROR|")) {
                                    appendLogFn.accept("  " + ll.substring(6), COLOR_STDERR);
                                } else if (ll.startsWith("DONE|")) {
                                    appendLogFn.accept("-- 共生成 " + ll.substring(5) + " 条 update 语句（未执行，请人工核对后执行；明文 UUID 见上方注释行）", COLOR_INFO);
                                } else {
                                    appendLogFn.accept("  " + ll, COLOR_STDOUT);
                                }
                            });
                        }
                    }
                    int ec = proc.waitFor();
                    final int exitCode = ec;
                    SwingUtilities.invokeLater(() -> {
                        btnResetApikey.setEnabled(true);
                        if (exitCode != 0) {
                            appendLogFn.accept("-- 加密异常，退出码: " + exitCode, COLOR_STDERR);
                        }
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        btnResetApikey.setEnabled(true);
                        appendLogFn.accept("!! 重置APIKEY出错: " + ex.getMessage(), COLOR_STDERR);
                    });
                }
            }, "reset-apikey").start();
        });

        btnClose.addActionListener(e -> dialog.dispose());

        // ---- 布局 ----
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        btnPanel.add(btnValidate);
        btnPanel.add(btnSelectAllFail);
        btnPanel.add(btnFilterTable);
        btnPanel.add(btnResetPwd);
        btnPanel.add(btnResetApikey);
        btnPanel.add(Box.createHorizontalGlue());
        btnPanel.add(btnClose);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.add(btnPanel, BorderLayout.NORTH);
        panel.add(tabs, BorderLayout.CENTER);

        dialog.getContentPane().add(panel);
        dialog.setSize(1050, 620);
        dialog.setMinimumSize(new Dimension(820, 450));
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    // ============================================================
    // 表格复制支持: Ctrl+C 复制选中单元格(多格 Tab/换行分隔), 右键弹出"复制单元格值"
    // ============================================================
    private void addTableCopySupport(JTable table) {
        table.setCellSelectionEnabled(true);
        // Ctrl+C
        table.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C,
                java.awt.event.InputEvent.CTRL_DOWN_MASK), "sks-copy-cells");
        table.getActionMap().put("sks-copy-cells", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                copySelectedCells(table);
            }
        });
        // 右键菜单
        javax.swing.JPopupMenu popup = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem miCopy = new javax.swing.JMenuItem("复制单元格值");
        miCopy.addActionListener(e -> {
            int r = table.getSelectedRow(), c = table.getSelectedColumn();
            if (r >= 0 && c >= 0) {
                Object v = table.getValueAt(r, c);
                setClipboardText(v == null ? "" : String.valueOf(v));
            }
        });
        popup.add(miCopy);
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            private void show(java.awt.event.MouseEvent ev) {
                if (!ev.isPopupTrigger()) return;
                int r = table.rowAtPoint(ev.getPoint());
                int c = table.columnAtPoint(ev.getPoint());
                if (r >= 0 && c >= 0) {
                    table.changeSelection(r, c, false, false);
                    popup.show(table, ev.getX(), ev.getY());
                }
            }
            @Override public void mousePressed(java.awt.event.MouseEvent ev) { show(ev); }
            @Override public void mouseReleased(java.awt.event.MouseEvent ev) { show(ev); }
        });
    }

    // 复制选中单元格区域: 列间 Tab, 行间换行
    private void copySelectedCells(JTable table) {
        int[] rows = table.getSelectedRows();
        int[] cols = table.getSelectedColumns();
        if (rows.length == 0 || cols.length == 0) return;
        StringBuilder sb = new StringBuilder();
        for (int r : rows) {
            for (int i = 0; i < cols.length; i++) {
                if (i > 0) sb.append("\t");
                Object v = table.getValueAt(r, cols[i]);
                sb.append(v == null ? "" : String.valueOf(v));
            }
            sb.append("\r\n");
        }
        setClipboardText(sb.toString().trim());
    }

    private void setClipboardText(String s) {
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new java.awt.datatransfer.StringSelection(s), null);
    }

    // ============================================================
    // 查看用户 / 重置密码
    // ============================================================
    private void runUserList() {
        String sql = "select userid,personid,LOGINID,(select displayname from person where personid=maxuser.personid) as displayname from maxuser";
        appendLog(">> 查询用户列表", COLOR_START, 1);
        appendLog(">> SQL: " + sql, COLOR_INFO, 1);
        infoLabel.setText("正在查询用户...");

        new Thread(() -> {
            try {
                String escapedSql = sql.replace("\"", "\\\"");
                ArrayList<String> cmdList = new ArrayList<>();
                cmdList.add(javaCmd);
                cmdList.add("-Dfile.encoding=UTF-8");
                cmdList.add("-cp");
                cmdList.add(sksRoot.getAbsolutePath() + File.pathSeparator + classPath);
                cmdList.add("ConfigDBUI");
                cmdList.add("--sql=" + escapedSql);
                cmdList.add("--sks-root=" + sksRoot.getAbsolutePath());

                ProcessBuilder pb = new ProcessBuilder(cmdList);
                pb.directory(sksRoot);
                pb.redirectErrorStream(true);
                Process p = pb.start();

                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"), 8192)) {
                    String l; while ((l = r.readLine()) != null) { sb.append(l).append("\n"); }
                }
                int exitCode = p.waitFor();

                final String output = sb.toString();
                final int code = exitCode;

                SwingUtilities.invokeLater(() -> {
                    if (code == 0) {
                        java.util.List<String[]> users = new java.util.ArrayList<>();
                        for (String line : output.split("\n")) {
                            if (line.startsWith("  USERID=")) {
                                // 输出格式: USERID=xxx, PERSONID=yyy, LOGINID=zzz, DISPLAYNAME=www
                                String[] parts = line.trim().split(", ");
                                String[] row = new String[4];
                                for (String cell : parts) {
                                    int idx = cell.indexOf('=');
                                    if (idx < 0) continue;
                                    String name = cell.substring(0, idx);
                                    String val = cell.substring(idx + 1);
                                    if ("USERID".equals(name)) row[0] = val;
                                    else if ("PERSONID".equals(name)) row[1] = val;
                                    else if ("LOGINID".equals(name)) row[2] = val;
                                    else if ("DISPLAYNAME".equals(name)) row[3] = "null".equals(val) ? "" : val;
                                }
                                if (row[0] != null) users.add(row);
                            }
                        }
                        showUserDialog(users);
                    } else {
                        appendLog("  查询用户失败", COLOR_STDERR, 1);
                        for (String l : output.split("\n")) appendLog("  " + l, COLOR_STDERR, 1);
                    }
                    infoLabel.setText(code == 0 ? "用户查询完成" : "用户查询失败");
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("!! 查询用户出错: " + e.getMessage(), COLOR_STDERR, 1);
                    infoLabel.setText("查询用户出错");
                });
            }
        }, "user-list").start();
    }

    private void showUserDialog(java.util.List<String[]> userData) {
        // 表格
        String[] columns = {"用户ID", "人员ID", "登录ID", "显示名称", "操作"};
        Object[][] data = new Object[userData.size()][5];
        for (int i = 0; i < userData.size(); i++) {
            data[i][0] = userData.get(i)[0];
            data[i][1] = userData.get(i)[1];
            data[i][2] = userData.get(i)[2];
            data[i][3] = userData.get(i)[3];
        }

        javax.swing.table.DefaultTableModel model = new javax.swing.table.DefaultTableModel(data, columns) {
            @Override public boolean isCellEditable(int r, int c) { return c == 4; }
        };
        JTable table = new JTable(model);
        table.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        table.setRowHeight(26);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(80);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(100);
        table.getColumnModel().getColumn(3).setPreferredWidth(160);
        table.getColumnModel().getColumn(4).setPreferredWidth(80);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowSelectionAllowed(true);

        // 日志框
        JTextPane logPane = new JTextPane();
        logPane.setEditable(false);
        logPane.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        logPane.setBackground(new Color(30, 30, 30));
        logPane.setForeground(new Color(212, 212, 212));
        StyledDocument logDoc = logPane.getStyledDocument();

        // 非模态对话框
        JDialog dialog = new JDialog(frame, "用户管理", false);

        // ---- helper: 追加日志 ----
        java.util.function.BiConsumer<String, Color> appendLogFn = (text, color) -> {
            StyleContext sc = StyleContext.getDefaultStyleContext();
            AttributeSet attr = sc.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, color);
            try {
                logDoc.insertString(logDoc.getLength(), text + "\n", attr);
                logPane.setCaretPosition(logDoc.getLength());
            } catch (BadLocationException ignored) {}
        };

        // ---- helper: 运行 ResetPasswords 子进程（新密码通过临时 .bytes 文件传入）----
        java.util.function.BiConsumer<String, String> runReset = (userid, newpwd) -> {
            if (running) {
                appendLogFn.accept("!! ConfigDB/RestoreFromBackup 执行中，无法重置密码", COLOR_STDERR);
                return;
            }
            new Thread(() -> {
                File bytesFile = null;
                try {
                    // 生成临时 .bytes 文件: 表名, 关键列, 关键值, 更新列, 更新值
                    String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new java.util.Date());
                    File logsDir = new File(sksRoot, "logs");
                    if (!logsDir.exists()) logsDir.mkdirs();
                    String prefix = new File(logsDir, "reset_" + userid + "_" + ts).getAbsolutePath();
                    bytesFile = new File(prefix + ".bytes");
                    try (PrintWriter w = new PrintWriter(new FileWriter(bytesFile))) {
                        w.println("maxuser, userid, " + userid + ", password, " + newpwd);
                    }
                    appendLogFn.accept("  bytes 文件: " + bytesFile.getAbsolutePath(), COLOR_INFO);

                    StringBuilder cp = new StringBuilder();
                    cp.append(sksRoot.getAbsolutePath()).append(File.pathSeparator).append(classPath);

                    ArrayList<String> cmdList = new ArrayList<>();
                    cmdList.add(javaCmd);
                    cmdList.add("-Dfile.encoding=UTF-8");
                    cmdList.add("-cp");
                    cmdList.add(cp.toString());
                    cmdList.add("psdi.util.ResetPasswords");
                    cmdList.add("-k" + propDir);
                    cmdList.add("-u" + userid);
                    cmdList.add("-i" + prefix);

                    ProcessBuilder pb = new ProcessBuilder(cmdList);
                    pb.directory(sksRoot);
                    pb.redirectErrorStream(true);
                    Process p = pb.start();

                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"), 8192)) {
                        String l; while ((l = r.readLine()) != null) {
                            final String ll = l;
                            SwingUtilities.invokeLater(() -> appendLogFn.accept("  " + ll, COLOR_STDOUT));
                        }
                    }
                    int ec = p.waitFor();
                    final int exitCode = ec;
                    SwingUtilities.invokeLater(() -> {
                        appendLogFn.accept("-- 重置密码完毕，退出码: " + exitCode, exitCode == 0 ? COLOR_SUCCESS : COLOR_STDERR);
                    });
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        appendLogFn.accept("!! 重置密码出错: " + e.getMessage(), COLOR_STDERR);
                    });
                } finally {
                    if (bytesFile != null) bytesFile.delete();
                }
            }, "reset-password").start();
        };

        // ---- 操作列按钮（第 5 列）----
        JButton opBtn = new JButton("重置密码");
        opBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        opBtn.setForeground(Color.WHITE);
        opBtn.setBackground(new Color(200, 80, 60));
        opBtn.setOpaque(true);
        opBtn.setFocusPainted(false);
        opBtn.setBorderPainted(false);
        opBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        opBtn.setMargin(new Insets(2, 10, 2, 10));

        table.getColumnModel().getColumn(4).setCellRenderer((t, v, isS, isF, row, col) -> {
            JButton b = new JButton("重置密码");
            b.setFont(opBtn.getFont());
            b.setForeground(opBtn.getForeground());
            b.setBackground(opBtn.getBackground());
            b.setOpaque(true);
            b.setFocusPainted(false);
            b.setBorderPainted(false);
            b.setCursor(new Cursor(Cursor.HAND_CURSOR));
            b.setMargin(opBtn.getMargin());
            return b;
        });

        class OpButtonEditor extends AbstractCellEditor implements javax.swing.table.TableCellEditor, ActionListener {
            private final JButton b;
            private int editRow;
            OpButtonEditor() {
                b = new JButton("重置密码");
                b.setFont(opBtn.getFont());
                b.setForeground(opBtn.getForeground());
                b.setBackground(opBtn.getBackground());
                b.setOpaque(true);
                b.setFocusPainted(false);
                b.setBorderPainted(false);
                b.setCursor(new Cursor(Cursor.HAND_CURSOR));
                b.setMargin(opBtn.getMargin());
                b.addActionListener(this);
            }
            @Override public Component getTableCellEditorComponent(JTable t, Object v, boolean s1, int r, int c) {
                editRow = r;
                return b;
            }
            @Override public Object getCellEditorValue() { return "重置密码"; }
            @Override public void actionPerformed(ActionEvent e) {
                fireEditingStopped();
                String userid = String.valueOf(model.getValueAt(editRow, 0));
                String displayName = String.valueOf(model.getValueAt(editRow, 3));

                JPasswordField pw = new JPasswordField(16);
                JPasswordField pw2 = new JPasswordField(16);
                Object[] msg = {"用户: " + userid + (displayName.isEmpty() ? "" : " (" + displayName + ")"), " 输入新密码:", pw, " 再次输入:", pw2};
                int r = JOptionPane.showConfirmDialog(dialog, msg, "重置密码 [" + userid + "]", JOptionPane.OK_CANCEL_OPTION);
                if (r != JOptionPane.OK_OPTION) return;
                String p1 = new String(pw.getPassword());
                String p2 = new String(pw2.getPassword());
                if (p1.isEmpty()) {
                    appendLogFn.accept("!! 密码不能为空", COLOR_STDERR);
                    return;
                }
                if (!p1.equals(p2)) {
                    appendLogFn.accept("!! 两次输入的密码不一致", COLOR_STDERR);
                    return;
                }
                appendLogFn.accept(">> 重置密码: " + userid, COLOR_START);
                runReset.accept(userid, p1);
            }
        }
        table.getColumnModel().getColumn(4).setCellEditor(new OpButtonEditor());

        // ---- 布局 ----
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new Dimension(520, 350));
        JScrollPane logScroll = new JScrollPane(logPane);
        logScroll.setPreferredSize(new Dimension(400, 350));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScroll, logScroll);
        splitPane.setDividerLocation(520);
        splitPane.setResizeWeight(0.55);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.add(splitPane, BorderLayout.CENTER);

        dialog.getContentPane().add(panel);
        dialog.setSize(1000, 500);
        dialog.setMinimumSize(new Dimension(800, 400));
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private JButton createDialogBtn(String text, Color bg) {
        Color disabledBg = new Color(0x4A, 0x50, 0x56);  // 深灰
        Color disabledFg = new Color(0xE8, 0x38, 0x38);  // 暗红
        Color disabledBorder = new Color(0xFF, 0x33, 0x33); // 红色细描边
        JButton btn = new JButton(text) {
            public void setEnabled(boolean enabled) {
                super.setEnabled(enabled);
                if (enabled) {
                    setBackground(bg);
                    setForeground(Color.WHITE);
                    setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
                    setCursor(new Cursor(Cursor.HAND_CURSOR));
                } else {
                    setBackground(disabledBg);
                    setForeground(disabledFg);
                    setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(disabledBorder, 1),
                        BorderFactory.createEmptyBorder(7, 17, 7, 17)
                    ));
                    setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
                }
            }
        };
        btn.setFont(BTN_FONT);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setOpaque(true);
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) {
                if (btn.isEnabled()) btn.setBackground(bg.darker());
            }
            public void mouseExited(MouseEvent e) {
                if (btn.isEnabled()) btn.setBackground(bg);
            }
        });
        btn.setEnabled(true);
        return btn;
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
        loadedJars.clear();

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
            loadedJars.add(boJar.getAbsolutePath());
        } else {
            appendIfExists(cp, boDir);
        }

        File snakeyamlJar = new File(sksRoot, "lib/snakeyaml-2.5.jar");
        if (snakeyamlJar.exists()) {
            cp.append(snakeyamlJar.getAbsolutePath()).append(File.pathSeparator);
            loadedJars.add(snakeyamlJar.getAbsolutePath());
        }

        File binDir = new File(sksRoot, "bin");
        appendIfExists(cp, binDir);

        if (libDir.exists() && libDir.isDirectory()) {
            File[] jars = libDir.listFiles((d, n) -> n.endsWith(".jar"));
            if (jars != null) {
                Arrays.sort(jars);
                for (File j : jars) {
                    cp.append(j.getAbsolutePath()).append(File.pathSeparator);
                    loadedJars.add(j.getAbsolutePath());
                }
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
        btnShowJars = createButton("已加载 JAR", new Color(130, 130, 130), e -> showLoadedJars());
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
        rightRow1.add(btnShowJars);
        rightRow1.add(Box.createHorizontalStrut(8));
        rightRow1.add(btnSwitchEnv);
        // 调试模式切换（在右上角）
        rightRow1.add(Box.createHorizontalStrut(12));
        btnDebug = new JToggleButton("调试: OFF");
        btnDebug.setFont(BTN_FONT);
        btnDebug.setForeground(Color.WHITE);
        btnDebug.setBackground(new Color(100, 100, 100));
        btnDebug.setFocusPainted(false);
        btnDebug.setBorderPainted(false);
        btnDebug.setOpaque(true);
        btnDebug.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnDebug.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        btnDebug.addActionListener(e -> {
            debugEnabled = btnDebug.isSelected();
            if (debugEnabled) {
                btnDebug.setText("调试: ON");
                btnDebug.setBackground(new Color(220, 80, 60));
            } else {
                btnDebug.setText("调试: OFF");
                btnDebug.setBackground(new Color(100, 100, 100));
            }
        });
        rightRow1.add(btnDebug);
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
        btnLogLevel  = createButton("备份表管理", new Color(160, 120, 180), e -> runBackupTableList());
        btnShowUsers = createButton("查看用户", new Color(60, 150, 190), e -> runUserList());
        btnValidateCrypto = createButton("验证全库密码", new Color(120, 90, 170), e -> runValidateCryptoKey());

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        row2.setBackground(Color.WHITE);
        row2.add(btnClearSession);
        row2.add(btnSetConfiguring);
        row2.add(btnSetConfirmLabTrans);
        row2.add(btnSetAdminRestart);
        row2.add(Box.createHorizontalStrut(12));
        row2.add(btnQueryVars);
        row2.add(btnLogLevel);
        row2.add(btnShowUsers);
        row2.add(btnValidateCrypto);

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
        Color disabledBg = new Color(0x4A, 0x50, 0x56);
        Color disabledFg = new Color(0xE8, 0x38, 0x38);
        Color disabledBorder = new Color(0xFF, 0x33, 0x33);
        JButton btn = new JButton(text) {
            public void setEnabled(boolean en) {
                super.setEnabled(en);
                if (en) {
                    setBackground(bg);
                    setForeground(Color.WHITE);
                    setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
                    setCursor(new Cursor(Cursor.HAND_CURSOR));
                } else {
                    setBackground(disabledBg);
                    setForeground(disabledFg);
                    setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(disabledBorder, 1),
                        BorderFactory.createEmptyBorder(7, 17, 7, 17)
                    ));
                    setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
                }
            }
        };
        btn.setFont(BTN_FONT);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setOpaque(true);
        btn.setEnabled(enabled);
        btn.addActionListener(listener);
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { if (btn.isEnabled()) btn.setBackground(bg.darker()); }
            public void mouseExited(MouseEvent e) { if (btn.isEnabled()) btn.setBackground(bg); }
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
            String[] baseArgs = {"-l", "-e", "-r"};
            if (!configDBArgs.isEmpty()) {
                java.util.List<String> merged = new java.util.ArrayList<>();
                Collections.addAll(merged, baseArgs);
                merged.addAll(configDBArgs);
                extraArgs = merged.toArray(new String[0]);
            } else {
                extraArgs = baseArgs;
            }
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
        if (debugEnabled) {
            appendLog(">> 调试模式: ON (监听端口 " + debugPort + ", 等待 IDE 连接...)", COLOR_WARN);
        }

        new Thread(() -> {
            try {
                String[] cmd = buildCommand(mainClass, extraArgs);
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(sksRoot);
                pb.environment().putAll(System.getenv());
                for (Map.Entry<String, String> ev : extraEnv.entrySet()) {
                    pb.environment().put(ev.getKey(), ev.getValue());
                }
                pb.redirectErrorStream(false);
                currentProcess = pb.start();

                Thread outT = new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(currentProcess.getInputStream(), "UTF-8"), 8192)) {
                        String l; while ((l = r.readLine()) != null) { final String ll = l; SwingUtilities.invokeLater(() -> appendLogRawToTab(ll, COLOR_STDOUT, 0)); }
                    } catch (IOException ignored) {}
                });
                Thread errT = new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(currentProcess.getErrorStream(), "UTF-8"), 8192)) {
                        String l; while ((l = r.readLine()) != null) { final String ll = l; SwingUtilities.invokeLater(() -> appendLogRawToTab(ll, COLOR_STDERR, 0)); }
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
        args.add(javaCmd);
        args.add("-Dfile.encoding=UTF-8");
        if (debugEnabled) {
            args.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:" + debugPort);
        }
        args.add("-Xmx1024m"); args.add("-cp");
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
                cmdList.add("-Dfile.encoding=UTF-8");
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
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(currentProcess.getInputStream(), "UTF-8"), 8192)) {
                        String l; while ((l = r.readLine()) != null) { final String ll = l; SwingUtilities.invokeLater(() -> appendLog(ll, COLOR_STDOUT, 1)); }
                    } catch (IOException ignored) {}
                });
                Thread errT = new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(currentProcess.getErrorStream(), "UTF-8"), 8192)) {
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
        if (!running) return;
        int opt = JOptionPane.showConfirmDialog(frame,
            "确定要停止当前任务吗？\n子进程将被强制终止。",
            "确认停止", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (opt != JOptionPane.YES_OPTION) return;

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
        appendToDoc(pane, targetDoc, text, color, bold, tabIndex == 0);
    }

    private void appendLogRawToTab(String text, Color color, int tabIndex) {
        JTextPane pane = (tabIndex == 0) ? logPaneCfg : logPaneSql;
        StyledDocument targetDoc = (tabIndex == 0) ? docCfg : docSql;
        try {
            StyleContext sc = StyleContext.getDefaultStyleContext();
            AttributeSet attr = sc.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, color);
            targetDoc.insertString(targetDoc.getLength(), text + "\n", attr);
            if (tabIndex == 0) cfgLineCount++; else sqlLineCount++;
            lineCountLabel.setText((cfgLineCount + sqlLineCount) + " 行");
            pane.setCaretPosition(targetDoc.getLength());
        } catch (BadLocationException ignored) {}
    }

    private void appendToDoc(JTextPane pane, StyledDocument targetDoc, String text, Color color, boolean bold, boolean countLine) {
        try {
            StyleContext sc = StyleContext.getDefaultStyleContext();
            AttributeSet attr = sc.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, color);
            if (bold) attr = sc.addAttribute(attr, StyleConstants.Bold, true);
            String ts = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
            targetDoc.insertString(targetDoc.getLength(), "[" + ts + "] " + text + "\n", attr);
            if (countLine) cfgLineCount++;
            else sqlLineCount++;
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
