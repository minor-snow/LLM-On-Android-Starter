# Release Notes / 发布说明

## v1.0.0-beta

> **Establishment of the Standard**
> **标准的建立**

**This release establishes the stable protocol (LESP) and client API.**
**本次发布确立了稳定的协议 (LESP) 和客户端 API。**

### Core Commitments / 核心承诺

1. **Protocol Frozen (协议冻结)**:
    * The `LLMEvent` structure (type, payload, retryable) is now frozen.
    * `LLMEvent` 结构（类型、负载、重试机制）现已冻结。

2. **Transport Independence (传输无关性)**:
    * **v1.x** uses HTTP SSE as the verified reference implementation.
    * Future versions (v2/v3) may add WebSocket or gRPC transports, but **will not break the event protocol**.
    * **v1.x** 使用 HTTP SSE 作为经过验证的参考实现。
    * 未来版本 (v2/v3) 可能会增加 WebSocket 或 gRPC 传输层，但**绝不会破坏事件协议**。

3. **Backward Compatibility (向后兼容性)**:
    * We promise that applications built on v1.0.0-beta will work with all v1.x releases.
    * 我们承诺，基于 v1.0.0-beta 构建的应用将兼容所有的 v1.x 版本。

### Features / 功能特性

* **Failure-First Design**: Graceful handling of network, JSON, and timeout errors.
    <br> **故障优先设计**：优雅处理网络、JSON 和超时错误。
* **Self-Diagnosis**: Server and Client provide human-readable hints for misconfigurations.
    <br> **自诊断能力**：服务端和客户端为配置错误提供人类可读的提示。
* **Bilingual Documentation**: First-class support for English and Chinese developers.
    <br> **双语文档**：对中英文开发者提供一等公民支持。

---

*Ready for production integration.*
*已准备好集成到生产环境。*
