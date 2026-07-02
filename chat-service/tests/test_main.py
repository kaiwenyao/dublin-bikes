"""Unit tests for chat-service (mocked DB/LLM; no live credentials)."""

from __future__ import annotations

from datetime import datetime
from unittest.mock import MagicMock, patch

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient
from langchain_core.messages import (
    AIMessage,
    AIMessageChunk,
    HumanMessage,
    SystemMessage,
    ToolMessage,
)
from pydantic import ValidationError

from main import (
    ASSISTANT_GREETING,
    LOCATION_MISSING_INSTRUCTIONS,
    ChatRequest,
    HealthReply,
    MessageItem,
    Settings,
    TitleReply,
    get_nearest_station_availability,
    _build_messages,
    _map_messages,
    _psycopg_conninfo,
    app,
    tools,
)

client = TestClient(app)
client_no_raise = TestClient(app, raise_server_exceptions=False)


@pytest.fixture(autouse=True)
def reset_sse_starlette_appstatus_event():
    """Reset sse-starlette's exit-event singleton between tests.

    AppStatus.should_exit_event binds to the first test's event loop; later
    TestClient requests run on fresh loops and would raise RuntimeError.
    Documented workaround from the sse-starlette README.
    """
    from sse_starlette.sse import AppStatus

    AppStatus.should_exit_event = None


def _configured_settings() -> Settings:
    return Settings(
        chat_db_url="postgresql://user:pass@localhost:5432/chat",
        deepseek_api_key="test-key",
    )


def _mock_tool_bound_llm(mock_llm, *responses):
    bound = mock_llm.return_value.bind.return_value
    if len(responses) == 1:
        bound.invoke.return_value = responses[0]
    else:
        bound.invoke.side_effect = list(responses)
    return bound


def _mock_streaming_llm(mock_llm, *streams):
    bound = mock_llm.return_value.bind.return_value
    pending_streams = list(streams)
    bound.stream_messages = []

    async def astream(messages):
        bound.stream_messages.append(messages)
        for chunk in pending_streams.pop(0):
            yield chunk

    bound.astream = astream
    return bound


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


def test_chat_request_accepts_location_context():
    req = ChatRequest(
        session_id="sess-1",
        user_id=42,
        message="nearest station",
        location={"lat": 53.3498, "lng": -6.2603, "accuracy_m": 25},
    )
    assert req.location is not None
    assert req.location.lat == 53.3498


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


@patch("main.psycopg.connect")
def test_get_nearest_station_availability_ranks_by_distance(mock_connect):
    conn = MagicMock()
    cursor = MagicMock()
    mock_connect.return_value.__enter__.return_value = conn
    conn.cursor.return_value.__enter__.return_value = cursor
    cursor.fetchall.return_value = [
        (
            1,
            "Far Station",
            "Far Address",
            53.3600,
            -6.2800,
            20,
            3,
            17,
            "OPEN",
            datetime(2026, 1, 1, 10, 0),
            datetime(2026, 1, 1, 10, 1),
        ),
        (
            2,
            "Near Station",
            "Near Address",
            53.3499,
            -6.2604,
            30,
            8,
            22,
            "OPEN",
            datetime(2026, 1, 1, 10, 2),
            datetime(2026, 1, 1, 10, 3),
        ),
    ]
    req = ChatRequest(
        session_id="sess-1",
        user_id=42,
        message="nearest station",
        location={"lat": 53.3498, "lng": -6.2603},
    )

    result = get_nearest_station_availability(req, _configured_settings(), limit=1)

    assert result["stations"][0]["number"] == 2
    assert result["stations"][0]["available_bikes"] == 8
    assert result["stations"][0]["distance_m"] < 50


def test_build_messages_includes_location_system_message_when_present():
    req = ChatRequest(
        session_id="sess-1",
        user_id=42,
        message="nearest station",
        location={"lat": 53.3498, "lng": -6.2603, "accuracy_m": 25},
    )
    mem = MagicMock()
    mem.messages = []
    pending = HumanMessage(content="nearest station")

    messages = _build_messages(req, mem, pending)

    assert isinstance(messages[0], SystemMessage)
    assert "53.3498" in messages[0].content
    assert "-6.2603" in messages[0].content
    assert "get_nearest_station_availability" in messages[0].content
    assert messages[1].content == ASSISTANT_GREETING
    assert messages[-1] is pending


