package com.usora.notification.mapper;

import com.usora.notification.dto.ResponseDto.NotificationListResponse;
import com.usora.notification.dto.ResponseDto.NotificationResponse;
import com.usora.notification.entity.Notification;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.data.domain.Page;

import java.util.List;

@Mapper(componentModel = "spring")
public interface EntityMapper {

    @Mapping(target = "channel", source = "channel")
    NotificationResponse toResponse(Notification notification);

    List<NotificationResponse> toResponseList(List<Notification> notifications);

    default NotificationListResponse toListResponse(Page<Notification> page) {
        return NotificationListResponse.builder()
                .notifications(toResponseList(page.getContent()))
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .first(page.isFirst())
                .last(page.isLast())
                .build();
    }
}
