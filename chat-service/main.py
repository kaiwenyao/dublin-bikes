"""Dublin Bikes chat-service: LangChain + DeepSeek over FastAPI."""

from __future__ import annotations

import json
import logging
import threading
from contextlib import AbstractContextManager, asynccontextmanager
from functools import lru_cache
from typing import Any
from urllib.parse import parse_qsl, urlencode, urlparse, urlunparse

from fastapi import FastAPI, HTTPException
from langchain_community.chat_message_histories import SQLChatMessageHistory
from langchain_core.messages import (
    AIMessage,
    BaseMessage,
    HumanMessage,
    SystemMessage,
    ToolMessage,
)
from langchain_openai import ChatOpenAI
from psycopg import Connection
from psycopg_pool import ConnectionPool
from pydantic import BaseModel, Field
from pydantic_settings import BaseSettings, SettingsConfigDict
from sqlalchemy import create_engine
from sqlalchemy.engine import Engine
from sse_starlette.sse import EventSourceResponse
from starlette.concurrency import run_in_threadpool

logger = logging.getLogger(__name__)

ROLE_MAP = {"human": "user", "ai": "assistant"}
MAX_TOOL_STEPS = 4
DB_POOL_MIN_SIZE = 1
DB_POOL_MAX_SIZE = 4
DB_POOL_MAX_IDLE_S = 300  # recycle idle conns before NAT/Supabase reaping
DB_POOL_CHECKOUT_TIMEOUT_S = 10  # fail a request fast instead of freezing 30s
# SQLAlchemy pool used by the LangChain message-history Engine. Keep the total
# (pool_size + max_overflow) small: one Engine is shared by every request, so
# this is a hard ceiling on chat-service history connections.
SQLALCHEMY_POOL_SIZE = 2
SQLALCHEMY_MAX_OVERFLOW = 3
ASSISTANT_GREETING = (
    "Hi! I'm your Dublin Bikes assistant. Ask me about bike sharing, stations, "
    "or sustainable mobility—or just say hello."
)
TOOL_CALLING_INSTRUCTIONS = (
    "Tool calling protocol: when you need to call a tool, do not emit "
    "user-facing content in that assistant turn; return only tool_calls. "
    "When you emit user-facing content, do not call tools in that same "
    "assistant turn. After receiving enough information from tools, answer "
    "the user with content and no tool_calls."
)
LOCATION_ATTACHED_INSTRUCTIONS = (
    "The user's current location is attached to this request: "
    "lat={lat}, lng={lng}{accuracy}. For questions about nearby or "
    "nearest stations, call get_nearest_station_availability; it already "
    "uses this attached location, so never say you cannot access the "
    "user's location."
)
LOCATION_MISSING_INSTRUCTIONS = (
    "No user location is attached to this request. If the user asks about "
    "nearby or nearest stations, ask them to enable location sharing in "
    "the app instead of guessing."
)

tools = [
    {
        "type": "function",
        "function": {
            "name": "get_nearest_station_availability",
            "description": (
                "Find the nearest Dublin Bikes station availability using the current "
                "request location. Use for nearby or closest station questions."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "limit": {
                        "type": "integer",
                        "description": "Number of nearest stations to return.",
                        "minimum": 1,
                        "maximum": 5,
                    }
                },
                "required": [],
                "additionalProperties": False,
            },
        },
    }
]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    chat_db_url: str | None = Field(default=None, validation_alias="CHAT_DB_URL")
    deepseek_api_key: str | None = Field(default=None, validation_alias="DEEPSEEK_API_KEY")
    deepseek_base_url: str = Field(
        default="https://api.deepseek.com",
        validation_alias="DEEPSEEK_BASE_URL",
    )
    deepseek_model: str = Field(default="deepseek-chat", validation_alias="DEEPSEEK_MODEL")
    # Upper bound on the conversation history put into each prompt. Tool-call pairs
    # are kept intact by _window_history.
    chat_history_max_messages: int = Field(
        default=20,
        validation_alias="CHAT_HISTORY_MAX_MESSAGES",
    )

    @property
    def is_configured(self) -> bool:
        return bool(self.chat_db_url and self.deepseek_api_key)


