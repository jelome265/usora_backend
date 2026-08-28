package com.usora.notification.v1;

import com.usora.notification.exception.BusinessException.NotificationNotFoundException;
import com.usora.notification.security.TenantContext;
import com.usora.notification.service.DomainService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.data.domain.PageRequest;

import java.util.UUID;

/**
 * gRPC facade for {@link DomainService}, implementing the
 * NotificationService contract declared in notification.proto.
 *
 * F-012: this server-side implementation did not exist prior to this
 * change -- NotificationService was declared in this module's own
 * notification.proto (copied from shared/proto/notification.proto; Maven
 * codegen wired up via protoc-jar-maven-plugin in pom.xml, matching
 * usora-compliance-service's already-working pattern), and the API
 * gateway already has a fully-built typed client for it
 * (rust-services/usora-api-gateway's GrpcClients.notification), but
 * nothing on this side ever implemented the server, so any call the
 * gateway made to this service over gRPC would fail with UNIMPLEMENTED.
 * This class is a thin mapping layer only: every RPC delegates to the
 * exact same DomainService methods the (separately existing) REST
 * controller already uses, so no new business logic is introduced here.
 *
 * TENANT CONTEXT: unlike the HTTP path, where TenantInterceptor extracts
 * "tid" from the caller's own cryptographically verified JWT and never
 * trusts a client-supplied value (see F-002), a gRPC call arriving here
 * is server-to-server -- from the API gateway, over the internal
 * mTLS/service-token-authenticated channel (see F-007) -- and the
 * request message itself carries tenant_id as plain data, the same way
 * DomainEventListener's Kafka path already does (see that class's
 * withTenantContext). That is only as trustworthy as the gateway's own
 * internal-channel authentication and its own handling of the original
 * caller's tenant claim; this class does not (and cannot, at this layer)
 * re-verify that the supplied tenant_id actually matches whichever
 * end-user JWT originated the request further upstream. Every RPC here
 * fails closed (INVALID_ARGUMENT) if tenant_id is missing or blank, but
 * that is necessary, not sufficient -- closing the gap between "this
 * value is present" and "this value is authorized" is F-012's own
 * service-matrix/authorization-interceptor remediation item, not solved
 * here.
 */
@GrpcService
@RequiredArgsConstructor
@Slf4j
public class NotificationGrpcService extends NotificationServiceGrpc.NotificationServiceImplBase {

    private final DomainService domainService;

    @Override
    public void sendNotification(SendNotificationRequest request,
                                  StreamObserver<SendNotificationResponse> responseObserver) {
        withTenantContext(request.getTenantId(), responseObserver, () -> {
            var dtoRequest = com.usora.notification.dto.RequestDto.SendNotificationRequest.builder()
                    .to(request.getRecipient())
                    .channel(request.getChannel().name())
                    .templateId(request.getTemplateId())
                    .subject(request.getTitle())
                    .variables(new java.util.HashMap<>(request.getTemplateParamsMap()))
                    .priority(request.getPriority() == NotificationPriority.NOTIFICATION_PRIORITY_UNSPECIFIED
                            ? "NORMAL" : request.getPriority().name())
                    .build();

            var result = domainService.sendNotification(dtoRequest);

            var response = SendNotificationResponse.newBuilder()
                    .setNotificationId(String.valueOf(result.getId()))
                    .setStatus(toProtoStatus(result.getStatus()))
                    .setAccepted(true)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        });
    }

