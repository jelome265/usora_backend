package com.usora.notification.service;

import com.usora.notification.dto.RequestDto.NotificationListRequest;
import com.usora.notification.dto.RequestDto.SendNotificationRequest;
import com.usora.notification.dto.ResponseDto.NotificationListResponse;
import com.usora.notification.dto.ResponseDto.NotificationResponse;
import com.usora.notification.entity.Notification;
import com.usora.notification.entity.Notification.NotificationChannel;
import com.usora.notification.entity.Notification.NotificationPriority;
import com.usora.notification.entity.Notification.NotificationStatus;
import com.usora.notification.entity.TenantEntity;
import com.usora.notification.event.DomainEventPublisher;
import com.usora.notification.exception.BusinessException.InvalidAddressException;
import com.usora.notification.exception.BusinessException.InvalidChannelException;
import com.usora.notification.exception.BusinessException.NotificationFailureException;
import com.usora.notification.exception.BusinessException.NotificationNotFoundException;
import com.usora.notification.exception.BusinessException.ProviderUnavailableException;
import com.usora.notification.mapper.EntityMapper;
import com.usora.notification.repository.NotificationRepository;
import com.usora.notification.security.TenantContext;
import com.usora.notification.util.HashingUtil;
import com.usora.notification.util.ValidationUtil;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DomainService {

    private final NotificationRepository notificationRepository;
    private final NotificationIdempotencyStore notificationIdempotencyStore;
    private final EntityMapper entityMapper;
    private final DomainEventPublisher eventPublisher;
    private final TenantAwareService tenantAwareService;
    private final ValidationUtil validationUtil;
    private final HashingUtil hashingUtil;

    @Value("${notification.webhook.retry.max-attempts:3}")
    private int maxWebhookRetries;

    @Transactional
    public NotificationResponse sendNotification(@Valid SendNotificationRequest request) {
        var tenantId = TenantContext.getCurrentTenantId();
        log.info("Sending notification via {} to {} for tenant {}",
                request.getChannel(), request.getTo(), tenantId);

        validationUtil.validateAddress(request.getTo(), request.getChannel());

        var channel = parseChannel(request.getChannel());
        var priority = parsePriority(request.getPriority());
        var tenantConfig = tenantAwareService.getCurrentTenantConfig();

        var notification = Notification.builder()
                .tenantId(tenantId)
                .channel(channel)
                .toAddress(request.getTo())
                .templateId(request.getTemplateId())
                .subject(request.getSubject())
                .variables(request.getVariables())
                .attachments(request.getAttachments())
                .status(NotificationStatus.PENDING)
                .priority(priority)
                .idempotencyKey(request.getIdempotencyKey())
                .build();

        // F-023: replaces a plain, unconditional
        // notificationRepository.save(notification) call. When the
        // caller supplies an idempotencyKey and this exact
        // (tenant, key) pair was already used for a prior call -- e.g.
        // this is a client retry after a timeout whose response the
        // client never actually saw, not a genuinely new request -- this
        // returns the ORIGINAL notification instead of creating (and
        // then sending) a duplicate.
        var insertResult = notificationIdempotencyStore.insertIfAbsent(
                notification, tenantId, request.getIdempotencyKey());
        notification = insertResult.notification();

        if (insertResult.wasDuplicate()) {
            log.info("Notification send with idempotency key {} for tenant {} already existed as {} -- " +
                            "returning the original notification, not re-sending",
                    request.getIdempotencyKey(), tenantId, notification.getId());
            return entityMapper.toResponse(notification);
        }

        log.info("Notification {} saved with status PENDING", notification.getId());

        deliverAsync(notification.getId(), tenantConfig);
        return entityMapper.toResponse(notification);
    }

    @Async
    protected void deliverAsync(UUID notificationId, TenantEntity tenantConfig) {
        try {
            var notification = notificationRepository.findById(notificationId)
                    .orElseThrow(() -> new NotificationNotFoundException(notificationId.toString()));

            notification.setStatus(NotificationStatus.SENDING);
            notificationRepository.save(notification);

            var deliveryService = new NotificationDeliveryService(tenantConfig);

            switch (notification.getChannel()) {
                case EMAIL -> deliveryService.sendEmail(notification);
                case SMS -> deliveryService.sendSms(notification);
                case WEBHOOK -> deliveryService.sendWebhook(notification);
                case PUSH_IN_APP -> deliveryService.sendPush(notification);
            }

            notification.setStatus(NotificationStatus.SENT);
            notification.setSentAt(LocalDateTime.now());
            notificationRepository.save(notification);

            eventPublisher.publishNotificationDelivered(notification);
            log.info("Notification {} sent successfully", notification.getId());

        } catch (Exception e) {
            handleDeliveryFailure(notificationId, e);
        }
    }

    @CircuitBreaker(name = "notificationDelivery", fallbackMethod = "deliveryFallback")
    @Retry(name = "notificationRetry")
    protected void deliverWithRetry(UUID notificationId, TenantEntity tenantConfig) {
        deliverAsync(notificationId, tenantConfig);
    }

    protected void deliveryFallback(UUID notificationId, TenantEntity tenantConfig, Throwable t) {
        log.error("Circuit breaker triggered for notification delivery: {}", notificationId);
        handleDeliveryFailure(notificationId, t);
    }

    private void handleDeliveryFailure(UUID notificationId, Throwable cause) {
        try {
            var notification = notificationRepository.findById(notificationId)
                    .orElse(null);
            if (notification == null) return;

            notification.setStatus(NotificationStatus.FAILED);
            notification.setFailedAt(LocalDateTime.now());
            notification.setErrorMessage(cause.getMessage());
            notification.setRetryCount(notification.getRetryCount() + 1);
            notificationRepository.save(notification);

            eventPublisher.publishNotificationFailed(notification, cause.getMessage());
            log.error("Notification {} failed: {}", notificationId, cause.getMessage());
        } catch (Exception e) {
            log.error("Failed to handle delivery failure for notification {}", notificationId, e);
        }
    }

    @Transactional(readOnly = true)
    public NotificationResponse getNotificationStatus(UUID id) {
        var notification = notificationRepository.findById(id)
                .orElseThrow(() -> new NotificationNotFoundException(id.toString()));
        return entityMapper.toResponse(notification);
    }

    @Transactional(readOnly = true)
    public NotificationListResponse listNotifications(NotificationListRequest request, Pageable pageable) {
        var tenantId = TenantContext.getCurrentTenantId();

        Specification<Notification> spec = (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));

            Optional.ofNullable(request.getChannel())
                    .ifPresent(ch -> predicates.add(
                            cb.equal(root.get("channel"), parseChannel(ch))));

            Optional.ofNullable(request.getStatus())
                    .ifPresent(st -> predicates.add(
                            cb.equal(root.get("status"), NotificationStatus.valueOf(st))));

            if (request.getDateFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), request.getDateFrom()));
            }
            if (request.getDateTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), request.getDateTo()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<Notification> page = notificationRepository.findAll(spec, pageable);
        return entityMapper.toListResponse(page);
    }

    @Transactional
    public NotificationResponse acknowledgeNotification(UUID id) {
        var notification = notificationRepository.findById(id)
                .orElseThrow(() -> new NotificationNotFoundException(id.toString()));

        notification.setStatus(NotificationStatus.ACKNOWLEDGED);
        notification.setAcknowledgedAt(LocalDateTime.now());
        notification = notificationRepository.save(notification);

        eventPublisher.publishNotificationAcknowledged(notification);
        log.info("Notification {} acknowledged", id);

        return entityMapper.toResponse(notification);
    }

    @Transactional
    public void retryNotification(Notification notification) {
        if (notification.getRetryCount() >= 3) {
            log.warn("Notification {} has exceeded max retry count", notification.getId());
            return;
        }

        var tenantConfig = tenantAwareService.getTenantConfig(notification.getTenantId());
        notification.setStatus(NotificationStatus.PENDING);
        notification.setRetryCount(notification.getRetryCount() + 1);
        notificationRepository.save(notification);

        deliverWithRetry(notification.getId(), tenantConfig);
    }

    private NotificationChannel parseChannel(String channel) {
        try {
            return NotificationChannel.valueOf(channel.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidChannelException(channel);
        }
    }

    private NotificationPriority parsePriority(String priority) {
        try {
            return NotificationPriority.valueOf(priority.toUpperCase());
        } catch (IllegalArgumentException e) {
            return NotificationPriority.NORMAL;
        }
    }

    public class NotificationDeliveryService {

        private final TenantEntity tenantConfig;

        public NotificationDeliveryService(TenantEntity tenantConfig) {
            this.tenantConfig = tenantConfig;
        }

        public void sendEmail(Notification notification) {
            try {
                var sender = ApplicationContextProvider.getBean(JavaMailSender.class);
                var message = new SimpleMailMessage();
                message.setTo(notification.getToAddress());
                message.setSubject(notification.getSubject() != null
                        ? notification.getSubject() : "Notification");
                message.setText(buildContent(notification));
                message.setFrom(tenantConfig.getSendgridFromEmail());

                sender.send(message);
                log.debug("Email sent to {}", notification.getToAddress());
            } catch (Exception e) {
                throw new NotificationFailureException("Failed to send email", e);
            }
        }

        public void sendSms(Notification notification) {
            try {
                var twilioService = ApplicationContextProvider.getBean(TwilioService.class);
                twilioService.sendSms(
                        tenantConfig.getTwilioAccountSid(),
                        tenantConfig.getTwilioAuthToken(),
                        tenantConfig.getTwilioFromNumber(),
                        notification.getToAddress(),
                        buildContent(notification)
                );
                log.debug("SMS sent to {}", notification.getToAddress());
            } catch (Exception e) {
                throw new NotificationFailureException("Failed to send SMS", e);
            }
        }

        public void sendWebhook(Notification notification) {
            try {
                var restTemplate = ApplicationContextProvider.getBean(RestTemplate.class);
                var webhookUrl = resolveWebhookUrl(notification);

                var headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                var payload = buildWebhookPayload(notification);
                var signature = hashingUtil.hmacSha256(
                        tenantConfig.getWebhookSecret(), payload);
                headers.set("X-Webhook-Signature", signature);
                headers.set("X-Notification-Id", notification.getId().toString());

                var entity = new HttpEntity<>(payload, headers);
                var response = restTemplate.exchange(
                        webhookUrl, HttpMethod.POST, entity, String.class);

                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new NotificationFailureException(
                            "Webhook returned " + response.getStatusCode());
                }
                log.debug("Webhook sent to {}", webhookUrl);
            } catch (Exception e) {
                throw new NotificationFailureException("Failed to send webhook", e);
            }
        }

        public void sendPush(Notification notification) {
            try {
                var fcmConfig = tenantConfig.getPushFcmConfig();
                if (fcmConfig == null || fcmConfig.isEmpty()) {
                    throw new ProviderUnavailableException("FCM not configured for tenant");
                }

                var restTemplate = ApplicationContextProvider.getBean("fcmRestTemplate", RestTemplate.class);

                var pushPayload = Map.<String, Object>of(
                        "to", notification.getToAddress(),
                        "notification", Map.of(
                                "title", notification.getSubject() != null
                                        ? notification.getSubject() : "Notification",
                                "body", buildContent(notification)
                        ),
                        "data", notification.getVariables() != null
                                ? notification.getVariables() : Map.of()
                );

                var headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth((String) fcmConfig.get("serverKey"));

                var entity = new HttpEntity<>(pushPayload, headers);
                var response = restTemplate.postForEntity(
                        "https://fcm.googleapis.com/fcm/send", entity, String.class);

                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new NotificationFailureException(
                            "Push notification failed: " + response.getStatusCode());
                }
                log.debug("Push notification sent to device {}", notification.getToAddress());
            } catch (Exception e) {
                throw new NotificationFailureException("Failed to send push notification", e);
            }
        }

        private String buildContent(Notification notification) {
            var content = "Template: " + notification.getTemplateId();
            if (notification.getVariables() != null && !notification.getVariables().isEmpty()) {
                content += "\n" + notification.getVariables().toString();
            }
            return content;
        }

        private String resolveWebhookUrl(Notification notification) {
            var template = tenantConfig.getWebhookUrlTemplate();
            if (template == null || template.isBlank()) {
                return notification.getToAddress();
            }
            return template.replace("{to}", notification.getToAddress())
                    .replace("{tenantId}", notification.getTenantId())
                    .replace("{notificationId}", notification.getId().toString());
        }

        private String buildWebhookPayload(Notification notification) {
            var sb = new StringBuilder();
            sb.append("{\"notificationId\":\"").append(notification.getId())
                    .append("\",\"tenantId\":\"").append(notification.getTenantId())
                    .append("\",\"channel\":\"").append(notification.getChannel())
                    .append("\",\"templateId\":\"").append(notification.getTemplateId())
                    .append("\",\"to\":\"").append(notification.getToAddress())
                    .append("\"");
            if (notification.getVariables() != null) {
                sb.append(",\"variables\":").append(
                        ApplicationContextProvider.getBean(
                                com.fasterxml.jackson.databind.ObjectMapper.class)
                                .valueToTree(notification.getVariables()));
            }
            sb.append("}");
            return sb.toString();
        }
    }
}
