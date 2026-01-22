# LLM Event Streaming Protocol (LESP)

Version: 1.0
Status: **Frozen as of v1.0**

---

## 1. Purpose / 目标

The LLM Event Streaming Protocol (LESP) defines a **minimal, failure-aware event stream** for delivering Large Language Model (LLM) outputs to client applications.
<br>LLM 事件流协议 (LESP) 定义了一个**极简的、故障感知**的事件流，用于将 LLM 输出传递给客户端应用。

This protocol is designed under the assumption that **LLMs are unreliable by default**: they may produce malformed output, stall indefinitely, or terminate streams unexpectedly.
<br>本协议基于**"LLM 默认不可靠"**的假设设计：它们可能会产生畸形输出、无限停顿或意外终止流。

LESP prioritizes:

- explicit event semantics (明确的事件语义)
- failure classification (失败分类)
- client-side resilience (客户端韧性)

---

## 2. Core Concepts / 核心概念

### 2.1 Event-Oriented, Not Byte-Oriented

**面向事件，而非面向字节**

LESP is an **event protocol**, not a raw byte stream.
<br>LESP 是一个**事件协议**，而不是原始字节流。

Each output from the backend is delivered as a discrete **event** with explicit meaning. Transport mechanisms (SSE, WebSocket, gRPC, etc.) are considered implementation details.
<br>后端的每个输出都作为一个具有明确含义的离散**事件**传递。传输机制（SSE, WebSocket, gRPC 等）被视为实现细节。

---

## 3. Event Model / 事件模型

### 3.1 LLMEvent

Each event MUST conform to the following structure:
<br>每个事件必须符合以下结构：

```json
{
  "type": "token | final | error | meta",
  "payload": <any>,
  "retryable": false
}
```

**Fields / 字段定义**

| Field | Description (English) | 说明 (中文) |
|-------|-----------------------|-------------|
| `type` | The semantic type of the event | 事件的语义类型 |
| `payload` | Event-specific data | 事件特定数据 |
| `retryable` | Whether a failed request MAY be retried | 失败请求是否可以重试 |

---

## 4. Event Types / 事件类型

### 4.1 token

Represents a partial output fragment from the LLM.
<br>代表 LLM 的部分输出片段。

- **MAY** be emitted multiple times (可能触发多次)
- **Ordering MUST** be preserved (必须保持顺序)
- Clients **SHOULD** treat tokens as ephemeral (客户端应将其视为短暂的)

**Example payload:**

```json
"Hello, wor"
```

### 4.2 final

Indicates successful completion of the request.
<br>表示请求成功完成。

- **MUST** be emitted at most once (最多触发一次)
- Terminates the stream (终止流)
- After final, no further events **MUST** be sent (final 之后不得再发送事件)

**Example payload:**

```json
"Hello, world!"
```

### 4.3 error

Represents a terminal failure.
<br>表示终止性故障。

- **MUST** terminate the stream (必须终止流)
- **MAY** be retryable or non-retryable (可能是可重试或不可重试的)

**Example payload:**

```json
{
  "message": "Backend unavailable",
  "code": 503
}
```

**Retry semantics / 重试语义:**

- `retryable = true`: client **MAY** retry the request (客户端可以重试)
- `retryable = false`: client **MUST NOT** retry automatically (客户端不得自动重试)

### 4.4 meta

Provides optional non-output metadata.
<br>提供可选的非输出元数据。

- **MAY** be emitted at any time (可能随时触发)
- **MUST NOT** affect stream termination (不得影响流终止)
- Clients **MAY** ignore this event (客户端可以忽略此事件)

**Example payload:**

```json
{
  "latency_ms": 842,
  "model": "qwen-1.5b"
}
```

---

## 5. Failure Semantics / 故障语义

LESP explicitly distinguishes between failure categories:
<br>LESP 明确区分了故障类别：

### 5.1 Retryable Failures (可重试故障)

- Network interruption (网络中断)
- Backend unavailable (e.g. 5xx, 429) (后端不可用)
- Transport-level termination (传输层终止)

### 5.2 Non-Retryable Failures (不可重试故障)

- Malformed or unsafe model output (畸形或不安全的模型输出)
- Policy violations (策略违规)
- Infinite or runaway generation (死循环或失控生成)

**Clients MUST respect the retryable flag.**
<br>**客户端必须遵守 retryable 标志。**

---

## 6. Transport Independence / 传输无关性

LESP does NOT mandate a specific transport layer.
<br>LESP 不强制要求特定的传输层。

**Reference implementations MAY use / 参考实现可以使用:**

- HTTP Server-Sent Events (SSE)
- WebSocket
- gRPC streaming

**All transports MUST preserve / 所有传输必须保留:**

- event ordering (事件顺序)
- event boundaries (事件边界)
- event semantics (事件语义)

---

## 7. Client Responsibilities / 客户端职责

Clients implementing LESP **SHOULD**:
<br>实现 LESP 的客户端**应该**：

- handle partial streams gracefully (优雅处理部分流)
- tolerate unexpected termination (容忍意外终止)
- avoid assuming well-formed model output (避免假设模型输出格式良好)
- surface failures as user-visible states, not crashes (将故障呈现为用户可见状态，而非崩溃)

---

## 8. Backward Compatibility Guarantees / 向后兼容性保证

As of version 1.0, the following are considered **breaking changes**:
<br>自 v1.0 起，以下情况被视为**破坏性变更**：

- removing or renaming event types (删除或重命名事件类型)
- changing the meaning of existing event fields (更改现有字段的含义)
- altering retry semantics (改变重试语义)

**Future v1.x releases MUST remain backward compatible with this specification.**
<br>**未来的 v1.x 版本必须保持与本规范的向后兼容性。**

---

## 9. Design Philosophy / 设计理念

> **Failure is not exceptional. It is the default operating condition.**
> <br>**故障并非例外。它是默认的操作条件。**

LESP exists to ensure client applications remain stable even when the underlying model is not.
<br>LESP 的存在是为了确保即使底层模型不稳定，客户端应用也能保持稳定。
