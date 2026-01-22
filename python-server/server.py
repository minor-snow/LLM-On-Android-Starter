"""
LLM-On-Android-Starter Server

A protocol-first server for reliable LLM streaming between Android clients and Python backends.
一个协议优先的服务端，用于实现 Android 客户端与 Python 后端之间的可靠 LLM 流式传输。

Usage / 用法:
    python server.py --mode echo      # Dummy echo mode (default) / 回声模式（默认）
    python server.py --mode mock      # Dummy mock mode / 模拟响应模式
    python server.py --mode proxy     # Proxy to LLM_BACKEND_URL / 代理模式

The server implements the LLM Event Streaming Protocol (LESP):
服务端实现了 LLM 事件流协议 (LESP):
- token:  Partial output during streaming / 流式部分输出
- final:  Completed output (end of stream) / 完整输出（流结束）
- error:  Error occurred (may be retryable) / 发生错误（可能可重试）
- meta:   Optional metadata / 可选元数据
"""

import argparse
import asyncio
from fastapi import FastAPI
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from typing import Optional, Any, Dict
import uvicorn
import json

from protocol import LLMEvent
from adapters import load_adapter, BackendAdapter


# ==========================================
# Request/Response Models
# ==========================================

class ChatRequest(BaseModel):
    """
    Chat request model.
    
    Note: 'input' is preferred over 'message' for forward compatibility
    with multi-modal inputs (vision, tool calls, etc.)
    """
    message: Optional[str] = None  # Backward compat
    input: Optional[Any] = None    # Preferred (can be any type)
    context: Optional[Dict[str, Any]] = None
    thread_id: Optional[str] = None
    
    def get_input(self) -> Any:
        """Get the actual input, preferring 'input' over 'message'."""
        return self.input or self.message or ""


class HealthResponse(BaseModel):
    status: str
    mode: str
    backend: Optional[str] = None
    latency_hint: Optional[str] = None


# ==========================================
# App Initialization
# ==========================================

app = FastAPI(
    title="LLM-On-Android-Starter",
    description="Protocol-first server for reliable LLM streaming",
    version="1.0.0"
)

# Global adapter - set by main()
adapter: BackendAdapter = None


# ==========================================
# Endpoints
# ==========================================

@app.get("/health", response_model=HealthResponse)
async def health():
    """
    Health check endpoint.
    
    Returns server status and current mode.
    Used by Android client to verify connectivity.
    """
    health_info = await adapter.health_check()
    return HealthResponse(
        status=health_info.get("status", "ok"),
        mode=health_info.get("mode", "unknown"),
        backend=health_info.get("backend"),
        latency_hint="30-80ms per token" if "dummy" in str(health_info.get("backend", "")) else None
    )


@app.post("/chat")
async def chat(request: ChatRequest):
    """
    Streaming chat endpoint.
    
    Accepts a message and streams LLMEvent responses via SSE.
    The response format follows the LLM Event Streaming Protocol (LESP).
    """
    async def event_generator():
        try:
            # Build request dict
            req = {
                "input": request.get_input(),
                "context": request.context,
                "thread_id": request.thread_id
            }
            
            # Stream from adapter
            async for event in adapter.stream(req):
                yield event.to_sse()
                
        except Exception as e:
            yield LLMEvent.error(str(e), retryable=False).to_sse()
    
    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        }
    )


# ==========================================
# Main Entry Point
# ==========================================

def main():
    global adapter
    
    parser = argparse.ArgumentParser(
        description="LLM-On-Android-Starter Server",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python server.py --mode echo       # Echo mode (default)
  python server.py --mode mock       # Mock responses
  python server.py --mode proxy      # Proxy to LLM_BACKEND_URL
  
Environment Variables:
  LLM_BACKEND_URL     Backend URL for proxy mode (default: http://127.0.0.1:11434)
  LLM_BACKEND_TIMEOUT Timeout in seconds (default: 120)
  LLM_MODEL           Model name for proxy (default: "default")
        """
    )
    
    parser.add_argument(
        "--mode", 
        choices=["echo", "mock", "proxy"],
        default="echo",
        help="Backend mode (default: echo)"
    )
    parser.add_argument(
        "--port",
        type=int,
        default=8000,
        help="Server port (default: 8000)"
    )
    parser.add_argument(
        "--host",
        default="0.0.0.0",
        help="Server host (default: 0.0.0.0)"
    )
    
    args = parser.parse_args()
    
    # Load adapter based on mode
    adapter = load_adapter(args.mode)
    
    # Print startup info
    print(f"""
╔══════════════════════════════════════════════════════════╗
║       LLM-On-Android-Starter Server v1.0                 ║
╠══════════════════════════════════════════════════════════╣
║  Mode:    {args.mode:<46} ║
║  Port:    {args.port:<46} ║
║  Host:    {args.host:<46} ║
╚══════════════════════════════════════════════════════════╝
""")

    if args.mode == "proxy":
        import os
        url = os.getenv("LLM_BACKEND_URL", "http://127.0.0.1:11434")
        print(f"[*] Proxy Config: {url}")
        
        # Quick reachability check
        print("[*] Checking backend reachability...")
        try:
            health = asyncio.run(adapter.health_check())
            if health.get("status") == "ok":
                print(f"    Backend Reachable: ✅  ({health.get('backend', 'unknown')})")
            else:
                print(f"    Backend Reachable: ❌  (Error: {health.get('error', 'unknown')})")
        except Exception as e:
             print(f"    Backend Reachable: ❌  (Exception: {str(e)})")
    else:
        print(f"[*] Dummy mode: {args.mode} (no real LLM)")
    
    print("\n[>] Server ready. Waiting for connections...\n")
    
    uvicorn.run(app, host=args.host, port=args.port)


if __name__ == "__main__":
    main()
