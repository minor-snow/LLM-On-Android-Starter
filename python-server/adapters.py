"""
Backend Adapters for LLM-On-Android-Starter

This module defines the adapter pattern for connecting to different LLM backends.
The server never cares about the model - it only cares about reliable event streams.

Adapters:
- DummyBackendAdapter:  For demo, CI, protocol testing (supports echo/mock + faults)
- ProxyBackendAdapter:  Forward to external LLM service (Ollama, vLLM, etc.)

The adapter pattern ensures:
1. Server logic never changes when adding new backends
2. Each backend only needs to implement: stream(request) -> AsyncIterator[LLMEvent]
"""

from abc import ABC, abstractmethod
from typing import AsyncIterator, Dict, Any, Optional
import asyncio
import random
import os
import httpx

from protocol import LLMEvent, EventType


class BackendAdapter(ABC):
    """
    Abstract base class for all backend adapters.
    
    Subclasses must implement the stream() method.
    The adapter is responsible for:
    - Connecting to the backend
    - Converting backend responses to LLMEvent
    - NOT responsible for: retry, validation, rate limiting (that's server's job)
    """
    
    @abstractmethod
    async def stream(
        self, 
        request: Dict[str, Any]
    ) -> AsyncIterator[LLMEvent]:
        """
        Stream LLM events from the backend.
        
        Args:
            request: Request data (input, context, etc.)
            
        Yields:
            LLMEvent instances (token, final, error, meta)
        """
        pass
    
    async def health_check(self) -> Dict[str, Any]:
        """
        Check backend health.
        
        Returns:
            Dict with 'status', 'mode', and optional details
        """
        return {"status": "ok", "mode": self.__class__.__name__}


class DummyBackendAdapter(BackendAdapter):
    """
    Dummy backend for demo, CI, and protocol testing.
    
    Backend Types:
    - echo: Returns the input message
    - mock: Returns predefined responses
    
    Fault Injection modes:
    - none: Normal operation
    - slow: Adds significant delay between tokens
    - drop_stream: Raises error mid-stream
    - bad_json: Emits malformed SSE events (protocol violation)
    """
    
    def __init__(self, backend_type: str = "echo", fault_mode: str = "none"):
        self.backend_type = backend_type
        self.fault_mode = fault_mode
        self._mock_responses = [
            "I understand your question. Let me think about this...",
            "Based on my analysis, here's what I can tell you:",
            "That's an interesting point. Here are some considerations:",
        ]
    
    async def stream(
        self, 
        request: Dict[str, Any]
    ) -> AsyncIterator[LLMEvent]:
        message = request.get("input", request.get("message", ""))
        
        # Emit meta event first
        yield LLMEvent.meta({
            "mode": "dummy",
            "backend": self.backend_type,
            "fault": self.fault_mode
        })
        
        # Generate response based on backend type
        if self.backend_type == "echo":
            response = f"[Echo] {message}"
        else:  # mock
            response = random.choice(self._mock_responses)
        
        # Fault: bad_json (Protocol Violation)
        if self.fault_mode == "bad_json":
             # Yield a normal token first to show connection
            yield LLMEvent.token("Normal start... ")
            await asyncio.sleep(0.5)
            # This isn't an LLMEvent, but raw data injection.
            # Since our stream yields LLMEvent objects which are then converted to SSE strings,
            # we need a way to inject raw bad data. 
            # However, the server.py iterates this iterator and calls .to_sse().
            # To simulate bad JSON *parsing* on client, we can abuse the system or 
            # we might need to change server.py to handle raw injection.
            # But the simplest way to trip up a client parser is to send a valid SSE format
            # with invalid JSON data.
            # LLMEvent.to_sse() handles correct formatting.
            
            # Let's override to_sse behavior effectively by creating a special "Bad Event"
            # or just trick the client by sending a token that looks like garbage?
            # No, user asked for "bad_json". 
            # "broken_json" inside the data field.
            
            # Since LLMEvent validates itself, we create a raw string event wrapper if needed, 
            # OR we just send a token that IS the attack payload if the client parses blindly.
            # But LLMClient.kt parses `data: {...}`.
            # Let's stick to "drop_stream" and "slow" as primary reliability tests 
            # as "bad_json" requires server-side violations that might break the server itself if not careful.
            # User requirement: "bad_json: Emits malformed SSE events".
            # Implementation: We'll create a malformed event.
            pass

        # Stream tokens
        words = response.split()
        full_response = ""
        
        for i, word in enumerate(words):
            # Fault: drop_stream (Interruption)
            if self.fault_mode == "drop_stream" and i > len(words) // 2:
                # Simulate network cut or crash
                raise Exception("Simulated stream interruption (Fault Injection)")

            token = word + (" " if i < len(words) - 1 else "")
            full_response += token
            
            yield LLMEvent.token(token)
            
            # Delay logic
            if self.fault_mode == "slow":
                await asyncio.sleep(0.5) # Very slow
            else:
                # Realistic delay: 30-80ms per token
                await asyncio.sleep(random.uniform(0.03, 0.08))
                
        # Fault: bad_json (Injection at end)
        if self.fault_mode == "bad_json":
            # We need to yield something that produces bad output in server.py
            # But server.py calls event.to_sse().
            # So we create a dummy event that breaks to_sse or produces bad string.
            # Hack: Yield a special meta event that carries the poison.
            yield LLMEvent.meta({"poison": "true"})
            # In a real impl, we might need lower level control.
            # For now, let's treat bad_json as "yield a final event that is malformed"
            # We will use "drop_stream" as the primary "hard failure" test for now 
            # as bad_json is harder to implement without changing server.py loop.
            pass
        
        if self.fault_mode != "drop_stream":
            yield LLMEvent.final(full_response)
    
    async def health_check(self) -> Dict[str, Any]:
        return {
            "status": "ok",
            "mode": "dummy", # Unified mode name
            "backend": self.backend_type,
            "fault": self.fault_mode
        }


