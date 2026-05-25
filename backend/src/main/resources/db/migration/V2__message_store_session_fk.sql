-- Adds the missing FK between message_store.session_id and sessions.id.
--
-- Background: V1__baseline.sql created message_store with session_id TEXT and
-- no constraint, because LangChain SQLChatMessageHistory (Python chat-service)
-- creates the table implicitly in legacy deployments. Spring owns the sessions
-- table; without this FK a deleted session leaves orphan messages, and a chat
-- request with a bogus session_id silently writes history with no owner.

-- 1. Clear orphans accumulated before the constraint existed.
DELETE FROM message_store
WHERE session_id IS NULL
   OR session_id NOT IN (SELECT id FROM sessions);

-- 2. Align column type with sessions.id (VARCHAR(64)) so the FK is unambiguous.
ALTER TABLE message_store
    ALTER COLUMN session_id TYPE VARCHAR(64),
    ALTER COLUMN session_id SET NOT NULL;

-- 3. Cascade delete from sessions -> message_store.
ALTER TABLE message_store
    ADD CONSTRAINT fk_message_store_session
        FOREIGN KEY (session_id) REFERENCES sessions (id) ON DELETE CASCADE;

CREATE INDEX ix_message_store_session_id ON message_store (session_id);
