"""
Backend Adapters for LLM-On-Android-Starter

This module defines the adapter pattern for connecting to different LLM backends.
The server never cares about the model - it only cares about reliable event streams.

Adapters:
- DummyBackendAdapter:  For demo, CI, protocol testing
- ProxyBackendAdapter:  Forward to external LLM service (Ollama, vLLM, etc.)
- LocalBackendAdapter:  (Future) Local model loading

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
    
    Modes:
    - echo: Returns the input message
    - mock: Returns predefined responses
    
    Features:
    - Simulates realistic token delays (30-80ms)
    - Can simulate errors for testing
    """
    
    def __init__(self, mode: str = "echo"):
        self.mode = mode
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
            "mode": self.mode,
            "backend": "dummy"
        })
        
        # Generate response based on mode
        if self.mode == "echo":
            response = f"[Echo] {message}"
        else:  # mock
            response = random.choice(self._mock_responses)
        
        # Stream tokens with realistic delays
        words = response.split()
        full_response = ""
        
        for i, word in enumerate(words):
            token = word + (" " if i < len(words) - 1 else "")
            full_response += token
            
            yield LLMEvent.token(token)
            
            # Realistic delay: 30-80ms per token
            await asyncio.sleep(random.uniform(0.03, 0.08))
        
        # Emit final event
        yield LLMEvent.final(full_response)
    
    async def health_check(self) -> Dict[str, Any]:
        return {
            "status": "ok",
            "mode": self.mode,
            "backend": "dummy"
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
                        "mode": "proxy",
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


def load_adapter(mode: str) -> BackendAdapter:
    """
    Factory function to load the appropriate adapter.
    
    Args:
        mode: "echo", "mock", or "proxy"
        
    Returns:
        BackendAdapter instance
    """
    if mode == "proxy":
        return ProxyBackendAdapter()
    elif mode == "mock":
        return DummyBackendAdapter(mode="mock")
    else:  # default to echo
        return DummyBackendAdapter(mode="echo")
