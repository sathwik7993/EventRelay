package com.eventrelay.common.crypto;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates per-subscription signing secrets in the Standard Webhooks format:
 * {@code whsec_<base64 of 24 random bytes>}. Keeping the documented format means
 * off-the-shelf verification libraries can consume the secret directly.
 */
public final class SigningSecrets {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int KEY_BYTES = 24;
    private static final String PREFIX = "whsec_";

    private SigningSecrets() {
    }

    public static String generate() {
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        return PREFIX + Base64.getEncoder().encodeToString(key);
    }
}