def test_build_messages_omits_accuracy_when_not_provided():
    req = ChatRequest(
        session_id="sess-1",
        user_id=42,
        message="nearest station",
        location={"lat": 53.3498, "lng": -6.2603},
    )
    mem = MagicMock()
    mem.messages = []
    pending = HumanMessage(content="nearest station")

    messages = _build_messages(req, mem, pending)

    assert isinstance(messages[0], SystemMessage)
    assert "53.3498" in messages[0].content
    assert "accuracy" not in messages[0].content
    assert "None" not in messages[0].content
    assert " ." not in messages[0].content


def test_build_messages_instructs_model_to_request_location_when_absent():
    req = ChatRequest(session_id="sess-1", user_id=42, message="nearest station")
    mem = MagicMock()
    mem.messages = []
    pending = HumanMessage(content="nearest station")

    messages = _build_messages(req, mem, pending)

    assert isinstance(messages[0], SystemMessage)
    assert messages[0].content == LOCATION_MISSING_INSTRUCTIONS
    assert "53.3498" not in messages[0].content


def test_get_nearest_station_availability_requires_location():
    req = ChatRequest(session_id="sess-1", user_id=42, message="nearest station")

    result = get_nearest_station_availability(req, _configured_settings())

    assert result["error"] == "location_required"


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
    bound = _mock_tool_bound_llm(mock_llm, AIMessage(content="assistant reply"))

    response = client.post(
        "/chat",
        json={"session_id": "sess-1", "user_id": 42, "message": "hello"},
    )

    assert response.status_code == 200
    assert response.json() == {"chat_id": "sess-1", "reply": "assistant reply"}
    mock_llm.return_value.bind.assert_called_once_with(tools=tools)
    bound.invoke.assert_called_once()
    sent_messages = bound.invoke.call_args[0][0]
    assert isinstance(sent_messages[0], SystemMessage)
    assert isinstance(sent_messages[1], AIMessage)
    assert sent_messages[1].content == ASSISTANT_GREETING
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
def test_chat_sends_location_context_to_llm(
    _mock_runtime, mock_llm, mock_memory, _mock_ensure_session
):
    mem = MagicMock()
    mem.messages = []
    mock_memory.return_value = mem
    bound = _mock_tool_bound_llm(mock_llm, AIMessage(content="assistant reply"))

    response = client.post(
        "/chat",
        json={
            "session_id": "sess-1",
            "user_id": 42,
            "message": "nearest station?",
            "location": {"lat": 53.3498, "lng": -6.2603, "accuracy_m": 25},
        },
    )

    assert response.status_code == 200
    sent_messages = bound.invoke.call_args[0][0]
    assert isinstance(sent_messages[0], SystemMessage)
    assert "53.3498" in sent_messages[0].content
    assert "-6.2603" in sent_messages[0].content
    persisted = [call[0][0] for call in mem.add_message.call_args_list]
    assert not any(isinstance(message, SystemMessage) for message in persisted)


@patch.dict(
    "main.tool_functions",
    {"get_nearest_station_availability": MagicMock(return_value={"stations": [{"number": 2}]})},
)
@patch("main._ensure_session_row")
@patch("main._memory")
@patch("main._llm")
@patch("main._require_runtime", return_value=_configured_settings())
def test_chat_executes_nearest_station_tool_and_sends_observation_to_llm(
    _mock_runtime,
    mock_llm,
    mock_memory,
    _mock_ensure_session,
):
    mem = MagicMock()
    mem.messages = []
    mock_memory.return_value = mem
    bound = _mock_tool_bound_llm(
        mock_llm,
        AIMessage(
            content="",
            tool_calls=[
                {
                    "name": "get_nearest_station_availability",
                    "args": {"limit": 1},
                    "id": "call_1",
                }
            ],
        ),
        AIMessage(content="The nearest station has 8 bikes."),
    )

    response = client.post(
        "/chat",
        json={
            "session_id": "sess-1",
            "user_id": 42,
            "message": "nearest station?",
            "location": {"lat": 53.3498, "lng": -6.2603},
        },
    )

    assert response.status_code == 200
    assert response.json()["reply"] == "The nearest station has 8 bikes."
    assert bound.invoke.call_count == 2
    second_messages = bound.invoke.call_args_list[1][0][0]
    tool_observations = [m for m in second_messages if isinstance(m, ToolMessage)]
    assert len(tool_observations) == 1
    assert tool_observations[0].tool_call_id == "call_1"


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
    bound = mock_llm.return_value.bind.return_value
    bound.invoke.side_effect = RuntimeError("upstream 503")

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
    bound = _mock_streaming_llm(
        mock_llm,
        [AIMessageChunk(content="Hel"), AIMessageChunk(content="lo")],
    )

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
    mock_llm.return_value.bind.assert_called_once_with(tools=tools)
    assert isinstance(bound.stream_messages[0][0], SystemMessage)
    assert isinstance(bound.stream_messages[0][1], AIMessage)
    assert bound.stream_messages[0][1].content == ASSISTANT_GREETING
    assert mem.add_message.call_count == 2
    ai = mem.add_message.call_args_list[1][0][0]
    assert isinstance(ai, AIMessage)
    assert ai.content == "Hello"


