package com.eventrelay.common.util;

import java.security.SecureRandom;

/**
 * Helpers for generating prefixed, URL-safe identifiers and random secrets.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] BASE62 =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private Ids() {
    }

    /**
     * Returns a random base62 token of the given length.
     */
    public static String token(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(BASE62[RANDOM.nextInt(BASE62.length)]);
        }
        return sb.toString();
    }

    /**
     * Returns {@code prefix + "_" + token}, e.g. {@code whsec_a9f3...}.
     */
    public static String prefixed(String prefix, int length) {
        return prefix + "_" + token(length);
    }
}
