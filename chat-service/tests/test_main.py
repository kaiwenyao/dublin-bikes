"""Unit tests for chat-service (mocked DB/LLM; no live credentials)."""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage, HumanMessage
from pydantic import ValidationError

from main import (
    ChatRequest,
    HealthReply,
    MessageItem,
    Settings,
    TitleReply,
    _map_messages,
    _psycopg_conninfo,
    app,
)

client = TestClient(app)
client_no_raise = TestClient(app, raise_server_exceptions=False)


def _configured_settings() -> Settings:
    return Settings(
        chat_db_url="postgresql://user:pass@localhost:5432/chat",
        deepseek_api_key="test-key",
    )


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


def test_psycopg_conninfo_strips_sqlalchemy_driver_and_fixes_sslmode():
    url = "postgresql+psycopg://user:pass@host:5432/db?sslmode"
    assert _psycopg_conninfo(url) == "postgresql://user:pass@host:5432/db?sslmode=require"


def test_chat_request_rejects_message_over_max_length():
    with pytest.raises(ValidationError) as exc_info:
        ChatRequest(session_id="sess-1", user_id=1, message="x" * 4001)
    assert any(e["loc"] == ("message",) for e in exc_info.value.errors())


def test_map_messages_maps_roles_and_serializes_list_content():
    items = _map_messages(
        [
            HumanMessage(content="hi"),
            AIMessage(content=[{"type": "text", "text": "yo"}]),
        ]
    )
    assert items == [
        MessageItem(role="user", content="hi"),
        MessageItem(role="assistant", content='[{"type": "text", "text": "yo"}]'),
    ]


@patch(
    "main._require_runtime",
    side_effect=HTTPException(status_code=503, detail="not configured"),
)
def test_chat_returns_503_when_not_configured(_mock_runtime):
    response = client.post(
        "/chat",
        json={"session_id": "s1", "user_id": 1, "message": "hi"},
    )
    assert response.status_code == 503


@patch("main._ensure_session_row")
@patch("main._memory")
@patch("main._llm")
@patch("main._require_runtime", return_value=_configured_settings())
def test_chat_persists_human_and_ai_on_success(
    _mock_runtime, mock_llm, mock_memory, _mock_ensure_session
):
    mem = MagicMock()
    mem.messages = []
    mock_memory.return_value = mem
    mock_llm.return_value.invoke.return_value = AIMessage(content="assistant reply")

    response = client.post(
        "/chat",
        json={"session_id": "sess-1", "user_id": 42, "message": "hello"},
    )

    assert response.status_code == 200
    assert response.json() == {"chat_id": "sess-1", "reply": "assistant reply"}
    mock_llm.return_value.invoke.assert_called_once()
    assert mem.add_message.call_count == 2
    human, ai = mem.add_message.call_args_list[0][0][0], mem.add_message.call_args_list[1][0][0]
    assert isinstance(human, HumanMessage)
    assert human.content == "hello"
    assert isinstance(ai, AIMessage)
    assert ai.content == "assistant reply"


@patch("main._ensure_session_row")
@patch("main._memory")
@patch("main._llm")
@patch("main._require_runtime", return_value=_configured_settings())
def test_chat_does_not_persist_when_llm_fails(
    _mock_runtime, mock_llm, mock_memory, _mock_ensure_session
):
    mem = MagicMock()
    mem.messages = []
    mock_memory.return_value = mem
    mock_llm.return_value.invoke.side_effect = RuntimeError("upstream 503")

    response = client_no_raise.post(
        "/chat",
        json={"session_id": "sess-1", "user_id": 42, "message": "hello"},
    )

    assert response.status_code == 500
    mem.add_message.assert_not_called()


@patch("main._ensure_session_row")
@patch("main._memory")
@patch("main._llm")
@patch("main._require_runtime", return_value=_configured_settings())
def test_chat_title_returns_trimmed_title(
    _mock_runtime, mock_llm, _mock_memory, _mock_ensure_session
):
    mock_llm.return_value.invoke.return_value = AIMessage(content="  Bike routes  ")

    response = client.post("/chat/title", json={"message": "Where can I rent a bike?"})

    assert response.status_code == 200
    TitleReply.model_validate(response.json())
    assert response.json()["title"] == "Bike routes"


@patch("main._memory")
@patch("main._require_runtime", return_value=_configured_settings())
def test_session_messages_returns_mapped_history(mock_runtime, mock_memory):
    mem = MagicMock()
    mem.messages = [HumanMessage(content="q"), AIMessage(content="a")]
    mock_memory.return_value = mem

    response = client.get("/sessions/sess-9/messages")

    assert response.status_code == 200
    assert response.json() == [
        {"role": "user", "content": "q"},
        {"role": "assistant", "content": "a"},
    ]


@patch("main._ensure_session_row")
@patch("main._memory")
@patch("main._llm")
@patch("main._require_runtime", return_value=_configured_settings())
def test_chat_stream_emits_chunks_and_done(
    _mock_runtime, mock_llm, mock_memory, _mock_ensure_session
):
    mem = MagicMock()
    mem.messages = []
    mock_memory.return_value = mem

    async def astream(_history):
        for piece in ("Hel", "lo"):
            yield AIMessage(content=piece)

    mock_llm.return_value.astream = astream

    with client.stream(
        "POST",
        "/chat/stream",
        json={"session_id": "sess-1", "user_id": 42, "message": "hi"},
    ) as response:
        assert response.status_code == 200
        chunks = [line for line in response.iter_lines() if line.startswith("data: ")]

    assert any('{"content": "Hel"}' in c for c in chunks)
    assert any('{"content": "lo"}' in c for c in chunks)
    assert any(c.endswith("[DONE]") for c in chunks)
    assert mem.add_message.call_count == 2


@patch("main._ensure_session_row")
@patch("main._memory")
@patch("main._llm")
@patch("main._require_runtime", return_value=_configured_settings())
def test_chat_stream_does_not_persist_when_llm_fails_before_chunks(
    _mock_runtime, mock_llm, mock_memory, _mock_ensure_session
):
    mem = MagicMock()
    mem.messages = []
    mock_memory.return_value = mem

    async def failing_astream(_history):
        raise RuntimeError("upstream 429")
        yield AIMessage(content="never")  # pragma: no cover

    mock_llm.return_value.astream = failing_astream

    with client_no_raise.stream(
        "POST",
        "/chat/stream",
        json={"session_id": "sess-1", "user_id": 42, "message": "hi"},
    ) as response:
        list(response.iter_lines())

    mem.add_message.assert_not_called()


@patch("main._ensure_session_row")
@patch("main._memory")
@patch("main._llm")
@patch("main._require_runtime", return_value=_configured_settings())
def test_chat_stream_persists_partial_ai_on_mid_stream_failure(
    _mock_runtime, mock_llm, mock_memory, _mock_ensure_session
):
    mem = MagicMock()
    mem.messages = []
    mock_memory.return_value = mem

    async def partial_astream(_history):
        yield AIMessage(content="partial")
        raise RuntimeError("connection reset")

    mock_llm.return_value.astream = partial_astream

    with client_no_raise.stream(
        "POST",
        "/chat/stream",
        json={"session_id": "sess-1", "user_id": 42, "message": "hi"},
    ) as response:
        list(response.iter_lines())

    assert mem.add_message.call_count == 2
    ai = mem.add_message.call_args_list[1][0][0]
    assert isinstance(ai, AIMessage)
    assert ai.content == "partial"
