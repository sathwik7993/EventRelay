package com.eventrelay.common.crypto;

import com.eventrelay.common.util.Ids;
import org.springframework.security.crypto.bcrypt.BCrypt;

/**
 * Generation and verification of tenant API keys.
 *
 * <p>The raw key is shown to the caller exactly once at creation time. Only the
 * bcrypt hash and a short non-secret prefix are persisted. The prefix lets us
 * locate a candidate key row without exposing the secret in logs or dashboards.
 */
public final class ApiKeys {

    /** Live-key marker embedded in the raw key so it is self-describing. */
    public static final String LIVE_PREFIX = "er_live";

    /** Number of characters of the raw key stored as the lookup prefix. */
    public static final int PREFIX_LENGTH = 12;

    private static final int BCRYPT_COST = 12;
    private static final int SECRET_LENGTH = 32;

    private ApiKeys() {
    }

    /** A freshly generated key: the raw value (returned once) and its stored form. */
    public record Generated(String rawKey, String keyHash, String keyPrefix) {
    }

    public static Generated generate() {
        String raw = LIVE_PREFIX + "_" + Ids.token(SECRET_LENGTH);
        String hash = BCrypt.hashpw(raw, BCrypt.gensalt(BCRYPT_COST));
        String prefix = raw.substring(0, Math.min(PREFIX_LENGTH, raw.length()));
        return new Generated(raw, hash, prefix);
    }

    /** Extracts the stored lookup prefix from a raw key presented on a request. */
    public static String prefixOf(String rawKey) {
        if (rawKey == null || rawKey.length() < PREFIX_LENGTH) {
            return rawKey;
        }
        return rawKey.substring(0, PREFIX_LENGTH);
    }

    /** Constant-time verification of a presented raw key against a stored hash. */
    public static boolean matches(String rawKey, String keyHash) {
        if (rawKey == null || keyHash == null || keyHash.isBlank()) {
            return false;
        }
        return BCrypt.checkpw(rawKey, keyHash);
    }
}
