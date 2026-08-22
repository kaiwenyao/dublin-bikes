"""Unit tests for chat-service (mocked DB/LLM; no live credentials)."""

from __future__ import annotations

from datetime import datetime
from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient
from langchain_core.messages import (
    AIMessage,
    AIMessageChunk,
    HumanMessage,
    SystemMessage,
)
from pydantic import ValidationError

from main import (
    ASSISTANT_GREETING,
    LOCATION_MISSING_INSTRUCTIONS,
    TOOL_CALLING_INSTRUCTIONS,
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
    # Settings fields only bind via their env-var validation aliases; field-name
    # kwargs are dropped by extra="ignore". _env_file=None keeps the test
    # hermetic when a local chat-service/.env exists.
    return Settings(
        _env_file=None,
        CHAT_DB_URL="postgresql://user:pass@localhost:5432/chat",
        DEEPSEEK_API_KEY="test-key",
    )


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


@patch("main._db_connection")
def test_get_nearest_station_availability_pushes_ranking_to_sql(mock_db):
    conn = MagicMock()
    cursor = MagicMock()
    mock_db.return_value.__enter__.return_value = conn
    conn.cursor.return_value.__enter__.return_value = cursor
    cursor.fetchall.return_value = [
        (
            2,
            "Near Station",
            "Near Address",
            53.3499,
            -6.2604,
            14,
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

    sql, params = cursor.execute.call_args[0]
    assert params == {"lat": 53.3498, "lng": -6.2603, "limit": 1}
    assert "CROSS JOIN LATERAL" in sql
    assert "ORDER BY distance_m" in sql
    assert "LIMIT %(limit)s" in sql
    assert result["stations"][0]["distance_m"] == 14
    assert result["stations"][0]["number"] == 2


@pytest.mark.parametrize("raw_limit,expected", [(99, 5), (0, 1), (-3, 1)])
@patch("main._db_connection")
def test_get_nearest_station_availability_clamps_limit(mock_db, raw_limit, expected):
    conn = MagicMock()
    cursor = MagicMock()
    mock_db.return_value.__enter__.return_value = conn
    conn.cursor.return_value.__enter__.return_value = cursor
    cursor.fetchall.return_value = []
    req = ChatRequest(
        session_id="sess-1",
        user_id=42,
        message="nearest station",
        location={"lat": 53.3498, "lng": -6.2603},
    )

    get_nearest_station_availability(req, _configured_settings(), limit=raw_limit)

    assert cursor.execute.call_args[0][1]["limit"] == expected


@patch("main.ConnectionPool")
def test_db_pool_is_lazy_and_cached(mock_pool_cls):
    import main

    test_url = "postgresql://user:pass@localhost:5432/chat"
    main._db_pools = {}
    try:
        main._db_pool(test_url)
        main._db_pool(test_url)

        assert mock_pool_cls.call_count == 1
        kwargs = mock_pool_cls.call_args.kwargs
        assert kwargs["conninfo"] == test_url
        assert kwargs["min_size"] == 1
        assert kwargs["max_size"] == 4
        assert kwargs["max_idle"] == 300
        assert kwargs["timeout"] == 10
        assert kwargs["open"] is True
        assert kwargs["kwargs"] == {"application_name": "chat-service"}
    finally:
        main._db_pools = {}


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
    assert TOOL_CALLING_INSTRUCTIONS in messages[0].content
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
    assert TOOL_CALLING_INSTRUCTIONS in messages[0].content
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
    assert LOCATION_MISSING_INSTRUCTIONS in messages[0].content
    assert TOOL_CALLING_INSTRUCTIONS in messages[0].content
    assert "53.3498" not in messages[0].content


def test_get_nearest_station_availability_requires_location():
    req = ChatRequest(session_id="sess-1", user_id=42, message="nearest station")

    result = get_nearest_station_availability(req, _configured_settings())

    assert result["error"] == "location_required"


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
    assert LOCATION_MISSING_INSTRUCTIONS in first_message.content
    assert TOOL_CALLING_INSTRUCTIONS in first_message.content


@patch("main._ensure_session_row")
@patch("main._memory")
@patch("main._llm")
@patch("main._require_runtime", return_value=_configured_settings())
def test_chat_stream_does_not_duplicate_human_message_when_ai_persist_fails(
    _mock_runtime, mock_llm, mock_memory, _mock_ensure_session,
):
    """If the AI-message write fails after the human message was already stored,
    the finally-block recovery must not append the human message a second time.
    Regression guard for duplicated user turns in conversation history.
    """
    mem = MagicMock()
    mem.messages = []
    mock_memory.return_value = mem
    _mock_streaming_llm(mock_llm, [AIMessageChunk(content="Hello")])

    persisted: list = []

    def add(msg):
        # Human-message writes succeed; AI-message write fails (e.g. dropped conn).
        if isinstance(msg, AIMessage):
            raise RuntimeError("db write failed")
        persisted.append(msg)

    mem.add_message.side_effect = add

    with client_no_raise.stream(
        "POST",
        "/chat/stream",
        json={"session_id": "sess-1", "user_id": 42, "message": "hi"},
    ) as response:
        list(response.iter_lines())

    human_count = sum(1 for m in persisted if isinstance(m, HumanMessage))
    assert human_count == 1, (
        f"human message persisted {human_count} times (expected 1)"
    )


@patch("main._ensure_session_row")
@patch("main._memory")
@patch("main._llm")
@patch("main._require_runtime", return_value=_configured_settings())
def test_chat_stream_offloads_memory_io_off_event_loop(
    _mock_runtime, mock_llm, mock_memory, _mock_ensure_best_sess
):
    """SQLChatMessageHistory reads/writes are sync DB I/O; inside the async SSE
    generator they must be routed through run_in_threadpool, otherwise every
    stream blocks the event loop for the whole DB round trip.
    """
    mem = MagicMock()
    mem.messages = []
    mock_memory.return_value = mem
    _mock_streaming_llm(mock_llm, [AIMessageChunk(content="Hello")])

    offloaded: list[object] = []

    async def record_run_in_threadpool(func, *args, **kwargs):
        offloaded.append(func)
        return func(*args, **kwargs)

    with patch("main.run_in_threadpool", side_effect=record_run_in_threadpool):
        with client.stream(
            "POST",
            "/chat/stream",
            json={"session_id": "sess-1", "user_id": 42, "message": "hi"},
        ) as response:
            assert response.status_code == 200
            list(response.iter_lines())

    names = {c.__name__ if hasattr(c, "__name__") else str(c) for c in offloaded}
    assert any("add_message" in n for n in names), (
        f"chat memory writes must be offloaded off the event loop; "
        f"run_in_threadpool received: {sorted(names)}"
    )


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


@patch.dict(
    "main.tool_functions",
    {"get_nearest_station_availability": MagicMock(return_value={"stations": [{"number": 2}]})},
)
@patch("main._ensure_session_row")
@patch("main._memory")
@patch("main._llm")
@patch("main._require_runtime", return_value=_configured_settings())
def test_chat_stream_suppresses_tool_round_content(
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
                content="I will check nearby stations.",
            ),
            AIMessageChunk(
                content="",
                tool_call_chunks=[
                    {
                        "name": "get_nearest_station_availability",
                        "args": '{"limit":1}',
                        "id": "call_1",
                        "index": 0,
                    }
                ],
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

    assert not any("I will check nearby stations." in c for c in chunks)
    assert any('{"content": "Nearest "}' in c for c in chunks)
    assert mem.add_message.call_count == 2
    ai = mem.add_message.call_args_list[1][0][0]
    assert isinstance(ai, AIMessage)
    assert ai.content == "Nearest station has bikes."
