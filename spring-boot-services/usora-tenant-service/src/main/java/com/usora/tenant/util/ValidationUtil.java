package com.usora.tenant.util;

import java.util.regex.Pattern;

public final class ValidationUtil {

    private static final Pattern DOMAIN_PATTERN =
            Pattern.compile("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$");
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private ValidationUtil() {}

    public static boolean isValidDomain(String domain) {
        return domain != null && DOMAIN_PATTERN.matcher(domain).matches();
    }

    public static boolean isValidEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email).matches();
    }

    public static boolean isValidUuid(String uuid) {
        return uuid != null && UUID_PATTERN.matcher(uuid).matches();
    }

    public static boolean isValidPlan(String plan) {
        return plan != null && (plan.equals("free") || plan.equals("starter") ||
                plan.equals("business") || plan.equals("enterprise"));
    }

    public static boolean isValidRegion(String region) {
        return region != null && (region.equals("us-east") || region.equals("us-west") ||
                region.equals("eu-west") || region.equals("eu-central") ||
                region.equals("ap-southeast") || region.equals("ap-northeast"));
    }

    public static boolean isValidTenantName(String name) {
        return name != null && name.length() >= 2 && name.length() <= 255;
    }

    public static String sanitize(String input) {
        if (input == null) return null;
        return input.trim().replaceAll("[<>\"'&]", "");
    }
}