@lru_cache
def get_settings() -> Settings:
    return Settings()


class GeoLocation(BaseModel):
    lat: float = Field(..., ge=-90, le=90)
    lng: float = Field(..., ge=-180, le=180)
    accuracy_m: float | None = Field(default=None, ge=0)


class ChatRequest(BaseModel):
    # user_id is forwarded by Spring after JWT auth; this service does not verify
    # session ownership — see README "Trust boundaries".
    session_id: str
    user_id: int
    message: str = Field(..., max_length=4000)
    location: GeoLocation | None = None


class TitleRequest(BaseModel):
    message: str


class TitleReply(BaseModel):
    title: str


class HealthReply(BaseModel):
    status: str
    configured: bool


class MessageItem(BaseModel):
    role: str
    content: str


def _require_runtime() -> Settings:
    settings = get_settings()
    if not settings.is_configured:
        raise HTTPException(
            status_code=503,
            detail="CHAT_DB_URL and DEEPSEEK_API_KEY must be set",
        )
    return settings


def _repair_sslmode(parsed) -> list[tuple[str, str]]:
    """Return the URL query with a bare ``?sslmode`` flag repaired to ``require``."""
    fixed_query: list[tuple[str, str]] = []
    for key, value in parse_qsl(parsed.query, keep_blank_values=True):
        if key == "sslmode" and value == "":
            fixed_query.append((key, "require"))
        else:
            fixed_query.append((key, value))
    return fixed_query


def _psycopg_conninfo(db_url: str) -> str:
    """Normalize CHAT_DB_URL for the libpq connection pool, not SQLAlchemy.

    LangChain accepts postgresql+psycopg://…; psycopg rejects the +driver suffix.
    Also repairs a bare ?sslmode query flag (no value), which Supabase URLs sometimes have.
    """
    parsed = urlparse(db_url)
    scheme = parsed.scheme.split("+", 1)[0]  # postgresql+psycopg -> postgresql
    return urlunparse(parsed._replace(scheme=scheme, query=urlencode(_repair_sslmode(parsed))))


def _sqlalchemy_url(db_url: str) -> str:
    """SQLAlchemy flavour of CHAT_DB_URL: keep the driver suffix, repair sslmode.

    ``SQLChatMessageHistory`` builds a SQLAlchemy Engine from this string, so unlike the libpq
    conninfo above the ``+psycopg`` driver suffix must survive.
    """
    parsed = urlparse(db_url)
    return urlunparse(parsed._replace(query=urlencode(_repair_sslmode(parsed))))


_db_pool_lock = threading.Lock()
_db_pools: dict[str, ConnectionPool] = {}
# Set once the lifespan is disposing resources. Cancelling a run_in_threadpool() wait does not
# stop the worker thread (asyncio delivers CancelledError to the awaiting task immediately), so a
# warmup thread can still be inside resource creation when cleanup runs. Refusing creation after
# this flag is set, under the same lock cleanup takes, guarantees nothing is registered after the
# registries were cleared.
_db_shutting_down = False


def _db_pool(db_url: str) -> ConnectionPool:
    """Per-URL connection pool; double-checked lock because concurrent cold-start
    threads must not each construct (and leak) a ConnectionPool for the same URL."""
    conninfo = _psycopg_conninfo(db_url)
    pool = _db_pools.get(conninfo)
    if pool is None:
        with _db_pool_lock:
            pool = _db_pools.get(conninfo)
            if pool is None:
                if _db_shutting_down:
                    raise RuntimeError("chat-service is shutting down")
                pool = ConnectionPool(
                    conninfo=conninfo,
                    min_size=DB_POOL_MIN_SIZE,
                    max_size=DB_POOL_MAX_SIZE,
                    max_idle=DB_POOL_MAX_IDLE_S,
                    timeout=DB_POOL_CHECKOUT_TIMEOUT_S,
                    check=ConnectionPool.check_connection,
                    kwargs={"application_name": "chat-service"},
                    open=True,
                )
                _db_pools[conninfo] = pool
    return pool


def _db_connection(db_url: str) -> AbstractContextManager[Connection[Any]]:
    """Check out a pooled connection; single seam shared by all DB call sites."""
    return _db_pool(db_url).connection()


