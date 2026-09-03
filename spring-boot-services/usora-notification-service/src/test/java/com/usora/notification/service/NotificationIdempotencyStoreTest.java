package com.usora.notification.service;

import com.usora.notification.entity.Notification;
import com.usora.notification.entity.Notification.NotificationChannel;
import com.usora.notification.entity.Notification.NotificationPriority;
import com.usora.notification.entity.Notification.NotificationStatus;
import com.usora.notification.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-023 regression tests: without idempotency handling, a client retry
 * after a timed-out (but actually-succeeded) send request created a
 * second, distinct Notification row and triggered a second SMS/email
 * send -- the "duplicate side effect on retry" scenario this finding
 * describes. These tests verify insertIfAbsent's actual duplicate-
 * detection behavior in isolation from the full sendNotification/
 * delivery path.
 */
@ExtendWith(MockitoExtension.class)
class NotificationIdempotencyStoreTest {

    @Mock
    private NotificationRepository notificationRepository;

    private NotificationIdempotencyStore store;

    private Notification sampleNotification(String idempotencyKey) {
        return Notification.builder()
                .tenantId("tenant-1")
                .channel(NotificationChannel.EMAIL)
                .toAddress("test@example.com")
                .templateId("t1")
                .status(NotificationStatus.PENDING)
                .priority(NotificationPriority.NORMAL)
                .idempotencyKey(idempotencyKey)
                .build();
    }

    @Test
    void insertsNormallyWhenNoConflict() {
        store = new NotificationIdempotencyStore(notificationRepository);
        var candidate = sampleNotification("key-1");
        var saved = sampleNotification("key-1");
        saved.setId(UUID.randomUUID());
        when(notificationRepository.save(candidate)).thenReturn(saved);

        var result = store.insertIfAbsent(candidate, "tenant-1", "key-1");

        assertThat(result.wasDuplicate()).isFalse();
        assertThat(result.notification()).isEqualTo(saved);
    }

    @Test
    void doesNotEnforceUniquenessWhenNoIdempotencyKeySupplied() {
        store = new NotificationIdempotencyStore(notificationRepository);
        var candidate = sampleNotification(null);
        var saved = sampleNotification(null);
        saved.setId(UUID.randomUUID());
        when(notificationRepository.save(candidate)).thenReturn(saved);

        var result = store.insertIfAbsent(candidate, "tenant-1", null);

        assertThat(result.wasDuplicate()).isFalse();
        // Must never even attempt the fallback lookup for a null key --
        // there is no meaningful (tenantId, null) row to find, and doing
        // so risked matching an unrelated notification that also has no
        // idempotency key.
        verify(notificationRepository, org.mockito.Mockito.never())
                .findByTenantIdAndIdempotencyKey(any(), any());
    }

    @Test
    void returnsExistingNotificationOnDuplicateKeyInsteadOfInserting() {
        store = new NotificationIdempotencyStore(notificationRepository);
        var candidate = sampleNotification("key-1");
        var existing = sampleNotification("key-1");
        existing.setId(UUID.randomUUID());

        when(notificationRepository.save(candidate))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));
        when(notificationRepository.findByTenantIdAndIdempotencyKey("tenant-1", "key-1"))
                .thenReturn(Optional.of(existing));

        var result = store.insertIfAbsent(candidate, "tenant-1", "key-1");

        assertThat(result.wasDuplicate()).isTrue();
        assertThat(result.notification()).isEqualTo(existing);
        assertThat(result.notification().getId()).isEqualTo(existing.getId());
    }

    @Test
    void rethrowsWhenConstraintViolatedButNoMatchingRowFound() {
        // A constraint violation that ISN'T actually our idempotency
        // constraint (or a genuinely bizarre race outcome) must not be
        // silently swallowed as if it were a normal duplicate.
        store = new NotificationIdempotencyStore(notificationRepository);
        var candidate = sampleNotification("key-1");
        var violation = new DataIntegrityViolationException("some other constraint violated");

        when(notificationRepository.save(candidate)).thenThrow(violation);
        when(notificationRepository.findByTenantIdAndIdempotencyKey("tenant-1", "key-1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> store.insertIfAbsent(candidate, "tenant-1", "key-1"))
                .isSameAs(violation);
    }
}
