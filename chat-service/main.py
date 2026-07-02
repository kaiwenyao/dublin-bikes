"""Dublin Bikes chat-service: LangChain + DeepSeek over FastAPI."""

from __future__ import annotations

import json
import logging
from functools import lru_cache
from typing import Any
from urllib.parse import parse_qsl, urlencode, urlparse, urlunparse

import httpx
import psycopg
from fastapi import FastAPI, HTTPException
from langchain_community.chat_message_histories import SQLChatMessageHistory
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
from langchain_openai import ChatOpenAI
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

# --- 工具描述 ---
tools = [
    {
        "type": "function",
        "function": {
            "name": "get_current_user",
            "description": (
                "Fetch the current session owner's id and email. Call only for direct "
                "requests like 'what is my id?' or 'what is my email?'. For all other "
                "messages, ignore this function."
            ),
            "parameters": {
                "type": "object",
                "properties": {},
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
    internal_tools_base_url: str = Field(
        default="http://localhost:8080/internal/ai/tools",
        validation_alias="INTERNAL_TOOLS_BASE_URL",
    )
    ai_service_token: str | None = Field(default=None, validation_alias="AI_SERVICE_TOKEN")

    @property
    def is_configured(self) -> bool:
        return bool(self.chat_db_url and self.deepseek_api_key)


@lru_cache
def get_settings() -> Settings:
    return Settings()


class ChatRequest(BaseModel):
    # user_id is forwarded by Spring after JWT auth; this service does not verify
    # session ownership — see README "Trust boundaries".
    session_id: str
    user_id: int
    message: str = Field(..., max_length=4000)


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
    """Normalize CHAT_DB_URL for psycopg.connect (libpq), not SQLAlchemy.

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


def _ensure_session_row(session_id: str, user_id: int, settings: Settings) -> None:
    """Upsert sessions row so message_store FK (V2 migration) is satisfied.

    Spring normally owns this table; when testing chat-service directly (e.g. Postman),
    we still need a parent session row before LangChain writes to message_store.
    """
    with psycopg.connect(_psycopg_conninfo(settings.chat_db_url)) as conn:
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


# --- 工具实现 ---
def get_current_user(session_id: str, settings: Settings) -> dict[str, Any]:
    if not settings.ai_service_token:
        raise RuntimeError("AI_SERVICE_TOKEN is not configured")
    url = settings.internal_tools_base_url.rstrip("/") + "/current-user"
    headers = {"Authorization": f"Bearer {settings.ai_service_token}"}
    with httpx.Client(timeout=5.0) as client:
        response = client.get(url, params={"session_id": session_id}, headers=headers)
        response.raise_for_status()
        data = response.json()
    return {"id": data.get("id"), "email": data.get("email")}


tool_functions = {
    "get_current_user": get_current_user,
}


def _log_tool_call(session_id: str, name: str | None, args: dict[str, Any]) -> None:
    print(f"[tool] start session_id={session_id} name={name} args={args}", flush=True)


# --- Agent 主循环 ---
def _agent_reply(req: ChatRequest, settings: Settings, mem: SQLChatMessageHistory) -> tuple[HumanMessage, str]:
    pending = HumanMessage(content=req.message)
    messages = [AIMessage(content=ASSISTANT_GREETING), *list(mem.messages), pending]
    llm_with_tools = _llm(sync=True).bind(tools=tools)

    for _ in range(MAX_TOOL_STEPS):
        ai = llm_with_tools.invoke(messages)
        messages.append(ai)

        tool_calls = getattr(ai, "tool_calls", None) or []
        if not tool_calls:
            reply = ai.content if isinstance(ai.content, str) else json.dumps(ai.content)
            return pending, reply

        for tool_call in tool_calls:
            name = tool_call.get("name")
            args = tool_call.get("args") or {}
            tool_call_id = tool_call.get("id") or name or "tool_call"
            _log_tool_call(req.session_id, name, args)
            try:
                result = tool_functions[name](req.session_id, settings, **args)
                print(f"[tool] done session_id={req.session_id} name={name}", flush=True)
            except Exception as exc:
                print(f"[tool] failed session_id={req.session_id} name={name}: {exc}", flush=True)
                logger.warning("tool %s failed: %s", name, exc)
                result = {"error": str(exc)}
            messages.append(
                ToolMessage(
                    content=json.dumps(result),
                    tool_call_id=tool_call_id,
                    name=name or "unknown",
                )
            )

    return pending, "I could not complete the tool workflow within the allowed number of steps."


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
            messages = [AIMessage(content=ASSISTANT_GREETING), *list(mem.messages), pending]
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
                    name = tool_call.get("name")
                    args = tool_call.get("args") or {}
                    tool_call_id = tool_call.get("id") or name or "tool_call"
                    _log_tool_call(req.session_id, name, args)
                    try:
                        result = tool_functions[name](req.session_id, settings, **args)
                        print(f"[tool] done session_id={req.session_id} name={name}", flush=True)
                    except Exception as exc:
                        print(f"[tool] failed session_id={req.session_id} name={name}: {exc}", flush=True)
                        logger.warning("tool %s failed: %s", name, exc)
                        result = {"error": str(exc)}
                    messages.append(
                        ToolMessage(
                            content=json.dumps(result),
                            tool_call_id=tool_call_id,
                            name=name or "unknown",
                        )
                    )

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
