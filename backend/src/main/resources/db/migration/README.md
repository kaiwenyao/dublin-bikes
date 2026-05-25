# Flyway migrations

PostgreSQL schema for the Dublin Bikes backend. Applied automatically on Spring Boot startup when `spring.flyway.enabled=true`.

## Version history

| Version | File | Notes |
|---------|------|--------|
| V1 | `V1__baseline.sql` | Full baseline including `message_store` (LangChain, Python-owned) |
| V2 | `V2__message_store_session_fk.sql` | FK `message_store.session_id` → `sessions.id`, orphan cleanup |

## V2 production checklist (required before deploy)

Migration `V2__message_store_session_fk.sql` is **destructive**:

1. **Session ID length** — if any `message_store.session_id` exceeds 64 characters, the `ALTER COLUMN … VARCHAR(64)` step fails and **rolls back the entire migration** for that deploy:

   ```sql
   SELECT max(length(session_id)) AS max_session_id_len FROM message_store;
   ```

2. **Orphan messages** — rows with `session_id` not present in `sessions` (or NULL) are **deleted** before the FK is added:

   ```sql
   SELECT count(*) AS orphan_count
   FROM message_store
   WHERE session_id IS NULL
      OR session_id NOT IN (SELECT id FROM sessions);
   ```

3. **Manual sign-off** — confirm orphan count and max length with DBA/ops, then run Flyway during a maintenance window.

After V2, deleting a `sessions` row cascades to `message_store` (`ON DELETE CASCADE`).
