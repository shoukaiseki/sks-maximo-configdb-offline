/*
 * ValidateCryptoKeyTool - 全库加密字段(CRYPTO)密钥验证
 *
 * 参照 psdi.tools.ValidateCryptoKey(-l -e -k) 的验证逻辑:
 *   1. 从 maxattribute/maxobject/maxtable 查出所有 CRYPTO / CRYPTOX 字段
 *   2. CRYPTO: 按 maxvars.UPGRADEALGORITHM=0 判断新算法(AES)/旧算法(DESede),
 *      用 mxe.security.crypto.key 构建 MXCipher 同款加解密器;
 *      CRYPTOX: 恒用新算法, 用 mxe.security.cryptox.key 构建 MXCipherX 同款加解密器
 *   3. 逐行 解密(decData) + 回加密(encData) round-trip 验证, 成功/失败分类输出
 *
 * 输出格式:
 *   INFO|message
 *   SQL|type|content
 *   OK|table|column|pk|hex              - 该行验证成功
 *   FAIL|table|column|pk|hex|error      - 该行验证失败
 *   STAT|table|column|okCount|failCount|lastError
 *   DONE|okTotal|failTotal
 *   ERROR|message
 *
 * 使用方式:
 *   --sks-root=<path>                   指定根目录
 *   --pkmap=PERSON:PERSONUID,...        表->主键列映射(缺省使用 表名+ID)
 *
 * 重置密码加密模式(--action=encrypt):
 *   --value=<明文密码>                  待加密的明文密码
 *   --fields=TABLE.COLUMN,...           待加密字段清单, 按 maxtype(CRYPTO/CRYPTOX) 自动选择密钥与算法
 *   输出: ENC|table|column|hex          加密后的完整密文字节码
 *
 * 重置APIKEY模式(--action=apikey):
 *   --pkvals=2,3,5                      APIKEYTOKENID 主键值清单, 每行随机生成 UUID(无-)加密后 base64 输出
 *   输出: INFO|pk=N 明文UUID: xxx       每行的明文 UUID
 *         ENC|table|column|pk|b64       每行加密后的 base64 密文
 */

