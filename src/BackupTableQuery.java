/*
 * BackupTableQuery - 查询和管理备份表 (XX前缀)
 *
 * 输出格式:
 *   TABLE|orig_name|backup_name              - 备份表列表
 *   COUNT|orig_name|backup_name|orig_count|backup_count  - 数据量
 *   SQL|type|content                         - 生成的 SQL
 *   ERROR|message                            - 错误信息
 *   INFO|message                             - 提示信息
 *
 * 使用方式:
 *   --action=list                        列出所有备份表
 *   --action=count --tables="T1,T2,..."  查询指定表的数据量
 *   --action=sql-insert table=T           生成 INSERT INTO 原表 SELECT * FROM 备份表
 *   --action=sql-drop table=T            生成 DROP TABLE 备份表
 *   --action=drop table=T                执行 DROP TABLE 备份表
 *   --sks-root=<path>                    指定根目录
 */

import java.io.*;
import java.sql.*;
import java.util.*;

public class BackupTableQuery {

    public static void main(String[] args) {
        String action = "list";
        String tables = null;
        String sksRootStr = null;
        String singleTable = null;

        for (String a : args) {
            if (a.startsWith("--action=")) action = a.substring(9);
            else if (a.startsWith("--tables=")) tables = a.substring(9);
            else if (a.startsWith("--sks-root=")) sksRootStr = a.substring(11);
            else if (a.startsWith("--table=")) singleTable = a.substring(8);
        }

        // 确定根目录
        File root;
        if (sksRootStr != null && !sksRootStr.isEmpty()) {
            root = new File(sksRootStr);
        } else {
            root = new File(System.getProperty("user.dir"));
        }

        // 连接数据库
        Connection con = null;
        try {
            con = connectDB(root);
            if (action.equals("list")) {
                actionList(con, root);
            } else if (action.equals("count")) {
                actionCount(con, tables);
            } else if (action.equals("sql-insert")) {
                actionGenInsert(con, singleTable);
            } else if (action.equals("sql-drop")) {
                actionGenDrop(con, singleTable);
            } else if (action.equals("drop")) {
                actionDrop(con, singleTable);
            }
        } catch (Exception e) {
            System.out.println("ERROR|" + e.getMessage());
            e.printStackTrace();
        } finally {
            if (con != null) try { con.close(); } catch (Exception ignored) {}
        }
    }

