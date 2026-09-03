package com.usora.notification.service;

import com.usora.notification.entity.Notification;
import com.usora.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * F-023: idempotent-insert helper for the notification send path.
 *
 * This is a SEPARATE Spring-managed bean, not two methods on DomainService
 * itself, specifically so its {@code @Transactional(REQUIRES_NEW)}
 * annotations actually take effect: DomainService.sendNotification is
 * itself {@code @Transactional}, and Spring's proxy-based AOP does not
 * apply transaction advice to self-invoked calls (calling
 * {@code this.someMethod()} from within the same class bypasses the
 * proxy entirely) -- injecting this as a real collaborator bean is what
 * makes the REQUIRES_NEW propagation genuinely isolate the insert attempt
 * from the caller's own transaction.
 *
 * Why REQUIRES_NEW matters here specifically: if
 * {@link #insertIfAbsent} ran in the caller's ongoing transaction and hit
 * the unique constraint on (tenant_id, idempotency_key) (see
 * V4__idempotency_key.sql), Spring's default exception translation marks
 * that transaction rollback-only -- any subsequent query in the SAME
 * transaction (the fallback lookup for the existing row) would then fail
 * or never see a consistent view. Running the insert attempt in its own
 * transaction means a constraint violation there only rolls back that
 * isolated transaction, leaving the caller free to run the fallback
 * lookup cleanly afterward.
 */
@Component
@RequiredArgsConstructor
public class NotificationIdempotencyStore {

    private final NotificationRepository notificationRepository;

    /**
     * Attempts to insert {@code candidate}. If a notification with the
     * same (tenantId, idempotencyKey) already exists, returns that
     * existing row instead of inserting a duplicate -- this is race-safe
     * across concurrent callers (unlike a check-then-insert done as two
     * separate steps in application code), because the actual uniqueness
     * guarantee is enforced by the database constraint, not by this
     * method's own control flow.
     *
     * Returns the row that should be treated as canonical for this
     * request: either the newly-inserted one, or the pre-existing one if
     * this was a duplicate/retried request.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InsertResult insertIfAbsent(Notification candidate, String tenantId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // No idempotency key supplied: unchanged, non-idempotent
            // behavior -- every call creates a new row. The partial
            // unique index only applies WHERE idempotency_key IS NOT
            // NULL, so this path can never hit that constraint.
            return new InsertResult(notificationRepository.save(candidate), false);
        }

        try {
            return new InsertResult(notificationRepository.save(candidate), false);
        } catch (DataIntegrityViolationException e) {
            Optional<Notification> existing =
                    notificationRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
            if (existing.isPresent()) {
                return new InsertResult(existing.get(), true);
            }
            // The insert failed on what looked like our own unique
            // constraint, but the row it should have conflicted with
            // isn't found -- something else is wrong (a different
            // constraint violation, or a genuinely bizarre race). Do not
            // swallow this as if it were a normal duplicate; surface the
            // original failure.
            throw e;
        }
    }

    /**
     * @param notification the canonical row for this request (new or pre-existing)
     * @param wasDuplicate true if this was an already-existing row from a prior call with the same
     *                     idempotency key, false if this call actually created it
     */
    public record InsertResult(Notification notification, boolean wasDuplicate) {}
}
