-- Aligns message_store.message column type with what LangChain
-- SQLChatMessageHistory (Python chat-service) actually writes.
--
-- Background: V1__baseline.sql created message_store.message as JSONB, but
-- LangChain's default SQLChatMessageHistory serializes BaseMessage with
-- json.dumps(...) and binds the parameter as VARCHAR. PostgreSQL refuses the
-- implicit varchar -> jsonb cast, so every POST /chat (and /chat/stream)
-- failed with:
--
--   psycopg.errors.DatatypeMismatch:
--     column "message" is of type jsonb but expression is of type character varying
--
-- LangChain stores JSON-as-text on read/write and parses with json.loads on
-- read; Spring never queries this column (no JPA entity). TEXT is the
-- LangChain-default column type and matches both ends without a custom
-- BaseMessageConverter.
--
-- This migration is non-destructive: USING message::text preserves existing
-- payloads byte-for-byte (PG renders JSONB to canonical text).

ALTER TABLE message_store
    ALTER COLUMN message TYPE TEXT USING message::text;
