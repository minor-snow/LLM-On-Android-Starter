"""
LLM Event Streaming Protocol (LESP)

This module defines the core protocol for LLM streaming communication.
The protocol is transport-agnostic (SSE, WebSocket, gRPC can all implement it).

Event Types:
- token:  Partial output during streaming
- final:  Completed output (end of stream)
- error:  Error occurred (may be retryable)
- meta:   Optional metadata (mode, timing, etc.)

This is a protocol-first design. The event semantics are fixed,
but the transport layer can change.
"""

from dataclasses import dataclass, field
from typing import Literal, Any, Dict
from enum import Enum
import json


class EventType(str, Enum):
    """LLM Event types - these are protocol-level constants."""
    TOKEN = "token"
    FINAL = "final"
    ERROR = "error"
    META = "meta"


@dataclass
class LLMEvent:
    """
    Core event type for LLM streaming protocol.
    
    This is the canonical representation of all events in the system.
    All BackendAdapters must emit LLMEvent instances.
    
    Attributes:
        type: Event type (token/final/error/meta)
        payload: Event-specific data
        retryable: Whether the client should retry on this error
    """
    type: EventType
    payload: Any
    retryable: bool = False
    
    @classmethod
    def token(cls, content: str) -> "LLMEvent":
        """Create a token event (partial output)."""
        return cls(type=EventType.TOKEN, payload=content)
    
    @classmethod
    def final(cls, content: str) -> "LLMEvent":
        """Create a final event (complete output)."""
        return cls(type=EventType.FINAL, payload=content)
    
    @classmethod
    def error(cls, message: str, retryable: bool = False) -> "LLMEvent":
        """Create an error event."""
        return cls(type=EventType.ERROR, payload=message, retryable=retryable)
    
    @classmethod
    def meta(cls, data: Dict[str, Any]) -> "LLMEvent":
        """Create a metadata event."""
        return cls(type=EventType.META, payload=data)
    
    def to_sse(self) -> str:
        """Serialize to Server-Sent Events format."""
        data = {
            "type": self.type.value,
            "content" if self.type == EventType.TOKEN else 
            "content" if self.type == EventType.FINAL else
            "message" if self.type == EventType.ERROR else "data": self.payload
        }
        if self.type == EventType.ERROR:
            data["retryable"] = self.retryable
        return f"data: {json.dumps(data)}\n\n"
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        result = {"type": self.type.value}
        
        if self.type == EventType.TOKEN:
            result["content"] = self.payload
        elif self.type == EventType.FINAL:
            result["content"] = self.payload
        elif self.type == EventType.ERROR:
            result["message"] = self.payload
            result["retryable"] = self.retryable
        elif self.type == EventType.META:
            result.update(self.payload)
        
        return result
