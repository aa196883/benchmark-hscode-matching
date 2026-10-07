package com.semsoft.lestr.common.test;

/** Local-only substitute: the industrial project supplies its own secret-loading utility. Never export. */
public final class Utils {
    private Utils() {}

    public static String getSecret(String name) {
        if (!"OPENAI-API".equals(name)) {
            throw new IllegalArgumentException("The local secret adapter only supports OPENAI-API");
        }
        String value = System.getenv("OPENAI_API_KEY");
        return value == null || value.isBlank() ? null : value;
    }
}
