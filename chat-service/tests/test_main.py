"""Smoke and schema tests for chat-service (no live DB or LLM)."""

import pytest
from pydantic import ValidationError
from fastapi.testclient import TestClient

from main import ChatRequest, HealthReply, app

client = TestClient(app)


def test_health_returns_ok():
    response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert "configured" in body
    HealthReply.model_validate(body)


def test_chat_request_accepts_valid_message():
    req = ChatRequest(session_id="sess-1", user_id=42, message="Hello")
    assert req.message == "Hello"


def test_chat_request_rejects_message_over_max_length():
    with pytest.raises(ValidationError) as exc_info:
        ChatRequest(session_id="sess-1", user_id=1, message="x" * 4001)
    assert any(e["loc"] == ("message",) for e in exc_info.value.errors())

