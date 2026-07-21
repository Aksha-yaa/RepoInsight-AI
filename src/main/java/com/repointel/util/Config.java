package com.repointel.util;

public final class Config {
    private Config() { }
    public static String get(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