import java.io.*;
import java.security.KeyFactory;
import java.security.Security;
import javax.crypto.spec.DESedeKeySpec;
import java.sql.*;
import java.util.*;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class ValidateCryptoKeyTool {

    private static final char[] HEX_CHARS = "0123456789ABCDEF".toCharArray();
    private static final int MAX_HEX_DISPLAY = 128; // 字段内容字节码最多显示的 hex 字符数

    private String algorithm;
    private String key;
    private String spec;
    private String mode = "CBC";
    private String padding = "PKCS5Padding";
    private int padLen = 16;
    private Cipher cipherDecrypt;
    private Cipher cipherEncrypt;
    // CRYPTOX (MXCipherX) 用
    private Cipher cipherXDecrypt;
    private Cipher cipherXEncrypt;
    private int cipherXPadLen = 16;

    private static Properties dbProps = new Properties();

    public static void main(String[] args) {
        String sksRootStr = null;
        String pkmap = null;
        String action = "validate";
        String encValue = null;
        String encFields = null;
        String encPkVals = null;
        for (String a : args) {
            if (a.startsWith("--sks-root=")) sksRootStr = a.substring(11);
            else if (a.startsWith("--pkmap=")) pkmap = a.substring(8);
            else if (a.startsWith("--action=")) action = a.substring(9);
            else if (a.startsWith("--value=")) encValue = a.substring(8);
            else if (a.startsWith("--fields=")) encFields = a.substring(9);
            else if (a.startsWith("--pkvals=")) encPkVals = a.substring(9);
        }

        // 确定根目录
        File root;
        if (sksRootStr != null && !sksRootStr.isEmpty()) {
            root = new File(sksRootStr);
        } else {
            root = new File(System.getProperty("user.dir"));
        }

        // 表->主键列映射
        Map<String, String> idMap = new LinkedHashMap<>();
        if (pkmap != null && !pkmap.isEmpty()) {
            for (String pair : pkmap.split(",")) {
                int i = pair.indexOf(':');
                if (i > 0) {
                    idMap.put(pair.substring(0, i).trim().toUpperCase(), pair.substring(i + 1).trim());
                }
            }
        }

        // 连接数据库并执行
        Connection con = null;
        try {
            con = connectDB(root);
            if ("encrypt".equalsIgnoreCase(action)) {
                // 重置密码加密模式
                if (encValue == null || encValue.isEmpty()) {
                    throw new Exception("缺少 --value=<明文密码> 参数");
                }
                if (encFields == null || encFields.isEmpty()) {
                    throw new Exception("缺少 --fields=TABLE.COLUMN,... 参数");
                }
                List<String[]> fl = new ArrayList<>();
                for (String f : encFields.split(",")) {
                    f = f.trim();
                    int i = f.indexOf('.');
                    if (i > 0) {
                        fl.add(new String[]{ f.substring(0, i).trim().toUpperCase(), f.substring(i + 1).trim().toUpperCase() });
                    }
                }
                if (fl.isEmpty()) {
                    throw new Exception("--fields 参数格式错误, 应为 TABLE.COLUMN,TABLE2.COLUMN2");
                }
                new ValidateCryptoKeyTool().runEncrypt(con, encValue, fl);
            } else if ("apikey".equalsIgnoreCase(action)) {
                // 重置APIKEY模式: 每行随机 UUID 加密
                if (encPkVals == null || encPkVals.isEmpty()) {
                    throw new Exception("缺少 --pkvals=<APIKEYTOKENID,...> 参数");
                }
                List<String> pkVals = new ArrayList<>();
                for (String v : encPkVals.split(",")) {
                    if (!v.trim().isEmpty()) pkVals.add(v.trim());
                }
                if (pkVals.isEmpty()) {
                    throw new Exception("--pkvals 参数格式错误, 应为 APIKEYTOKENID 主键值逗号分隔");
                }
                new ValidateCryptoKeyTool().runEncryptApikey(con, pkVals);
            } else {
                new ValidateCryptoKeyTool().run(con, idMap);
            }
        } catch (Exception e) {
            System.out.println("ERROR|" + oneLine(e.getMessage()));
        } finally {
            if (con != null) try { con.close(); } catch (Exception ignored) {}
        }
    }

    private static Connection connectDB(File root) throws Exception {
        File propFile = new File(root, "maximo/properties/maximo.properties");
        if (!propFile.exists()) {
            throw new Exception("maximo.properties not found at " + propFile.getAbsolutePath());
        }
        try (FileInputStream fis = new FileInputStream(propFile)) {
            dbProps.load(fis);
        }
        String dbUrl = dbProps.getProperty("mxe.db.url");
        String dbUser = dbProps.getProperty("mxe.db.user");
        String dbPass = dbProps.getProperty("mxe.db.password");
        String schemaOwner = dbProps.getProperty("mxe.db.schemaowner");
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

    private void run(Connection con, Map<String, String> idMap) throws Exception {
        // 加密参数（参照 ValidateCryptoKey.process/instanciateCiphers）
        String keyTest = dbProps.getProperty("mxe.security.crypto.key");
        String algTest = dbProps.getProperty("mxe.security.crypto.algorithm");
        String modeTest = dbProps.getProperty("mxe.security.crypto.mode");
        String paddingTest = dbProps.getProperty("mxe.security.crypto.padding");
        String specTest = dbProps.getProperty("mxe.security.crypto.spec");
        String providerTest = dbProps.getProperty("mxe.security.provider");

        // CRYPTOX 参数（MXCipherX 用）
        String keyTestX = dbProps.getProperty("mxe.security.cryptox.key");
        String algTestX = dbProps.getProperty("mxe.security.cryptox.algorithm");
        String modeTestX = dbProps.getProperty("mxe.security.cryptox.mode");
        String paddingTestX = dbProps.getProperty("mxe.security.cryptox.padding");
        String specTestX = dbProps.getProperty("mxe.security.cryptox.spec");

        // UPGRADEALGORITHM=0 -> 新算法(AES), 否则旧算法(DESede)
        boolean newEncryption = rowFound(con,
            "select 1 from maxvars where varname = 'UPGRADEALGORITHM' and varvalue = '0'");

        if (keyTest == null || keyTest.isEmpty()) {
            System.out.println("INFO|未配置 mxe.security.crypto.key, 使用 MXCipher 默认密钥");
        } else {
            System.out.println("INFO|密钥: " + keyTest.substring(0, 2) + "..."
                + keyTest.substring(keyTest.length() - 2));
        }
        if (keyTestX == null || keyTestX.isEmpty()) {
            System.out.println("INFO|未配置 mxe.security.cryptox.key, 使用 MXCipherX 默认密钥");
        } else {
            System.out.println("INFO|CRYPTOX 密钥: " + keyTestX.substring(0, 2) + "..."
                + keyTestX.substring(keyTestX.length() - 2));
        }
        System.out.println("INFO|算法: " + (newEncryption ? "新算法(AES)" : "旧算法(DESede)"));

        initCiphers(keyTest, algTest, modeTest, paddingTest, specTest, providerTest, newEncryption);
        initCipherX(keyTestX, algTestX, modeTestX, paddingTestX, specTestX);

        // 查出所有 CRYPTO 字段（参照 ValidateCryptoKey.validateKey 的 SQL）
        String sql = "select distinct entityname, columnname from maxattribute a, maxtable t "
            + "where a.persistent = 1 and a.maxtype = 'CRYPTO' "
            + "and a.entityname in (select entityname from maxobject where isview = 0 and persistent = 1) "
            + "and a.entityname = t.tablename and t.isaudittable = 0 "
            + "and a.objectname != 'DUMMY_TABLE' "
            + "order by entityname, columnname";
        System.out.println("SQL|CRYPTO|" + sql + ";");
        List<String[]> fields = new ArrayList<>();
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                fields.add(new String[]{ rs.getString(1).trim(), rs.getString(2).trim() });
            }
        }
        System.out.println("INFO|共 " + fields.size() + " 个 CRYPTO 加密字段");

        // 查出所有 CRYPTOX 字段（同条件, MAXUSER.PASSWORD 即为 CRYPTOX）
        String sqlX = "select distinct entityname, columnname from maxattribute a, maxtable t "
            + "where a.persistent = 1 and a.maxtype = 'CRYPTOX' "
            + "and a.entityname in (select entityname from maxobject where isview = 0 and persistent = 1) "
            + "and a.entityname = t.tablename and t.isaudittable = 0 "
            + "and a.objectname != 'DUMMY_TABLE' "
            + "order by entityname, columnname";
        System.out.println("SQL|CRYPTOX|" + sqlX + ";");
        List<String[]> fieldsX = new ArrayList<>();
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sqlX)) {
            while (rs.next()) {
                fieldsX.add(new String[]{ rs.getString(1).trim(), rs.getString(2).trim() });
            }
        }
        System.out.println("INFO|共 " + fieldsX.size() + " 个 CRYPTOX 加密字段");

        int okTotal = 0, failTotal = 0;
        for (String[] f : fields) {
            int[] r = validateField(con, idMap, f[0], f[1], false, false);
            okTotal += r[0];
            failTotal += r[1];
        }
        for (String[] f : fieldsX) {
            int[] r = validateField(con, idMap, f[0], f[1], false, true);
            okTotal += r[0];
            failTotal += r[1];
        }

        // APIKEYTOKEN.APIKEY（base64 文本, maxtype=ALN; 参照原版 validateKey 的 secureapikey 分支, 此处恒验证）
        int[] rApi = validateField(con, idMap, "APIKEYTOKEN", "APIKEY", true, false);
        okTotal += rApi[0];
        failTotal += rApi[1];

        System.out.println("DONE|" + okTotal + "|" + failTotal);
    }

    // 重置密码加密模式: 按字段 maxtype(CRYPTO/CRYPTOX) 自动选择密钥与算法加密明文
    // 输出: ENC|table|column|hex  (加密后完整密文字节码, 带回解自检)
    private void runEncrypt(Connection con, String value, List<String[]> fields) throws Exception {
        String keyTest = dbProps.getProperty("mxe.security.crypto.key");
        String algTest = dbProps.getProperty("mxe.security.crypto.algorithm");
        String modeTest = dbProps.getProperty("mxe.security.crypto.mode");
        String paddingTest = dbProps.getProperty("mxe.security.crypto.padding");
        String specTest = dbProps.getProperty("mxe.security.crypto.spec");
        String providerTest = dbProps.getProperty("mxe.security.provider");
        String keyTestX = dbProps.getProperty("mxe.security.cryptox.key");
        String algTestX = dbProps.getProperty("mxe.security.cryptox.algorithm");
        String modeTestX = dbProps.getProperty("mxe.security.cryptox.mode");
        String paddingTestX = dbProps.getProperty("mxe.security.cryptox.padding");
        String specTestX = dbProps.getProperty("mxe.security.cryptox.spec");

        // UPGRADEALGORITHM=0 -> 新算法(AES), 否则旧算法(DESede)
        boolean newEncryption = rowFound(con,
            "select 1 from maxvars where varname = 'UPGRADEALGORITHM' and varvalue = '0'");
        System.out.println("INFO|加密模式: " + (newEncryption ? "新算法(AES)" : "旧算法(DESede)"));

        boolean cryptoInit = false, cryptoXInit = false;
        int done = 0;
        byte[] plain = value.getBytes();
        for (String[] f : fields) {
            String entity = f[0], column = f[1];
            // 查字段类型
            String maxtype = null;
            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("select maxtype from maxattribute "
                     + "where entityname = '" + entity + "' and columnname = '" + column + "' "
                     + "fetch first 1 rows only")) {
                if (rs.next()) maxtype = rs.getString(1).trim();
            }
            try {
                byte[] enc;
                if ("CRYPTOX".equalsIgnoreCase(maxtype)) {
                    if (!cryptoXInit) {
                        initCipherX(keyTestX, algTestX, modeTestX, paddingTestX, specTestX);
                        cryptoXInit = true;
                    }
                    enc = encryptBytes(plain, true);
                } else if ("CRYPTO".equalsIgnoreCase(maxtype)) {
                    if (!cryptoInit) {
                        initCiphers(keyTest, algTest, modeTest, paddingTest, specTest, providerTest, newEncryption);
                        cryptoInit = true;
                    }
                    enc = encryptBytes(plain, false);
                } else {
                    System.out.println("ERROR|" + entity + "." + column + " maxtype="
                        + (maxtype == null ? "未找到" : maxtype) + ", 仅支持 CRYPTO/CRYPTOX 字段加密重置");
                    continue;
                }
                // 回解自检: 确保密文可被正确解密
                String rt = decData(enc, "CRYPTOX".equalsIgnoreCase(maxtype));
                if (!rt.equals(value)) {
                    throw new Exception("加密自检失败(回解不一致)");
                }
                System.out.println("ENC|" + entity + "|" + column + "|" + bytesToHex(enc));
                done++;
            } catch (Exception e) {
                System.out.println("ERROR|" + entity + "." + column + " 加密失败: " + oneLine(e.getMessage()));
            }
        }
        System.out.println("DONE|" + done);
    }

    // 重置APIKEY模式: APIKEYTOKEN 每行随机生成 UUID(无-), 用 MXCipher(crypto.key)加密后 base64 输出
    // 参照原版: APIKEY 为 base64(密文) 文本存储, 读取方 DatatypeConverter.parseBase64Binary 解码后解密
    private void runEncryptApikey(Connection con, List<String> pkVals) throws Exception {
        String keyTest = dbProps.getProperty("mxe.security.crypto.key");
        String algTest = dbProps.getProperty("mxe.security.crypto.algorithm");
        String modeTest = dbProps.getProperty("mxe.security.crypto.mode");
        String paddingTest = dbProps.getProperty("mxe.security.crypto.padding");
        String specTest = dbProps.getProperty("mxe.security.crypto.spec");
        String providerTest = dbProps.getProperty("mxe.security.provider");

        boolean newEncryption = rowFound(con,
            "select 1 from maxvars where varname = 'UPGRADEALGORITHM' and varvalue = '0'");
        System.out.println("INFO|加密模式: " + (newEncryption ? "新算法(AES)" : "旧算法(DESede)"));
        initCiphers(keyTest, algTest, modeTest, paddingTest, specTest, providerTest, newEncryption);

        int done = 0;
        for (String pk : pkVals) {
            try {
                String uuid = UUID.randomUUID().toString().replace("-", "");
                byte[] enc = encryptBytes(uuid.getBytes(), false);
                String rt = decData(enc, false);
                if (!rt.equals(uuid)) {
                    throw new Exception("加密自检失败(回解不一致)");
                }
                String b64 = Base64.getEncoder().encodeToString(enc);
                System.out.println("INFO|pk=" + pk + " 明文UUID: " + uuid);
                System.out.println("ENC|APIKEYTOKEN|APIKEY|" + pk + "|" + b64);
                done++;
            } catch (Exception e) {
                System.out.println("ERROR|APIKEYTOKEN.APIKEY pk=" + pk + " 加密失败: " + oneLine(e.getMessage()));
            }
        }
        System.out.println("DONE|" + done);
    }

    // 验证单个加密字段，返回 [成功数, 失败数]; useX=true 表示 CRYPTOX(MXCipherX)
    private int[] validateField(Connection con, Map<String, String> idMap,
                                String entity, String column, boolean asString, boolean useX) {
        // 主键列: 映射优先, 缺省使用 表名+ID
        String pk = idMap.get(entity.toUpperCase());
        if (pk == null || pk.isEmpty()) pk = entity.toUpperCase() + "ID";
        String sql = "select " + pk + ", " + column + " from " + entity
            + " where " + column + " is not null";
        System.out.println("SQL|CHECK|" + sql + ";");

        int ok = 0, fail = 0;
        String lastErr = "-";
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String pkVal = rs.getString(1);
                byte[] val;
                try {
                    if (asString) {
                        // APIKEYTOKEN.APIKEY 为 base64 文本（参照原版 DatatypeConverter.parseBase64Binary）
                        String s = rs.getString(2);
                        val = (s == null) ? null : Base64.getMimeDecoder().decode(s.trim());
                    } else {
                        val = rs.getBytes(2);
                    }
                } catch (SQLException se) {
                    // 列非二进制类型时回退为文本读取(VARGRAPHIC 每字符即一字节)
                    String s = rs.getString(2);
                    if (s == null) continue;
                    val = latin1Bytes(s);
                }
                String hex = (val == null) ? "" : bytesToHex(val);
                try {
                    // 参照原版: decData + encData round-trip
                    String decValue = decData(val, useX);
                    encData(decValue.getBytes(), useX);
                    ok++;
                    System.out.println("OK|" + entity + "|" + column + "|" + pkVal + "|" + truncate(hex));
                } catch (Exception e) {
                    fail++;
                    lastErr = oneLine(e.getMessage());
                    if (lastErr.isEmpty()) lastErr = e.getClass().getSimpleName();
                    System.out.println("FAIL|" + entity + "|" + column + "|" + pkVal + "|" + truncate(hex) + "|" + lastErr);
                }
            }
        } catch (Exception e) {
            lastErr = oneLine(e.getMessage());
            System.out.println("ERROR|" + entity + "." + column + " 查询失败: " + lastErr);
        }
        System.out.println("STAT|" + entity + "|" + column + "|" + ok + "|" + fail + "|" + lastErr);
        return new int[]{ ok, fail };
    }

    // ---- 加解密(参照 MXCipher / MXCipherX) ----
    // CRYPTO 字段用 MXCipher；CRYPTOX 字段用 MXCipherX
    private void initCiphers(String keyTest, String algTest, String modeTest,
                             String paddingTest, String specTest, String providerTest,
                             boolean useNewAlgorithm) throws Exception {
        if (providerTest != null && !providerTest.isEmpty()) {
            Class<?> c = Class.forName(providerTest);
            Security.addProvider((java.security.Provider) c.getDeclaredConstructor().newInstance());
        }

        // MXCipher 默认密钥/spec（keyTest/specTest 为空时使用）
        algorithm = useNewAlgorithm ? "AES" : "DESede";
        key = useNewAlgorithm ? "ApBR_ZF3PYJsGbpRbVmyDQ" : "j3*9vk0e8rjvc9fj(*KFikd#";
        spec = "kE*(RKc%";
        if (algTest != null && !algTest.isEmpty()) algorithm = algTest;
        if (modeTest != null && !modeTest.isEmpty()) mode = modeTest;
        if (paddingTest != null && !paddingTest.isEmpty()) padding = paddingTest;
        if (keyTest != null && !keyTest.isEmpty()) key = keyTest;
        if (specTest != null && !specTest.isEmpty()) spec = specTest;

        padLen = calcPadLen(algorithm);
        Cipher[] pair = buildCipherPair(algorithm, key, spec, mode, padding);
        cipherEncrypt = pair[0];
        cipherDecrypt = pair[1];
    }

    // CRYPTOX 字段: MXCipherX（恒用新算法, 默认 key/spec 见 MXCipherX 类字段）
    private void initCipherX(String keyTest, String algTest, String modeTest,
                             String paddingTest, String specTest) throws Exception {
        String xAlgorithm = "AES";
        String xKey = "Sa#qk5usfmMI-@2dbZP9`jL3";
        String xSpec = "beLd7$lB";
        String xMode = "CBC";
        String xPadding = "PKCS5Padding";
        if (algTest != null && !algTest.isEmpty()) xAlgorithm = algTest;
        if (modeTest != null && !modeTest.isEmpty()) xMode = modeTest;
        if (paddingTest != null && !paddingTest.isEmpty()) xPadding = paddingTest;
        if (keyTest != null && !keyTest.isEmpty()) xKey = keyTest;
        if (specTest != null && !specTest.isEmpty()) xSpec = specTest;

        cipherXPadLen = calcPadLen(xAlgorithm);
        Cipher[] pair = buildCipherPair(xAlgorithm, xKey, xSpec, xMode, xPadding);
        cipherXEncrypt = pair[0];
        cipherXDecrypt = pair[1];
    }

    private static int calcPadLen(String algorithm) {
        // 参照 MXCipherX.validateParams
        if (algorithm.equals("RSA")) return 0;
        if (algorithm.equals("AES") || algorithm.equals("Serpent") || algorithm.equals("MARS")
                || algorithm.equals("RC6") || algorithm.equals("Rijndael")
                || algorithm.equals("Square") || algorithm.equals("Twofish")) return 16;
        return 8;
    }

    private Cipher[] buildCipherPair(String algorithm, String key, String spec,
                                     String mode, String padding) throws Exception {
        String transformation = algorithm;
        if (mode != null && !mode.isEmpty() && padding != null && !padding.isEmpty()) {
            transformation = algorithm + "/" + mode + "/" + padding;
        }

        Cipher encrypt, decrypt;
        if (algorithm.equals("AES")) {
            // 参照 MXCipherX.buildCipher AES 分支: key/spec 零填充到 16 的倍数
            SecretKeySpec ks = new SecretKeySpec(pad(key.getBytes(), 16), "AES");
            IvParameterSpec iv = new IvParameterSpec(pad(spec.getBytes(), 16));
            encrypt = Cipher.getInstance(transformation);
            decrypt = Cipher.getInstance(transformation);
            if (transformation.indexOf("ECB") < 0) {
                encrypt.init(Cipher.ENCRYPT_MODE, ks, iv);
                decrypt.init(Cipher.DECRYPT_MODE, ks, iv);
            } else {
                encrypt.init(Cipher.ENCRYPT_MODE, ks);
                decrypt.init(Cipher.DECRYPT_MODE, ks);
            }
        } else if (algorithm.equals("DESede") || algorithm.equals("TripleDES")) {
            DESedeKeySpec keySpec = new DESedeKeySpec(key.getBytes());
            SecretKey sk = SecretKeyFactory.getInstance("DESede").generateSecret(keySpec);
            IvParameterSpec iv = new IvParameterSpec(spec.getBytes());
            encrypt = Cipher.getInstance(transformation);
            decrypt = Cipher.getInstance(transformation);
            if (transformation.indexOf("ECB") < 0) {
                encrypt.init(Cipher.ENCRYPT_MODE, sk, iv);
                decrypt.init(Cipher.DECRYPT_MODE, sk, iv);
            } else {
                encrypt.init(Cipher.ENCRYPT_MODE, sk);
                decrypt.init(Cipher.DECRYPT_MODE, sk);
            }
        } else {
            throw new Exception("不支持的加密算法: " + algorithm);
        }
        return new Cipher[]{ encrypt, decrypt };
    }

    // 参照 MXCipher.decData / MXCipherX.decData
    private String decData(byte[] in, boolean useX) throws Exception {
        if (in == null) return null;
        byte[] dec = (useX ? cipherXDecrypt : cipherDecrypt).doFinal(pad(in, useX ? cipherXPadLen : padLen));
        return new String(dec).trim();
    }

    // 参照 MXCipher.encData（round-trip 验证用）
    private void encData(byte[] in, boolean useX) throws Exception {
        (useX ? cipherXEncrypt : cipherEncrypt).doFinal(pad(in, useX ? cipherXPadLen : padLen));
    }

    // 加密并返回完整密文字节（重置密码用）
    private byte[] encryptBytes(byte[] in, boolean useX) throws Exception {
        return (useX ? cipherXEncrypt : cipherEncrypt).doFinal(pad(in, useX ? cipherXPadLen : padLen));
    }

    private byte[] pad(byte[] in, int len) {
        if (len == 0) return in;
        int rem = in.length % len;
        if (rem == 0) return in;
        byte[] out = new byte[in.length + len - rem];
        System.arraycopy(in, 0, out, 0, in.length);
        return out;
    }

    // ---- helpers ----
    private static boolean rowFound(Connection con, String sql) throws Exception {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next();
        }
    }

    private static byte[] latin1Bytes(String s) {
        char[] c = s.toCharArray();
        byte[] b = new byte[c.length];
        for (int i = 0; i < c.length; i++) b[i] = (byte) (c[i] & 0xFF);
        return b;
    }

    private static String bytesToHex(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            out[i * 2] = HEX_CHARS[v >>> 4];
            out[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(out);
    }

    private static String truncate(String hex) {
        if (hex.length() <= MAX_HEX_DISPLAY) return hex;
        return hex.substring(0, MAX_HEX_DISPLAY) + "...(" + (hex.length() / 2) + "B)";
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\r\\n]+", " ").trim();
    }
}