_sqlalchemy_engine_lock = threading.Lock()
_sqlalchemy_engines: dict[str, Engine] = {}


def _sqlalchemy_engine(db_url: str) -> Engine:
    """One SQLAlchemy Engine (own pool) per database URL, shared by every request.

    ``SQLChatMessageHistory(connection=<str>)`` calls ``create_engine(url)`` itself, so building
    a history object per request created a brand-new pool — and a fresh TLS handshake to
    PostgreSQL — for every chat message, then left that pool behind. Handing it this Engine
    instead keeps the connection pool bounded and the connections keep-alive.
    """
    url = _sqlalchemy_url(db_url)
    engine = _sqlalchemy_engines.get(url)
    if engine is None:
        with _sqlalchemy_engine_lock:
            engine = _sqlalchemy_engines.get(url)
            if engine is None:
                if _db_shutting_down:
                    raise RuntimeError("chat-service is shutting down")
                engine = create_engine(
                    url,
                    pool_size=SQLALCHEMY_POOL_SIZE,
                    max_overflow=SQLALCHEMY_MAX_OVERFLOW,
                    pool_recycle=DB_POOL_MAX_IDLE_S,
                    pool_pre_ping=True,
                    pool_timeout=DB_POOL_CHECKOUT_TIMEOUT_S,
                )
                _sqlalchemy_engines[url] = engine
    return engine


def _warmup_databases(settings: Settings) -> None:
    """Open the pools once so the first user request does not pay the connect cost.

    If the lifespan is cancelled mid-warmup the worker thread keeps running (or is resumed after
    cleanup already disposed everything); the shutdown guard then raises here instead of leaking
    a pool created after ``_dispose_databases()`` cleared the registries.
    """
    try:
        with _db_connection(settings.chat_db_url) as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT 1")
        with _sqlalchemy_engine(settings.chat_db_url).connect():
            pass
    except RuntimeError:
        if not _db_shutting_down:
            raise
        logger.info("database warmup aborted: resources were disposed while it was running")


def _reset_database_lifecycle() -> None:
    """Re-arm resource creation for a new lifespan (the app can be restarted in-process)."""
    global _db_shutting_down
    with _sqlalchemy_engine_lock, _db_pool_lock:
        _db_shutting_down = False


def _dispose_databases() -> None:
    """Dispose the SQLAlchemy engines and close the psycopg pools on shutdown.

    Takes the creation locks so a still-running warmup thread cannot register a pool after the
    registries are cleared (it either finishes first and is disposed here, or is refused by the
    shutdown guard).
    """
    global _db_shutting_down
    with _sqlalchemy_engine_lock, _db_pool_lock:
        _db_shutting_down = True
        for engine in list(_sqlalchemy_engines.values()):
            engine.dispose()
        _sqlalchemy_engines.clear()
        for pool in list(_db_pools.values()):
            pool.close()
        _db_pools.clear()


@asynccontextmanager
async def _lifespan(_app: FastAPI):
    settings = get_settings()
    _reset_database_lifecycle()
    try:
        if settings.is_configured:
            try:
                await run_in_threadpool(_warmup_databases, settings)
            except Exception:
                # Warmup is best-effort; a real cancellation (BaseException) is not caught
                # here and still reaches the finally below.
                logger.warning("chat-service database warmup failed", exc_info=True)
        yield
    finally:
        # Runs on normal shutdown, on an exception raised into the context, and on
        # cancellation (including a cancel during warmup). Deliberately synchronous:
        # awaiting inside a cancelled task raises CancelledError again before the pools are
        # closed, and dispose()/close() are fast shutdown-only calls.
        try:
            _dispose_databases()
        except Exception:
            logger.exception("failed to dispose chat-service database resources")


app = FastAPI(
    title="dublin-bikes-chat-service",
    version="1.0.0",
    lifespan=_lifespan,
)