    @Override
    public void getNotificationStatus(GetNotificationStatusRequest request,
                                       StreamObserver<GetNotificationStatusResponse> responseObserver) {
        withTenantContext(request.getTenantId(), responseObserver, () -> {
            var id = parseId(request.getNotificationId(), responseObserver);
            if (id == null) return;

            try {
                var result = domainService.getNotificationStatus(id);
                var response = GetNotificationStatusResponse.newBuilder()
                        .setNotification(toProtoNotification(result, request.getTenantId()))
                        .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } catch (NotificationNotFoundException e) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Notification not found: " + request.getNotificationId())
                        .asRuntimeException());
            }
        });
    }

    @Override
    public void listNotifications(ListNotificationsRequest request,
                                   StreamObserver<ListNotificationsResponse> responseObserver) {
        withTenantContext(request.getTenantId(), responseObserver, () -> {
            // Page-token scheme: a plain decimal page-number string. This is
            // a real, working pagination mechanism (not a placeholder) --
            // simple by design, since this service has no other cursor-based
            // pagination elsewhere to stay consistent with.
            int page = 0;
            if (!request.getPageToken().isBlank()) {
                try {
                    page = Integer.parseInt(request.getPageToken());
                } catch (NumberFormatException e) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("page_token must be a page number produced by a previous response")
                            .asRuntimeException());
                    return;
                }
            }
            int size = request.getPageSize() > 0 ? request.getPageSize() : 20;

            var listRequest = com.usora.notification.dto.RequestDto.NotificationListRequest.builder()
                    .page(page)
                    .size(size)
                    .channel(request.getChannel() == NotificationChannel.NOTIFICATION_CHANNEL_UNSPECIFIED
                            ? null : request.getChannel().name())
                    .status(request.getStatus() == NotificationStatus.NOTIFICATION_STATUS_UNSPECIFIED
                            ? null : request.getStatus().name())
                    .build();

            var result = domainService.listNotifications(listRequest, PageRequest.of(page, size));

            var builder = ListNotificationsResponse.newBuilder()
                    .setTotalCount((int) result.getTotalElements());
            result.getNotifications().forEach(n -> builder.addNotifications(toProtoNotification(n, request.getTenantId())));
            if (!result.isLast()) {
                builder.setNextPageToken(String.valueOf(page + 1));
            }

            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        });
    }

    @Override
    public void acknowledgeNotification(AcknowledgeNotificationRequest request,
                                         StreamObserver<AcknowledgeNotificationResponse> responseObserver) {
        withTenantContext(request.getTenantId(), responseObserver, () -> {
            var id = parseId(request.getNotificationId(), responseObserver);
            if (id == null) return;

            try {
                var result = domainService.acknowledgeNotification(id);
                var response = AcknowledgeNotificationResponse.newBuilder()
                        .setNotification(toProtoNotification(result, request.getTenantId()))
                        .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } catch (NotificationNotFoundException e) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Notification not found: " + request.getNotificationId())
                        .asRuntimeException());
            }
        });
    }

    /**
     * Shared fail-closed tenant-context wrapper: sets TenantContext from the
     * request's tenant_id for the duration of the call, rejecting outright
     * (INVALID_ARGUMENT) if it's missing, and always clearing afterward --
     * the same shape as DomainEventListener's withTenantContext for the
     * Kafka path, so both entry points into this service enforce the
     * invariant identically.
     */
    private void withTenantContext(String tenantId, StreamObserver<?> responseObserver, Runnable action) {
        if (tenantId == null || tenantId.isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("tenant_id is required")
                    .asRuntimeException());
            return;
        }
        try {
            TenantContext.setCurrentTenantId(tenantId);
            action.run();
        } catch (Exception e) {
            log.error("Unhandled error processing gRPC notification request for tenant {}", tenantId, e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error processing request")
                    .asRuntimeException());
        } finally {
            TenantContext.clear();
        }
    }

    private UUID parseId(String notificationId, StreamObserver<?> responseObserver) {
        try {
            return UUID.fromString(notificationId);
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("notification_id must be a valid UUID")
                    .asRuntimeException());
            return null;
        }
    }

    private NotificationStatus toProtoStatus(String status) {
        if (status == null) {
            return NotificationStatus.NOTIFICATION_STATUS_UNSPECIFIED;
        }
        // Entity has a "SENDING" in-flight state with no direct proto
        // equivalent -- mapped to PENDING as the closest analogous
        // not-yet-terminal state, since the proto contract only has
        // PENDING/SENT/DELIVERED/FAILED/ACKNOWLEDGED.
        if ("SENDING".equals(status)) {
            return NotificationStatus.PENDING;
        }
        try {
            return NotificationStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return NotificationStatus.NOTIFICATION_STATUS_UNSPECIFIED;
        }
    }

    private NotificationChannel toProtoChannel(String channel) {
        if (channel == null) {
            return NotificationChannel.NOTIFICATION_CHANNEL_UNSPECIFIED;
        }
        try {
            return NotificationChannel.valueOf(channel);
        } catch (IllegalArgumentException e) {
            return NotificationChannel.NOTIFICATION_CHANNEL_UNSPECIFIED;
        }
    }

    private NotificationPriority toProtoPriority(String priority) {
        if (priority == null) {
            return NotificationPriority.NOTIFICATION_PRIORITY_UNSPECIFIED;
        }
        try {
            return NotificationPriority.valueOf(priority);
        } catch (IllegalArgumentException e) {
            return NotificationPriority.NOTIFICATION_PRIORITY_UNSPECIFIED;
        }
    }

    private Notification toProtoNotification(com.usora.notification.dto.ResponseDto.NotificationResponse r, String tenantId) {
        var builder = Notification.newBuilder()
                .setNotificationId(String.valueOf(r.getId()))
                .setTenantId(tenantId)
                .setRecipient(r.getToAddress() != null ? r.getToAddress() : "")
                .setChannel(toProtoChannel(r.getChannel()))
                .setStatus(toProtoStatus(r.getStatus()))
                .setPriority(toProtoPriority(r.getPriority()))
                .setTemplateId(r.getTemplateId() != null ? r.getTemplateId() : "")
                .setRetryCount(r.getRetryCount());

        if (r.getErrorMessage() != null) {
            builder.setErrorMessage(r.getErrorMessage());
        }
        if (r.getCreatedAt() != null) {
            builder.setCreatedAt(toProtoTimestamp(r.getCreatedAt()));
        }
        if (r.getSentAt() != null) {
            builder.setSentAt(toProtoTimestamp(r.getSentAt()));
        }
        if (r.getDeliveredAt() != null) {
            builder.setDeliveredAt(toProtoTimestamp(r.getDeliveredAt()));
        }
        if (r.getAcknowledgedAt() != null) {
            builder.setAcknowledgedAt(toProtoTimestamp(r.getAcknowledgedAt()));
        }
        return builder.build();
    }

    private com.google.protobuf.Timestamp toProtoTimestamp(java.time.LocalDateTime dateTime) {
        var instant = dateTime.atZone(java.time.ZoneOffset.UTC).toInstant();
        return com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