@patch("main._ensure_session_row")
@patch("main._memory")
@patch("main._llm")
@patch("main._require_runtime", return_value=_configured_settings())
def test_chat_stream_sends_location_context_to_llm(
    _mock_runtime, mock_llm, mock_memory, _mock_ensure_session
):
    mem = MagicMock()
    mem.messages = []
    mock_memory.return_value = mem
    bound = _mock_streaming_llm(
        mock_llm,
        [AIMessageChunk(content="Nearest station found.")],
    )

    with client.stream(
        "POST",
        "/chat/stream",
        json={
            "session_id": "sess-1",
            "user_id": 42,
            "message": "nearest station?",
            "location": {"lat": 53.3498, "lng": -6.2603, "accuracy_m": 25},
        },
    ) as response:
        assert response.status_code == 200
        list(response.iter_lines())

    first_message = bound.stream_messages[0][0]
    assert isinstance(first_message, SystemMessage)
    assert "53.3498" in first_message.content
    assert "-6.2603" in first_message.content


@patch("main._ensure_session_row")
@patch("main._memory")
@patch("main._llm")
@patch("main._require_runtime", return_value=_configured_settings())
def test_chat_stream_instructs_model_to_request_location_when_absent(
    _mock_runtime, mock_llm, mock_memory, _mock_ensure_session
):
    mem = MagicMock()
    mem.messages = []
    mock_memory.return_value = mem
    bound = _mock_streaming_llm(
        mock_llm,
        [AIMessageChunk(content="Please share your location.")],
    )

    with client.stream(
        "POST",
        "/chat/stream",
        json={"session_id": "sess-1", "user_id": 42, "message": "nearest station?"},
    ) as response:
        assert response.status_code == 200
        list(response.iter_lines())

    first_message = bound.stream_messages[0][0]
    assert isinstance(first_message, SystemMessage)
    assert first_message.content == LOCATION_MISSING_INSTRUCTIONS


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
    bound = mock_llm.return_value.bind.return_value

    async def failing_astream(_messages):
        raise RuntimeError("upstream 429")
        yield AIMessageChunk(content="never")  # pragma: no cover

    bound.astream = failing_astream

    with client_no_raise.stream(
        "POST",
        "/chat/stream",
        json={"session_id": "sess-1", "user_id": 42, "message": "hi"},
    ) as response:
        list(response.iter_lines())

    mem.add_message.assert_not_called()


@patch.dict(
    "main.tool_functions",
    {"get_nearest_station_availability": MagicMock(return_value={"stations": [{"number": 2}]})},
)
@patch("main._ensure_session_row")
@patch("main._memory")
@patch("main._llm")
@patch("main._require_runtime", return_value=_configured_settings())
def test_chat_stream_persists_nearest_station_tool_reply(
    _mock_runtime,
    mock_llm,
    mock_memory,
    _mock_ensure_session,
):
    mem = MagicMock()
    mem.messages = []
    mock_memory.return_value = mem
    _mock_streaming_llm(
        mock_llm,
        [
            AIMessageChunk(
                content="",
                tool_call_chunks=[
                    {
                        "name": "get_nearest_station_availability",
                        "args": "",
                        "id": "call_1",
                        "index": 0,
                    }
                ],
            ),
            AIMessageChunk(
                content="",
                tool_call_chunks=[{"name": None, "args": '{"limit":1}', "id": None, "index": 0}],
            ),
        ],
        [AIMessageChunk(content="Nearest "), AIMessageChunk(content="station has bikes.")],
    )

    with client_no_raise.stream(
        "POST",
        "/chat/stream",
        json={
            "session_id": "sess-1",
            "user_id": 42,
            "message": "nearest station?",
            "location": {"lat": 53.3498, "lng": -6.2603},
        },
    ) as response:
        assert response.status_code == 200
        chunks = [line for line in response.iter_lines() if line.startswith("data: ")]

    assert mem.add_message.call_count == 2
    ai = mem.add_message.call_args_list[1][0][0]
    assert isinstance(ai, AIMessage)
    assert ai.content == "Nearest station has bikes."
    assert any('{"content": "Nearest "}' in c for c in chunks)