def _ensure_session_row(session_id: str, user_id: int, settings: Settings) -> None:
    """Upsert sessions row so message_store FK (V2 migration) is satisfied.

    Spring normally owns this table; when testing chat-service directly (e.g. Postman),
    we still need a parent session row before LangChain writes to message_store.
    """
    with _db_connection(settings.chat_db_url) as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO sessions (id, user_id, title, created_at, updated_at)
                VALUES (%s, %s, NULL, NOW() AT TIME ZONE 'UTC', NOW() AT TIME ZONE 'UTC')
                ON CONFLICT (id) DO UPDATE
                    SET updated_at = NOW() AT TIME ZONE 'UTC'
                """,
                (session_id, user_id),
            )
        # No explicit commit: pool.connection() commits on clean context exit.


def _memory(session_id: str, settings: Settings) -> SQLChatMessageHistory:
    # Shared Engine: passing the URL string here would create (and leak) an Engine + pool
    # per request. See _sqlalchemy_engine.
    return SQLChatMessageHistory(
        session_id=session_id,
        connection=_sqlalchemy_engine(settings.chat_db_url),
    )


def _history_snapshot(mem: SQLChatMessageHistory) -> list[BaseMessage]:
    """Synchronous history read (SQLChatMessageHistory uses a sync SQLAlchemy
    engine); must only be called from a worker thread, not the event loop."""
    return list(mem.messages)


async def _load_history(mem: SQLChatMessageHistory) -> list[BaseMessage]:
    """Fetch conversation history without blocking the asyncio event loop."""
    return await run_in_threadpool(_history_snapshot, mem)


async def _add_message(mem: SQLChatMessageHistory, message: BaseMessage) -> None:
    """Persist one history message without blocking the asyncio event loop."""
    await run_in_threadpool(mem.add_message, message)


@lru_cache  # API key rotation requires container restart (cached client holds old key).
def _llm(streaming: bool) -> ChatOpenAI:
    settings = _require_runtime()
    return ChatOpenAI(
        model=settings.deepseek_model,
        api_key=settings.deepseek_api_key,
        base_url=settings.deepseek_base_url,
        streaming=streaming,
    )


def _map_messages(messages: list[Any]) -> list[MessageItem]:
    items: list[MessageItem] = []
    for message in messages:
        role = ROLE_MAP.get(getattr(message, "type", ""), getattr(message, "type", "unknown"))
        content = getattr(message, "content", "")
        if isinstance(content, list):
            content = json.dumps(content)
        items.append(MessageItem(role=role, content=str(content)))
    return items


def get_nearest_station_availability(
    req: ChatRequest,
    settings: Settings,
    limit: int = 3,
) -> dict[str, Any]:
    if req.location is None:
        return {
            "error": "location_required",
            "message": "Ask the user to share their current location before answering.",
        }

    try:
        normalized_limit = max(1, min(int(limit), 5))
    except (TypeError, ValueError):
        normalized_limit = 3

    query = """
        SELECT
            s.number,
            s.name,
            s.address,
            s.latitude,
            s.longitude,
            -- Haversine formula; 6371000 = Earth mean radius in metres.
            ROUND(
                2 * 6371000 * ASIN(
                    SQRT(
                        LEAST(
                            1.0,
                            POWER(SIN(RADIANS(s.latitude - %(lat)s) / 2), 2)
                            + COS(RADIANS(%(lat)s)) * COS(RADIANS(s.latitude))
                              * POWER(SIN(RADIANS(s.longitude - %(lng)s) / 2), 2)
                        )
                    )
                )
            )::int AS distance_m,
            s.bike_stands,
            latest.available_bikes,
            latest.available_bike_stands,
            latest.status,
            latest."timestamp",
            latest.requested_at
        FROM station s
        CROSS JOIN LATERAL (
            SELECT
                a.available_bikes,
                a.available_bike_stands,
                a.status,
                a."timestamp",
                a.requested_at
            FROM availability a
            WHERE a.number = s.number
            ORDER BY a."timestamp" DESC, a.id DESC
            LIMIT 1
        ) latest
        ORDER BY distance_m, s.number
        LIMIT %(limit)s
    """

    stations: list[dict[str, Any]] = []
    with _db_connection(settings.chat_db_url) as conn:
        with conn.cursor() as cur:
            cur.execute(
                query,
                {"lat": req.location.lat, "lng": req.location.lng, "limit": normalized_limit},
            )
            rows = cur.fetchall()

    for row in rows:
        (
            number,
            name,
            address,
            latitude,
            longitude,
            distance_m,
            bike_stands,
            available_bikes,
            available_bike_stands,
            status,
            timestamp,
            requested_at,
        ) = row
        stations.append(
            {
                "number": number,
                "name": name,
                "address": address,
                "latitude": float(latitude),
                "longitude": float(longitude),
                "distance_m": distance_m,
                "bike_stands": bike_stands,
                "available_bikes": available_bikes,
                "available_bike_stands": available_bike_stands,
                "status": status,
                "timestamp": timestamp.isoformat() if timestamp else None,
                "requested_at": requested_at.isoformat() if requested_at else None,
            }
        )

    if not stations:
        return {"error": "station_availability_unavailable", "stations": []}

    return {
        "location": {
            "lat": req.location.lat,
            "lng": req.location.lng,
            "accuracy_m": req.location.accuracy_m,
        },
        "stations": stations,
    }


tool_functions = {
    "get_nearest_station_availability": get_nearest_station_availability,
}


def _tool_message(tool_call: dict[str, Any], req: ChatRequest, settings: Settings) -> ToolMessage:
    name = tool_call.get("name")
    args = tool_call.get("args") or {}
    tool_call_id = tool_call.get("id") or name or "tool_call"
    try:
        result = tool_functions[name](req, settings, **args)
        print(f"[tool] done session_id={req.session_id} name={name}", flush=True)
    except Exception as exc:
        print(f"[tool] failed session_id={req.session_id} name={name}: {exc}", flush=True)
        logger.warning("tool %s failed: %s", name, exc)
        result = {"error": str(exc)}
    return ToolMessage(
        content=json.dumps(result),
        tool_call_id=tool_call_id,
        name=name or "unknown",
    )


def _location_system_message(location: GeoLocation | None) -> SystemMessage:
    if location is None:
        return SystemMessage(content=f"{TOOL_CALLING_INSTRUCTIONS}\n\n{LOCATION_MISSING_INSTRUCTIONS}")
    accuracy = (
        f" (accuracy ~{location.accuracy_m:.0f}m)"
        if location.accuracy_m is not None
        else ""
    )
    location_instructions = LOCATION_ATTACHED_INSTRUCTIONS.format(
        lat=f"{location.lat:.5f}", lng=f"{location.lng:.5f}", accuracy=accuracy
    )
    return SystemMessage(
        content=f"{TOOL_CALLING_INSTRUCTIONS}\n\n{location_instructions}"
    )


def _window_history(history: list[BaseMessage], max_messages: int) -> list[BaseMessage]:
    """Keep only the most recent messages, without splitting a tool-call round.

    An ``AIMessage`` carrying ``tool_calls`` is always followed by its ``ToolMessage`` replies;
    starting the window in the middle of such a round sends the API an orphaned ToolMessage,
    which it rejects. So the start is moved back to the closest ``HumanMessage`` boundary
    (every tool round belongs to the human turn that triggered it). If no HumanMessage is
    found the full history is kept rather than producing an invalid prompt.
    """
    if max_messages <= 0 or len(history) <= max_messages:
        return history
    start = len(history) - max_messages
    while start > 0 and not isinstance(history[start], HumanMessage):
        start -= 1
    return history[start:]


def _build_messages(
    req: ChatRequest,
    mem: SQLChatMessageHistory,
    pending: HumanMessage,
    history: list[BaseMessage] | None = None,
    max_history_messages: int | None = None,
) -> list[BaseMessage]:
    """Assemble the per-call prompt for the LLM.

    The leading SystemMessage carries request-scoped location context and must
    never be persisted to mem — history writes stay limited to the pending
    HumanMessage and the final AIMessage at the call sites.

    Pass ``history`` (pre-fetched off the event loop) to avoid touching the
    sync SQLAlchemy engine from the event loop; defaults to ``mem.messages``
    for direct callers.

    ``max_history_messages`` bounds the prompt (a long conversation otherwise grows
    linearly in tokens and time-to-first-token on every turn); it defaults to
    ``CHAT_HISTORY_MAX_MESSAGES`` / 20.
    """
    if history is None:
        history = _history_snapshot(mem)
    if max_history_messages is None:
        max_history_messages = get_settings().chat_history_max_messages
    return [
        _location_system_message(req.location),
        AIMessage(content=ASSISTANT_GREETING),
        *_window_history(history, max_history_messages),
        pending,
    ]


def _record_assistant_turn(messages: list[BaseMessage], assistant_message: Any) -> list[dict[str, Any]]:
    tool_calls = getattr(assistant_message, "tool_calls", None) or []
    messages.append(
        AIMessage(
            content=getattr(assistant_message, "content", ""),
            tool_calls=tool_calls,
        )
    )
    return tool_calls


async def _append_tool_messages(
    messages: list[BaseMessage],
    tool_calls: list[dict[str, Any]],
    req: ChatRequest,
    settings: Settings,
) -> bool:
    for tool_call in tool_calls:
        messages.append(await run_in_threadpool(_tool_message, tool_call, req, settings))
    return bool(tool_calls)


@app.get("/health", response_model=HealthReply)
def health() -> HealthReply:
    settings = get_settings()
    return HealthReply(status="ok", configured=settings.is_configured)


@app.post("/chat/stream")
async def chat_stream(req: ChatRequest) -> EventSourceResponse:
    settings = _require_runtime()
    await run_in_threadpool(_ensure_session_row, req.session_id, req.user_id, settings)
    mem = await run_in_threadpool(_memory, req.session_id, settings)
    pending = HumanMessage(content=req.message)

    async def event_generator():
        chunks: list[str] = []
        persisted = False
        human_persisted = False
        try:
            history = await _load_history(mem)
            messages = _build_messages(
                req,
                mem,
                pending,
                history,
                settings.chat_history_max_messages,
            )
            llm_with_tools = _llm(streaming=True).bind(tools=tools)
            has_tool_observation = False

            for _ in range(MAX_TOOL_STEPS):
                assistant_message = None
                buffer_round_output = req.location is not None and not has_tool_observation
                round_pieces: list[str] = []
                async for chunk in llm_with_tools.astream(messages):
                    assistant_message = chunk if assistant_message is None else assistant_message + chunk
                    piece = chunk.content if isinstance(chunk.content, str) else json.dumps(chunk.content)
                    if piece and buffer_round_output:
                        round_pieces.append(piece)
                    elif piece:
                        chunks.append(piece)
                        yield {"data": json.dumps({"content": piece})}

                if assistant_message is None:
                    break

                tool_calls = _record_assistant_turn(messages, assistant_message)
                if not tool_calls:
                    for piece in round_pieces:
                        chunks.append(piece)
                        yield {"data": json.dumps({"content": piece})}
                    break
                has_tool_observation = (
                    await _append_tool_messages(messages, tool_calls, req, settings)
                    or has_tool_observation
                )

            await _add_message(mem, pending)
            human_persisted = True
            await _add_message(mem, AIMessage(content="".join(chunks)))
            persisted = True
            yield {"data": "[DONE]"}
        except Exception:
            logger.exception("chat stream failed for session_id=%s", req.session_id)
            raise
        finally:
            if chunks and not persisted:
                try:
                    # The human message may already be stored; re-adding it would
                    # duplicate the user's turn in history (and the LLM context).
                    if not human_persisted:
                        await _add_message(mem, pending)
                    await _add_message(mem, AIMessage(content="".join(chunks)))
                except Exception:
                    logger.exception(
                        "failed to persist partial stream for session_id=%s",
                        req.session_id,
                    )

    return EventSourceResponse(event_generator())


@app.post("/chat/title", response_model=TitleReply)
def chat_title(req: TitleRequest) -> TitleReply:
    _require_runtime()
    snippet = req.message[:200]
    prompt = (
        "Summarize the topic of this sentence in 6 words or less, "
        "output only the title without punctuation: "
        + snippet
    )
    out = _llm(streaming=False).invoke([HumanMessage(content=prompt)])
    title = (out.content or "").strip()[:50]
    return TitleReply(title=title)


@app.get("/sessions/{session_id}/messages", response_model=list[MessageItem])
def session_messages(session_id: str) -> list[MessageItem]:
    settings = _require_runtime()
    return _map_messages(_memory(session_id, settings).messages)