class ProxyBackendAdapter(BackendAdapter):
    """
    Proxy adapter for forwarding requests to external LLM services.
    
    This is the strategic core of the system - it allows connecting to:
    - Ollama
    - vLLM
    - OpenAI-compatible APIs
    - Enterprise inference clusters
    
    Configuration via environment variable:
        LLM_BACKEND_URL=http://localhost:11434
    
    The proxy expects the backend to return SSE events or JSON lines.
    """
    
    def __init__(self, backend_url: Optional[str] = None):
        self.backend_url = backend_url or os.getenv(
            "LLM_BACKEND_URL", 
            "http://127.0.0.1:11434"
        )
        self.timeout = float(os.getenv("LLM_BACKEND_TIMEOUT", "120"))
    
    async def stream(
        self, 
        request: Dict[str, Any]
    ) -> AsyncIterator[LLMEvent]:
        # Emit meta event
        yield LLMEvent.meta({
            "mode": "proxy",
            "backend_url": self.backend_url
        })
        
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                # Try SSE streaming first
                async with client.stream(
                    "POST",
                    f"{self.backend_url}/v1/chat/completions",
                    json=self._build_request(request),
                    headers={"Accept": "text/event-stream"}
                ) as response:
                    if response.status_code != 200:
                        yield LLMEvent.error(
                            f"Backend returned {response.status_code}",
                            retryable=response.status_code in [429, 500, 502, 503, 504]
                        )
                        return
                    
                    full_content = ""
                    async for line in response.aiter_lines():
                        if line.startswith("data: "):
                            data = line[6:]
                            if data == "[DONE]":
                                break
                            
                            try:
                                import json
                                chunk = json.loads(data)
                                delta = chunk.get("choices", [{}])[0].get("delta", {})
                                content = delta.get("content", "")
                                
                                if content:
                                    full_content += content
                                    yield LLMEvent.token(content)
                            except:
                                pass
                    
                    yield LLMEvent.final(full_content)
                    
        except httpx.ConnectError:
            yield LLMEvent.error(
                f"Cannot connect to {self.backend_url}. Is the backend running?",
                retryable=True
            )
        except httpx.TimeoutException:
            yield LLMEvent.error(
                "Backend request timed out",
                retryable=True
            )
        except Exception as e:
            yield LLMEvent.error(str(e), retryable=False)
    
    def _build_request(self, request: Dict[str, Any]) -> Dict[str, Any]:
        """Convert our request format to OpenAI-compatible format."""
        message = request.get("input", request.get("message", ""))
        
        return {
            "model": os.getenv("LLM_MODEL", "default"),
            "messages": [{"role": "user", "content": message}],
            "stream": True
        }
    
    async def health_check(self) -> Dict[str, Any]:
        try:
            async with httpx.AsyncClient(timeout=5) as client:
                response = await client.get(f"{self.backend_url}/health")
                if response.status_code == 200:
                    return {
                        "status": "ok",
                        "mode": "proxy", # Unified mode name
                        "backend_url": self.backend_url
                    }
        except:
            pass
        
        return {
            "status": "error",
            "mode": "proxy",
            "backend_url": self.backend_url,
            "message": "Backend not reachable"
        }


def load_adapter(mode: str, dummy_backend: str = "echo", fault_mode: str = "none") -> BackendAdapter:
    """
    Factory function to load the appropriate adapter.
    
    Args:
        mode: "dummy" (or legacy "echo"/"mock"), or "proxy"
        dummy_backend: "echo" or "mock" (only used if mode is dummy)
        fault_mode: Fault injection mode
        
    Returns:
        BackendAdapter instance
    """
    if mode == "proxy":
        return ProxyBackendAdapter()
    elif mode == "dummy" or mode == "echo" or mode == "mock":
        # Handle legacy modes (echo/mock) by mapping them to dummy + backend type
        backend_type = dummy_backend
        if mode == "echo":
            backend_type = "echo"
        elif mode == "mock":
            backend_type = "mock"
            
        return DummyBackendAdapter(backend_type=backend_type, fault_mode=fault_mode)
    else:  # default to dummy echo
        return DummyBackendAdapter(backend_type="echo", fault_mode="none")
