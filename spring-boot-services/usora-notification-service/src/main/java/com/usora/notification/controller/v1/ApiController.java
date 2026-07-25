package com.usora.notification.controller.v1;

import com.usora.notification.dto.RequestDto.NotificationListRequest;
import com.usora.notification.dto.RequestDto.SendNotificationRequest;
import com.usora.notification.dto.ResponseDto.NotificationListResponse;
import com.usora.notification.dto.ResponseDto.NotificationResponse;
import com.usora.notification.service.DomainService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class ApiController {

    private final DomainService domainService;

    @PostMapping("/send")
    public ResponseEntity<NotificationResponse> sendNotification(
            @Valid @RequestBody SendNotificationRequest request) {
        var response = domainService.sendNotification(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<NotificationResponse> getNotificationStatus(@PathVariable UUID id) {
        var response = domainService.getNotificationStatus(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<NotificationListResponse> listNotifications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) LocalDateTime dateFrom,
            @RequestParam(required = false) LocalDateTime dateTo,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        var request = NotificationListRequest.builder()
                .page(page)
                .size(size)
                .channel(channel)
                .status(status)
                .dateFrom(dateFrom)
                .dateTo(dateTo)
                .sortBy(sortBy)
                .sortDir(sortDir)
                .build();

        var pageable = PageRequest.of(
                request.getPage(),
                request.getSize(),
                Sort.by(Sort.Direction.fromString(request.getSortDir()), request.getSortBy()));

        var response = domainService.listNotifications(request, pageable);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<NotificationResponse> acknowledgeNotification(@PathVariable UUID id) {
        var response = domainService.acknowledgeNotification(id);
        return ResponseEntity.ok(response);
    }
}
