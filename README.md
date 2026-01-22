# LLM-On-Android-Starter

> **A failure-first LLM streaming framework for Android clients.**
>
> Stop crashing your app because an LLM broke JSON.
>
> **Android ↔ Python LLM 可靠通信框架**
>
> 拒绝让 LLM 的坏数据弄崩你的 App。

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Android SDK](https://img.shields.io/badge/Android%20SDK-24%2B-green.svg)](android-sdk)
[![Python](https://img.shields.io/badge/Python-3.9%2B-blue.svg)](python-server)

## Philosophy / 设计理念

> **This project defines a minimal, failure-aware LLM streaming protocol, and provides a reference implementation for Android + Python.**
>
> **本项目定义了一套极简的、故障感知的 LLM 流式传输协议，并提供了 Android + Python 的参考实现。**

This is not just a "demo that connects to an LLM". It is a communication framework designed **"not to be killed by LLM"**.

这不是一个"能连上 LLM 的 Demo"，而是一个 **"不会被 LLM 害死"** 的通信框架。

| Traditional Project / 普通项目 | This Project / 本项目 |
|---------|--------|
| **Works if lucky** (能跑就行) | **Graceful Handling** (断网/超时/坏JSON都优雅处理) |
| **String Concat** (字符串拼接) | **Type-Safe SSE** (类型安全 SSE 事件解析) |
| **GPU Required** (需要 GPU) | **Zero GPU** (Clone 后秒跑 - Dummy backend) |
| **Hardcoded** (硬编码后端) | **Adapter Pattern** (适配器模式，一行切换后端) |

### Protocol & Transport / 协议与传输

> **Protocol frozen as of v1.0.**
>
> **协议已于 v1.0 冻结。**

> **This project defines a minimal LLM Event Streaming Protocol (LESP).**
>
> **本项目定义了一个极简的 LLM 事件流协议 (LESP)。**

In **v1.x**, the reference transport implementation is **HTTP Server-Sent Events (SSE)**.
<br>在 **v1.x** 版本中，参考传输实现是 **HTTP Server-Sent Events (SSE)**。

Future versions may introduce additional transports (e.g. WebSocket, gRPC) without breaking the event protocol.
<br>未来版本可能会引入额外的传输方式（如 WebSocket, gRPC），但不会破坏事件协议。

### Core Features / 核心特性

- ✅ **Type-Safe Streaming** - SSE events with types (`token`/`final`/`error`/`meta`). <br> SSE 事件带类型，拒绝解析黑盒。
- ✅ **Protocol-First** - Defined explicit `LLMEvent` protocol, transport agnostic. <br> 协议优先，定义明确的 LLMEvent 协议，与传输层解耦。
- ✅ **Backend Adapters** - Switch between Dummy/Proxy modes instantly. <br> 后端适配器，Dummy/Proxy 模式自由切换。
- ✅ **Graceful Degradation** - Handles network drops, timeouts, and malformed JSON seamlessly. <br> 优雅降级，断网、超时、坏 JSON 都优雅处理。
- ✅ **Health Check** - Auto-detect connectivity on startup with clear diagnostics. <br> 健康检查，启动时自动检测连接，给出明确错误诊断。
- ✅ **Zero GPU** - Develop and test without a GPU using Dummy backend. <br>以此，无需显卡即可开发测试。

---

## Quick Start / 快速开始

### 1. Start Server / 启动服务端

```bash
cd python-server
pip install -r requirements.txt
python server.py --mode echo
```

**Expected Output / 预期输出:**

```
╔══════════════════════════════════════════════════════════╗
║       LLM-On-Android-Starter Server v1.0                 ║
╠══════════════════════════════════════════════════════════╣
║  Mode:    echo                                           ║
║  Port:    8000                                           ║
...
```

**Modes / 可用模式:**

- `--mode echo` - Echo mode (Default). Returns input as output. <br> 回声模式（默认），原样返回输入。
- `--mode mock` - Mock mode. Returns simulated LLM responses. <br> 模拟响应模式，返回预设的 LLM 回复。
- `--mode proxy` - Proxy mode. Forwards requests to real LLM backend (e.g., Ollama). <br> 代理模式，转发请求到真实 LLM 服务。

### 2. Configure Android / 配置 Android 连接

- **Emulator / 模拟器**: No config needed. Code uses `10.0.2.2:8000` by default. <br> 无需配置，代码默认使用 `10.0.2.2:8000`。
- **Real Device / 真机**: Run the following command: <br> 运行以下命令:

```bash
adb reverse tcp:8000 tcp:8000
```

### 3. Run Demo / 运行 Demo

```bash
./gradlew :demo-android:installDebug
```

Open App. If you see `✅ Connected (echo, XXms)`, you are ready! <br> 打开 App，看到 `✅ Connected` 即表示连接成功！

---

## Architecture /架构设计

```
┌─────────────────────┐          SSE/JSON          ┌─────────────────────┐
│    demo-android     │ ◄────────────────────────► │    python-server    │
│   (Compose UI)      │                            │     (FastAPI)       │
└──────────┬──────────┘                            └──────────┬──────────┘
           │                                                  │
           ▼                                                  ▼
┌─────────────────────┐                            ┌─────────────────────┐
│     android-sdk     │                            │   BackendAdapter    │
│  ┌───────────────┐  │                            │  ┌───────────────┐  │
│  │  LLMClient    │  │       LLMEvent             │  │ DummyAdapter  │  │
│  │  SSEParser    │  │  ◄────────────────────────►│  │ (echo/mock)   │  │
│  │ LLMResponse   │  │   token/final/error/meta   │  ├───────────────┤  │
│  └───────────────┘  │                            │  │ ProxyAdapter  │  │
│  ┌───────────────┐  │                            │  │ (Ollama, etc) │  │
│  │SafeLLMExecutor│  │                            │  └───────────────┘  │
│  │ (reliability) │  │                            └─────────────────────┘
│  └───────────────┘  │
└─────────────────────┘
```

### LLM Event Protocol (LESP)

All LLM responses follow a unified event format: <br> 所有 LLM 响应都遵循统一的事件格式：

```json
{"type": "token", "payload": "Hello", "timestamp": 1234567890}
{"type": "final", "payload": "Hello World", "timestamp": 1234567891}
{"type": "error", "payload": "Timeout", "retryable": true, "timestamp": 1234567892}
{"type": "meta",  "payload": {"model": "gpt-4"}, "timestamp": 1234567893}
```

---

## SDK Usage / SDK 使用指南

```kotlin
// 1. Create Client / 创建客户端
val client = LLMClient("http://10.0.2.2:8000")

// 2. Check Connection (Recommended) / 检查连接 (推荐)
when (val health = client.healthCheck()) {
    is HealthStatus.Ok -> Log.d("LLM", "Connected in ${health.latencyMs}ms")
    is HealthStatus.Error -> Log.e("LLM", "${health.message}\n${health.suggestion}")
}

// 3. Send Message & Stream Response / 发送消息并接收流式响应
client.chat("Hello").collect { response ->
    when (response) {
        is LLMResponse.Token -> print(response.content)  // Show token / 逐字显示
        is LLMResponse.Final -> println("\n✅ Done")
        is LLMResponse.Error -> println("❌ ${response.message}")
        is LLMResponse.Meta  -> { /* Metadata / 元数据 */ }
    }
}
```

### Advanced Config / 高级配置

```kotlin
// Use Config Object / 使用配置对象
val config = LLMClientConfig(
    baseUrl = "http://10.0.2.2:8000",
    connectTimeoutMs = 5000,
    readTimeoutMs = 60000,
    retryPolicy = RetryPolicy(
        maxRetries = 3,
        initialDelayMs = 1000,
        maxDelayMs = 30000
    )
)
val client = LLMClient(config)
```

---

## Connecting to Real LLM / 连接真实 LLM

Use Proxy Mode to connect to real backends: <br> 通过 Proxy 模式连接真实 LLM：

```bash
# Connect to Ollama (Local) / 连接到 Ollama
export LLM_BACKEND_URL=http://localhost:11434
python server.py --mode proxy

# Connect to OpenAI-compatible API / 连接到 OpenAI 兼容接口
export LLM_BACKEND_URL=http://your-api-server:8080
export LLM_MODEL=gpt-4
python server.py --mode proxy
```

---

## Troubleshooting / 故障排除

| Symptom / 症状 | Cause / 原因 | Solution / 解决方案 |
|-----|------|---------|
| `Cannot connect to...` | Port not mapped (端口未映射) | Real Device (真机): `adb reverse tcp:8000 tcp:8000` |
| `Connection timed out` | Server not running (Server 未启动) | Ensure `python server.py` is running (确认 server 在运行) |
| `Connection refused` | Firewall (防火墙拦截) | Check firewall settings (检查防火墙) |
| Emulator fails (模拟器连不上) | Wrong IP (IP 错误) | Use `10.0.2.2` instead of `localhost` (使用 10.0.2.2) |

---

## Project Structure / 项目结构

```
LLM-On-Android-Starter/
├── android-sdk/                    # Android SDK (No UI / 无 UI)
│   └── dev.llmbridge.client/
│       ├── LLMClient.kt            # HTTP Client + Health Check
│       ├── LLMClientConfig.kt      # Config + Retry Policy
│       ├── LLMResponse.kt          # Protocol Events / 协议定义
│       ├── ResultState.kt          # UI State Wrapper / UI 状态封装
│       └── SSEParser.kt            # Type-Safe SSE Parser
│   └── dev.llmbridge.reliability/
│       ├── SafeGenerationResult.kt # Safe Result Wrapper
│       └── SafeLLMExecutor.kt      # Reliability Layer / 可靠性执行层
│
├── demo-android/                   # Minimal Demo App
│   └── dev.llmbridge.demo/
│       └── MainActivity.kt         # ChatScreen
│
├── python-server/                  # FastAPI Server
│   ├── server.py                   # Entry Point / 主入口
│   ├── protocol.py                 # LLMEvent Definition
│   ├── adapters.py                 # Backend Adapters
│   └── requirements.txt
│
└── README.md
```

---

## License

MIT License - Free to use, please Star ⭐ if helpful. <br> MIT 协议 - 随便用，如果对你有帮助，请给个 Star ⭐。