    private static Connection connectDB(File root) throws Exception {
        File propFile = new File(root, "maximo/properties/maximo.properties");
        if (!propFile.exists()) {
            throw new Exception("maximo.properties not found at " + propFile.getAbsolutePath());
        }
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(propFile)) {
            props.load(fis);
        }
        String dbUrl = props.getProperty("mxe.db.url");
        String dbUser = props.getProperty("mxe.db.user");
        String dbPass = props.getProperty("mxe.db.password");
        String schemaOwner = props.getProperty("mxe.db.schemaowner");
        if (dbUrl == null || dbUser == null || dbPass == null) {
            throw new Exception("Missing DB connection info in maximo.properties");
        }
        Class.forName("com.ibm.db2.jcc.DB2Driver");
        Connection con = DriverManager.getConnection(dbUrl, dbUser, dbPass);
        if (schemaOwner != null && !schemaOwner.trim().isEmpty()) {
            try (Statement st = con.createStatement()) {
                st.execute("SET SCHEMA " + schemaOwner.trim());
            }
        }
        return con;
    }

    // ---- Helpers: 表名长度 & 备份表名 ----
    private static int getMaxNameLength(Connection con) throws Exception {
        String sql = "Select length From maxattribute Where objectname = 'MAXTABLECFG' And attributename = 'TABLENAME'";
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) return rs.getInt(1);
        }
        return 30; // 默认值
    }

    private static String getBackupTableName(String orig, int maxLen) {
        String backup = "XX" + orig.toUpperCase();
        if (backup.length() > maxLen) backup = backup.substring(0, maxLen);
        return backup;
    }

    // ---- Action: List 备份表 ----
    // 参照 Util.getOldBackupTables(null) + ConfigureService.getOldBackupTables() 两步流程：
    //   1. 先从 syscat.tables 查出所有存在的备份表名（XX前缀）
    //   2. 去掉 XX 前缀得到原表名
    private static void actionList(Connection con, File root) throws Exception {
        int maxLen = getMaxNameLength(con);
        int num1 = maxLen - 1;
        int num2 = maxLen - 2;

        // 第一步：按原版逻辑构建 SQL，查出所有存在的备份表名
        // short names: 'XX' || tablename where length(tablename) < num1
        // long names:  'XX' || substr(tablename,1,num2) where length(tablename) >= num1
        String sql = "select tabname from syscat.tables where tabschema = current schema "
            + "and (tabname in (Select 'XX' || tablename From maxtablecfg "
            + "where length(tablename) < " + num1 + " and restoredata != 'Y') "
            + "or tabname in (Select 'XX' || substr(tablename,1," + num2 + ") From maxtablecfg "
            + "where length(tablename) >= " + num1 + " and restoredata != 'Y'))";

        System.out.println("SQL|QUERY|" + sql + ";");

        List<String> backupNames = new ArrayList<>();
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                backupNames.add(rs.getString(1).trim().toUpperCase());
            }
        }

        // 第二步：去掉 XX 前缀得到原表名（参照 ConfigureService.getOldBackupTables 的 substring(2)）
        int count = 0;
        for (String backup : backupNames) {
            String orig = backup.substring(2); // 去掉 "XX" 前缀
            System.out.println("TABLE|" + orig + "|" + backup);
            count++;
        }
        System.out.println("INFO|共 " + count + " 个备份表");
    }

    // ---- Action: Count 数据量 ----
    private static void actionCount(Connection con, String tablesStr) throws Exception {
        if (tablesStr == null || tablesStr.isEmpty()) {
            System.out.println("ERROR|--tables 参数不能为空");
            return;
        }
        String[] tables = tablesStr.split(",");
        for (String pair : tables) {
            String[] parts = pair.split("\\|", 2);
            if (parts.length != 2) continue;
            String orig = parts[0].trim();
            String backup = parts[1].trim();
            long origCount = 0, backupCount = 0;
            try (Statement st = con.createStatement()) {
                String sql1 = "select count(1) from " + orig;
                System.out.println("SQL|COUNT|" + sql1 + ";");
                try (ResultSet rs = st.executeQuery(sql1)) {
                    if (rs.next()) origCount = rs.getLong(1);
                }
                String sql2 = "select count(1) from " + backup;
                System.out.println("SQL|COUNT|" + sql2 + ";");
                try (ResultSet rs = st.executeQuery(sql2)) {
                    if (rs.next()) backupCount = rs.getLong(1);
                }
            }
            System.out.println("COUNT|" + orig + "|" + backup + "|" + origCount + "|" + backupCount);
        }
    }

    // ---- Action: 生成 INSERT SQL ----
    private static void actionGenInsert(Connection con, String table) throws Exception {
        if (table == null || table.isEmpty()) {
            System.out.println("ERROR|--table 参数不能为空");
            return;
        }
        int maxLen = getMaxNameLength(con);
        String backup = getBackupTableName(table, maxLen);
        String sql = "INSERT INTO " + table + " SELECT * FROM " + backup;
        System.out.println("SQL|INSERT|" + sql + ";");
        System.out.println("SQL|INSERT|commit;");
    }

    // ---- Action: 生成 DROP SQL ----
    private static void actionGenDrop(Connection con, String table) throws Exception {
        if (table == null || table.isEmpty()) {
            System.out.println("ERROR|--table 参数不能为空");
            return;
        }
        int maxLen = getMaxNameLength(con);
        String backup = getBackupTableName(table, maxLen);
        String sql = "DROP TABLE " + backup;
        System.out.println("SQL|DROP|" + sql + ";");
        System.out.println("SQL|DROP|commit;");
    }

    // ---- Action: 执行 DROP ----
    private static void actionDrop(Connection con, String table) throws Exception {
        if (table == null || table.isEmpty()) {
            System.out.println("ERROR|--table 参数不能为空");
            return;
        }
        int maxLen = getMaxNameLength(con);
        String backup = getBackupTableName(table, maxLen);
        con.setAutoCommit(false);
        try (Statement st = con.createStatement()) {
            String dropSql = "DROP TABLE " + backup;
            System.out.println("SQL|DROP|" + dropSql + ";");
            st.execute(dropSql);
            System.out.println("SQL|DROP|commit;");
            st.execute("commit");
            System.out.println("INFO|备份表 " + backup + " 已删除");
            // 更新 restoredata = 'Y'
            String updateSql = "update maxtablecfg set restoredata = 'Y' where tablename = '" + table + "'";
            System.out.println("SQL|UPDATE|" + updateSql + ";");
            st.execute(updateSql);
            System.out.println("SQL|UPDATE|commit;");
            st.execute("commit");
            System.out.println("INFO|maxtablecfg.restoredata 已更新为 Y");
        } catch (Exception e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(true);
        }
    }
}
