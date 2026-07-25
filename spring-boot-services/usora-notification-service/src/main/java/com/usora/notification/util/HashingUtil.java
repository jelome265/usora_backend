package com.usora.notification.util;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Component
public class HashingUtil {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    public String hmacSha256(String secret, String data) {
        try {
            var keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            var mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(keySpec);
            var hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC computation failed", e);
        }
    }

    public boolean verifyHmacSha256(String secret, String data, String expectedSignature) {
        var computed = hmacSha256(secret, data);
        return computed.equals(expectedSignature);
    }
}
