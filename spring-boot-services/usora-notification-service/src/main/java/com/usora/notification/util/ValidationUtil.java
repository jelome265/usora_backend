package com.usora.notification.util;

import com.usora.notification.exception.BusinessException.InvalidAddressException;
import com.usora.notification.exception.BusinessException.InvalidChannelException;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class ValidationUtil {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private static final Pattern PHONE_PATTERN =
            Pattern.compile("^\\+?[1-9]\\d{1,14}$");

    private static final Pattern URL_PATTERN =
            Pattern.compile("^https?://[\\w.-]+(:\\d+)?(/[\\w./%-]*)?$");

    private static final Pattern DEVICE_TOKEN_PATTERN =
            Pattern.compile("^[A-Za-z0-9:_-]{64,}$");

    public void validateAddress(String address, String channel) {
        if (address == null || address.isBlank()) {
            throw new InvalidAddressException(address, channel);
        }

        switch (channel.toUpperCase()) {
            case "EMAIL":
                if (!EMAIL_PATTERN.matcher(address).matches()) {
                    throw new InvalidAddressException(address, "EMAIL");
                }
                break;
            case "SMS":
                if (!PHONE_PATTERN.matcher(address).matches()) {
                    throw new InvalidAddressException(address, "SMS");
                }
                break;
            case "WEBHOOK":
                if (!URL_PATTERN.matcher(address).matches()) {
                    throw new InvalidAddressException(address, "WEBHOOK");
                }
                break;
            case "PUSH_IN_APP":
                if (!DEVICE_TOKEN_PATTERN.matcher(address).matches()) {
                    throw new InvalidAddressException(address, "PUSH_IN_APP");
                }
                break;
            default:
                throw new InvalidChannelException(channel);
        }
    }

    public boolean isValidEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email).matches();
    }

    public boolean isValidPhone(String phone) {
        return phone != null && PHONE_PATTERN.matcher(phone).matches();
    }

    public boolean isValidUrl(String url) {
        return url != null && URL_PATTERN.matcher(url).matches();
    }

    public boolean isValidDeviceToken(String token) {
        return token != null && DEVICE_TOKEN_PATTERN.matcher(token).matches();
    }
}
