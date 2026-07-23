/*
 * MXLoggerQuery - 查询日志级别配置
 *
 * 使用方式：
 *   1. 读取 logging.properties 中的 log4j.logger.* 配置（安全，不触发 MXServer）
 *   2. 通过反射访问 MXLoggerFactory.loggers（安全，不触发 MXServer）
 *
 * 输出格式：
 *   LOGGER|name|level         - 日志记录器及其级别
 *   CONFIG|key=value          - logging.properties 的非 logger 配置
 *   ERROR|message             - 错误信息
 */

import java.io.*;
import java.util.*;

public class MXLoggerQuery {

    private static final String PROPS_PATH = "maximo/properties/logging.properties";

    public static void main(String[] args) {
        // ================================================================
        // 1. 读取 logging.properties 中的 log4j.logger.* 配置
        // ================================================================
        Properties props = new Properties();
        File f = new File(PROPS_PATH);
        if (f.exists()) {
            try (InputStream is = new FileInputStream(f)) {
                props.load(is);
            } catch (IOException e) {
                System.out.println("ERROR|Failed to load " + PROPS_PATH + ": " + e.getMessage());
            }
        } else {
            System.out.println("WARN|" + PROPS_PATH + " not found");
        }

        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("log4j.logger.")) {
                String loggerName = key.substring("log4j.logger.".length());
                String level = props.getProperty(key, "").trim();
                System.out.println("LOGGER|" + loggerName + "|" + level + " (from properties)");
            } else {
                System.out.println("CONFIG|" + key + "=" + props.getProperty(key));
            }
        }

        // ================================================================
        // 2. 尝试通过反射访问 MXLoggerFactory.loggers（不触发 MXServer）
        // ================================================================
        try {
            Class<?> factoryClass = Class.forName("psdi.util.logging.MXLoggerFactory");
            java.lang.reflect.Field loggersField = factoryClass.getDeclaredField("loggers");
            // loggers 是 public static 字段
            Object loggersObj = loggersField.get(null);

            if (loggersObj instanceof Map) {
                Map<?, ?> loggers = (Map<?, ?>) loggersObj;
                if (!loggers.isEmpty()) {
                    System.out.println("CONFIG|runtime_logger_count=" + loggers.size());
                    for (Map.Entry<?, ?> entry : loggers.entrySet()) {
                        String name = String.valueOf(entry.getKey());
                        Object logger = entry.getValue();
                        String level = "UNKNOWN";
                        try {
                            // 调用 getLevel() 方法
                            java.lang.reflect.Method getLevel = logger.getClass().getMethod("getLevel");
                            Object l = getLevel.invoke(logger);
                            if (l != null) {
                                level = l.toString();
                            } else {
                                level = "INHERITED";
                            }
                        } catch (Exception e) {
                            level = "ERROR(" + e.getMessage() + ")";
                        }
                        System.out.println("LOGGER|" + name + "|" + level + " (runtime)");
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            System.out.println("INFO|MXLoggerFactory not found on classpath");
        } catch (NoSuchFieldException e) {
            System.out.println("INFO|MXLoggerFactory.loggers field not accessible");
        } catch (Exception e) {
            System.out.println("INFO|Cannot access MXLoggerFactory: " + e.getMessage());
        }
    }
}
