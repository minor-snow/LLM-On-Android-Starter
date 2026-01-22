"""
LLM-On-Android-Starter Server

A protocol-first server for reliable LLM streaming between Android clients and Python backends.
一个协议优先的服务端，用于实现 Android 客户端与 Python 后端之间的可靠 LLM 流式传输。

Usage / 用法:
    python server.py --mode dummy     # Dummy mode (default) / Dummy 模式（默认）
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
import sys

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
        latency_hint="30-80ms per token" if "dummy" in str(health_info.get("mode", "")) else None
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
  python server.py --mode dummy       # Dummy mode (default)
  python server.py --mode proxy       # Proxy to LLM_BACKEND_URL
  
  # Advanced Dummy Options:
  python server.py --mode dummy --dummy-backend mock
  python server.py --mode dummy --dummy-fault slow
  
Environment Variables:
  LLM_BACKEND_URL     Backend URL for proxy mode (default: http://127.0.0.1:11434)
  LLM_BACKEND_TIMEOUT Timeout in seconds (default: 120)
  LLM_MODEL           Model name for proxy (default: "default")
        """
    )
    
    # NOTE: We removed choices=["dummy", "proxy"] to allow hidden legacy modes (echo/mock)
    parser.add_argument(
        "--mode", 
        default="dummy",
        help="Backend mode: 'dummy' (default) or 'proxy'"
    )
    
    parser.add_argument(
        "--dummy-backend",
        choices=["echo", "mock"],
        default="echo",
        help="Type of dummy backend (default: echo)"
    )
    
    parser.add_argument(
        "--dummy-fault",
        choices=["none", "slow", "drop_stream", "bad_json"],
        default="none",
        help="Inject faults for reliability testing (default: none)"
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
    
    # Manual validation for mode
    valid_modes = ["dummy", "proxy", "echo", "mock"]
    if args.mode not in valid_modes:
        print(f"Error: argument --mode: invalid choice: '{args.mode}' (choose from 'dummy', 'proxy')")
        sys.exit(1)
        
    if args.mode in ["echo", "mock"]:
        print(f"\n[!] WARNING: mode '{args.mode}' is deprecated. Use '--mode dummy --dummy-backend {args.mode}' instead.\n")
    
    # Load adapter based on mode
    adapter = load_adapter(
        mode=args.mode, 
        dummy_backend=args.dummy_backend,
        fault_mode=args.dummy_fault
    )
    
    # Print startup info
    print(f"""
╔══════════════════════════════════════════════════════════╗
║       LLM-On-Android-Starter Server v1.0.1               ║
╠══════════════════════════════════════════════════════════╣
║  Mode:    {args.mode:<46} ║
║  Port:    {args.port:<46} ║
║  Host:    {args.host:<46} ║""")

    if args.dummy_fault != "none":
        print(f"║  FAULT:   {args.dummy_fault.upper() + ' (Reliability Test)':<46} ║")
        
    print("╚══════════════════════════════════════════════════════════╝")
    
    if args.mode == "proxy":
        import os
        url = os.getenv("LLM_BACKEND_URL", "http://127.0.0.1:11434")
        print(f"\n[*] Proxy Config: {url}")
        
        # Quick reachability check
        print("[*] Checking backend reachability...")
        try:
            health = asyncio.run(adapter.health_check())
            if health.get("status") == "ok":
                print(f"    Backend Reachable: ✅  ({health.get('backend_url', 'unknown')})")
            else:
                print(f"    Backend Reachable: ❌  (Error: {health.get('message', 'unknown')})")
        except Exception as e:
             print(f"    Backend Reachable: ❌  (Exception: {str(e)})")
    else:
        # Dummy details
        backend_type = args.dummy_backend if args.mode == "dummy" else args.mode
        print(f"\n[*] Dummy Backend: {backend_type.upper()}")
        
        if args.dummy_fault != "none":
             print(f"[!] FAULT INJECTION ACTIVE: {args.dummy_fault}")
             if args.dummy_fault == "drop_stream":
                 print("    -> Will interrupt stream midway.")
             elif args.dummy_fault == "slow":
                 print("    -> Will add significant latency.")
             elif args.dummy_fault == "bad_json":
                 print("    -> Will send malformed protocol data.")
    
    print("\n[>] Server ready. Waiting for connections...\n")
    
    uvicorn.run(app, host=args.host, port=args.port)


if __name__ == "__main__":
    main()
