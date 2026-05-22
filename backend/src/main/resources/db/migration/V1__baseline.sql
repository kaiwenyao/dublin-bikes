-- Flyway baseline for existing Alembic-managed schema (flask-app).
-- Production DDL is owned by the legacy Flask/Alembic migrations; Spring Boot must not recreate tables here.
-- Subsequent schema changes use V2__*.sql and later versions.

-- Tables managed by Spring JPA (validate-only):
--   station, availability, user, weather_forecast, sessions
-- Table owned by Python chat-service (LangChain SQLChatMessageHistory, not mapped in Spring):
--   message_store
