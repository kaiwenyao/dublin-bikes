"""Dublin Bikes chat-service: LangChain + DeepSeek over FastAPI."""

from __future__ import annotations

import json
import logging
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
from psycopg_pool import ConnectionPool
from pydantic import BaseModel, Field
from pydantic_settings import BaseSettings, SettingsConfigDict
from sse_starlette.sse import EventSourceResponse

logger = logging.getLogger(__name__)

ROLE_MAP = {"human": "user", "ai": "assistant"}
MAX_TOOL_STEPS = 4
ASSISTANT_GREETING = (
    "Hi! I'm your UCDSE assistant. Ask me about bike sharing, stations, "
    "or sustainable mobility—or just say hello."
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


class ChatReply(BaseModel):
    chat_id: str
    reply: str


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


app = FastAPI(title="dublin-bikes-chat-service", version="1.0.0")


def _require_runtime() -> Settings:
    settings = get_settings()
    if not settings.is_configured:
        raise HTTPException(
            status_code=503,
            detail="CHAT_DB_URL and DEEPSEEK_API_KEY must be set",
        )
    return settings


def _psycopg_conninfo(db_url: str) -> str:
    """Normalize CHAT_DB_URL for the libpq connection pool, not SQLAlchemy.

    LangChain accepts postgresql+psycopg://…; psycopg rejects the +driver suffix.
    Also repairs a bare ?sslmode query flag (no value), which Supabase URLs sometimes have.
    """
    parsed = urlparse(db_url)
    scheme = parsed.scheme.split("+", 1)[0]  # postgresql+psycopg -> postgresql
    query = parse_qsl(parsed.query, keep_blank_values=True)
    fixed_query: list[tuple[str, str]] = []
    for key, value in query:
        if key == "sslmode" and value == "":
            fixed_query.append((key, "require"))
        else:
            fixed_query.append((key, value))
    return urlunparse(parsed._replace(scheme=scheme, query=urlencode(fixed_query)))


@lru_cache  # DB URL rotation requires container restart (cached pool holds old conninfo).
def _db_pool() -> ConnectionPool:
    settings = _require_runtime()
    return ConnectionPool(
        conninfo=_psycopg_conninfo(settings.chat_db_url),
        min_size=1,
        max_size=4,
        max_idle=300,
        open=True,
    )


def _db_connection():
    """Check out a pooled connection; single seam shared by all DB call sites."""
    return _db_pool().connection()


def _ensure_session_row(session_id: str, user_id: int, settings: Settings) -> None:
    """Upsert sessions row so message_store FK (V2 migration) is satisfied.

    Spring normally owns this table; when testing chat-service directly (e.g. Postman),
    we still need a parent session row before LangChain writes to message_store.
    """
    with _db_connection() as conn:
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
        conn.commit()


def _memory(session_id: str, settings: Settings) -> SQLChatMessageHistory:
    return SQLChatMessageHistory(
        session_id=session_id,
        connection=settings.chat_db_url,
    )


@lru_cache  # API key rotation requires container restart (cached client holds old key).
def _llm(sync: bool = True) -> ChatOpenAI:
    settings = _require_runtime()
    return ChatOpenAI(
        model=settings.deepseek_model,
        api_key=settings.deepseek_api_key,
        base_url=settings.deepseek_base_url,
        streaming=not sync,
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
    with _db_connection() as conn:
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
        return SystemMessage(content=LOCATION_MISSING_INSTRUCTIONS)
    accuracy = (
        f" (accuracy ~{location.accuracy_m:.0f}m)"
        if location.accuracy_m is not None
        else ""
    )
    return SystemMessage(
        content=LOCATION_ATTACHED_INSTRUCTIONS.format(
            lat=f"{location.lat:.5f}", lng=f"{location.lng:.5f}", accuracy=accuracy
        )
    )


def _build_messages(
    req: ChatRequest, mem: SQLChatMessageHistory, pending: HumanMessage
) -> list[BaseMessage]:
    """Assemble the per-call prompt for the LLM.

    The leading SystemMessage carries request-scoped location context and must
    never be persisted to mem — history writes stay limited to the pending
    HumanMessage and the final AIMessage at the call sites.
    """
    return [
        _location_system_message(req.location),
        AIMessage(content=ASSISTANT_GREETING),
        *list(mem.messages),
        pending,
    ]


def _agent_reply(req: ChatRequest, settings: Settings, mem: SQLChatMessageHistory) -> tuple[HumanMessage, str]:
    pending = HumanMessage(content=req.message)
    messages = _build_messages(req, mem, pending)
    llm_with_tools = _llm(sync=True).bind(tools=tools)

    for _ in range(MAX_TOOL_STEPS):
        ai = llm_with_tools.invoke(messages)
        messages.append(ai)
        tool_calls = getattr(ai, "tool_calls", None) or []
        if not tool_calls:
            reply = ai.content if isinstance(ai.content, str) else json.dumps(ai.content)
            return pending, reply
        for tool_call in tool_calls:
            messages.append(_tool_message(tool_call, req, settings))

    return pending, "I could not complete the station lookup within the allowed number of steps."


@app.get("/health", response_model=HealthReply)
def health() -> HealthReply:
    settings = get_settings()
    return HealthReply(status="ok", configured=settings.is_configured)


@app.post("/chat", response_model=ChatReply)
def chat(req: ChatRequest) -> ChatReply:
    settings = _require_runtime()
    _ensure_session_row(req.session_id, req.user_id, settings)
    mem = _memory(req.session_id, settings)
    try:
        pending, reply = _agent_reply(req, settings, mem)
    except Exception:
        logger.exception("chat failed for session_id=%s", req.session_id)
        raise
    mem.add_message(pending)
    mem.add_message(AIMessage(content=reply))
    return ChatReply(chat_id=req.session_id, reply=reply)


@app.post("/chat/stream")
async def chat_stream(req: ChatRequest) -> EventSourceResponse:
    settings = _require_runtime()
    _ensure_session_row(req.session_id, req.user_id, settings)
    mem = _memory(req.session_id, settings)
    pending = HumanMessage(content=req.message)

    async def event_generator():
        chunks: list[str] = []
        persisted = False
        try:
            messages = _build_messages(req, mem, pending)
            llm_with_tools = _llm(sync=False).bind(tools=tools)

            for _ in range(MAX_TOOL_STEPS):
                assistant_message = None
                async for chunk in llm_with_tools.astream(messages):
                    assistant_message = chunk if assistant_message is None else assistant_message + chunk
                    piece = chunk.content if isinstance(chunk.content, str) else json.dumps(chunk.content)
                    if piece:
                        chunks.append(piece)
                        yield {"data": json.dumps({"content": piece})}

                if assistant_message is None:
                    break

                messages.append(
                    AIMessage(
                        content=assistant_message.content,
                        tool_calls=assistant_message.tool_calls,
                    )
                )
                tool_calls = getattr(assistant_message, "tool_calls", None) or []
                if not tool_calls:
                    break
                for tool_call in tool_calls:
                    messages.append(_tool_message(tool_call, req, settings))

            mem.add_message(pending)
            mem.add_message(AIMessage(content="".join(chunks)))
            persisted = True
            yield {"data": "[DONE]"}
        except Exception:
            logger.exception("chat stream failed for session_id=%s", req.session_id)
            raise
        finally:
            if chunks and not persisted:
                try:
                    mem.add_message(pending)
                    mem.add_message(AIMessage(content="".join(chunks)))
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
    out = _llm(sync=True).invoke([HumanMessage(content=prompt)])
    title = (out.content or "").strip()[:50]
    return TitleReply(title=title)


@app.get("/sessions/{session_id}/messages", response_model=list[MessageItem])
def session_messages(session_id: str) -> list[MessageItem]:
    settings = _require_runtime()
    return _map_messages(_memory(session_id, settings).messages)
