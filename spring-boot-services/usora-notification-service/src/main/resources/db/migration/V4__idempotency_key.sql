-- F-023: idempotency support for the notification send path.
--
-- Without this, a client retry after a request timeout (a request that
-- actually succeeded server-side, but whose response the client never
-- saw) creates a second, distinct Notification row and sends a second
-- SMS/email/webhook -- the exact "duplicate side effect on retry"
-- scenario this finding describes. A partial unique index on
-- (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL makes a
-- duplicate insert with the same key fail at the database level rather
-- than silently succeeding twice; the application layer (see
-- DomainService.sendNotification) catches that specific constraint
-- violation and returns the original notification instead of erroring.
--
-- idempotency_key is nullable and NOT required: callers that don't
-- supply one (or don't have a natural retry-safe identifier for their
-- call) get the previous, non-idempotent behavior unchanged -- this is
-- additive, not a breaking change to the existing API contract.
ALTER TABLE notifications ADD COLUMN idempotency_key VARCHAR(255);

CREATE UNIQUE INDEX idx_notifications_tenant_idempotency_key
    ON notifications (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
