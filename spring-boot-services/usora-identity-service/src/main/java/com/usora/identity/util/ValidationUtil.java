package com.usora.identity.util;

import java.util.regex.Pattern;

public final class ValidationUtil {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern TENANT_NAME_PATTERN =
            Pattern.compile("^[a-z0-9]([a-z0-9-]{1,61}[a-z0-9])?$");
    private static final Pattern SCOPE_PATTERN =
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9:_-]{0,255}$");

    private ValidationUtil() {}

    public static boolean isValidEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email).matches();
    }

    public static boolean isValidUuid(String uuid) {
        return uuid != null && UUID_PATTERN.matcher(uuid).matches();
    }

    public static boolean isValidTenantName(String name) {
        return name != null && TENANT_NAME_PATTERN.matcher(name).matches();
    }

    public static boolean isValidScope(String scope) {
        return scope != null && SCOPE_PATTERN.matcher(scope).matches();
    }

    public static boolean isValidPassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 128) {
            return false;
        }
        boolean hasUpper = false, hasLower = false, hasDigit = false, hasSpecial = false;
        for (char c : password.toCharArray()) {
            if (Character.isUpperCase(c)) hasUpper = true;
            else if (Character.isLowerCase(c)) hasLower = true;
            else if (Character.isDigit(c)) hasDigit = true;
            else hasSpecial = true;
        }
        return hasUpper && hasLower && hasDigit && hasSpecial;
    }

    public static boolean isValidRedirectUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return false;
        }
        try {
            var parsed = new java.net.URI(uri);
            return "http".equals(parsed.getScheme()) || "https".equals(parsed.getScheme());
        } catch (Exception e) {
            return false;
        }
    }

    public static void requireValidEmail(String email) {
        if (!isValidEmail(email)) {
            throw new IllegalArgumentException("Invalid email format: " + email);
        }
    }

    public static void requireValidUuid(String id) {
        if (!isValidUuid(id)) {
            throw new IllegalArgumentException("Invalid UUID format: " + id);
        }
    }
}
