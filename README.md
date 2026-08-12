# paper-komari

基于 Paper 重构的 Java 代理部署项目，集成 sing-box (Hysteria2 + VLESS Reality) + komari-agent 监控 + Argo 隧道 + Telegram 推送。

## 分支说明

| 分支 | JAR 文件 | 部署方式 | 进程隐身 |
|------|---------|---------|---------|
| `main` | `server.jar` | 独立运行 `java -jar server.jar` | 子进程 + 乱码名 |
| `plugin` | `bettermix.jar` | Bukkit 插件 `plugins/` | **JNA 内存加载，无子进程** |
| `paper-pro` | `server.jar` | 独立运行 `java -jar server.jar` | **JNA 内存加载，无子进程** |

## 快速使用

### 1. 下载 JAR
从 [Release](https://github.com/jynn77/paper-komari/releases) 下载对应分支的 JAR 文件。

### 2. 编辑 config.yml
首次启动前编辑 `config.yml`：

**独立版（main / paper-pro 分支）：**
```yaml
port: "25983"           # hy2 + reality 共用端口
sni: "www.bing.com"     # TLS SNI / Reality 握手域名
```

**插件版（plugin 分支）：**
```yaml
reality_port: "443"     # VLESS+Reality 端口
hy2_port: "8443"        # Hysteria2 端口
# 其他协议端口留空则不启用
```

### 3. 启动

**独立版：**
```bash
java -jar server.jar
```

**插件版：**
将 `bettermix.jar` 放入 Minecraft 服务器的 `plugins/` 目录，重启服务器。

### 4. 首次启动流程
1. 自动生成 UUID 并持久化
2. 生成密钥对（纯 Java X25519，不依赖二进制）
3. 生成自签证书（EC prime256v1, CN=bing.com）
4. 下载 sbx.so / bot.so（JNA 内存加载，无子进程）
5. 启动代理服务
6. 输出节点链接（控制台 + Telegram 推送，1分钟后自动清屏）

## 配置项说明

### 独立版（main / paper-pro）
| 配置项 | 默认值 | 说明 |
|-------|--------|------|
| `port` | `""` | hy2 + reality 共用端口（必填） |
| `sni` | `www.bing.com` | TLS SNI / Reality 握手域名 |
| `sb_log_enabled` | `false` | 日志开关 |
| `print_info` | `false` | 控制台输出信息，1分钟后清屏 |
| `node_name` | `""` | 节点名称前缀（留空自动检测 ISP） |
| `argo_enabled` | `false` | 是否启用 Argo 隧道 |
| `argo_token` | `""` | Cloudflare Tunnel Token |
| `argo_domain` | `""` | 固定隧道域名 |
| `argo_port` | `8001` | 临时隧道本地端口 |
| `argo_cfip` | `saas.sin.fan` | Cloudflare 优选 IP |
| `komari_agent_enabled` | `false` | 是否启用监控 |
| `komari_agent_name` | `bettermix` | 伪装文件名 |
| `komari_agent_endpoint` | `""` | 服务器地址 |
| `komari_agent_key` | `""` | 自动发现密钥 |
| `tg_bot_token` | `""` | Telegram Bot Token |
| `tg_chat_id` | `""` | 聊天/频道 ID |

### 插件版（plugin）
| 配置项 | 默认值 | 说明 |
|-------|--------|------|
| `reality_port` | `""` | VLESS+Reality 端口 |
| `hy2_port` | `""` | Hysteria2 端口 |
| `vmess_ws_port` | `""` | VMess+WebSocket+TLS 端口 |
| `vless_ws_port` | `""` | VLESS+WebSocket+TLS 端口 |
| `naive_port` | `""` | NaiveProxy 端口 |
| `anytls_port` | `""` | AnyTLS 端口 |
| `tuic_port` | `""` | TUIC 端口 |
| `sni` | `www.iij.ad.jp` | TLS SNI |
| `sb_log_enabled` | `false` | 日志开关 |
| `print_info` | `false` | 控制台输出信息，1分钟后清屏 |
| `argo_*` | — | 同独立版 Argo 配置 |
| `komari_agent_*` | — | 同独立版监控配置 |
| `tg_bot_token` | `""` | Telegram Bot Token |
| `tg_chat_id` | `""` | 聊天/频道 ID |

## 反检测特性

| 特性 | main | plugin | paper-pro |
|------|:----:|:------:|:---------:|
| 子进程名乱码 | ✅ | — | — |
| JNA 内存加载（无子进程） | — | ✅ | ✅ |
| 二进制启动即删 | ✅ | ✅ | ✅ |
| 配置/证书 3s 即删 | ✅ | ✅ | ✅ |
| 日志无敏感词 | ✅ | ✅ | ✅ |
| 保活间隔随机化 | ✅ | ✅ | ✅ |
| 每日重启时间随机 | ✅ | ✅ | ✅ |
| 控制台 1 分钟自动清屏 | ✅ | ✅ | ✅ |
| 本地预置 .so 文件 | — | ✅ | ✅ |

## 节点名称自动检测

`node_name` 留空时自动从 `api.ip.sb/geoip` 获取 ISP，格式：`国家代码-运营商`，如 `CN-Telecom`、`JP-Ionos`、`US-Google`。