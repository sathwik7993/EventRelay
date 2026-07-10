package com.eventrelay.common.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * HMAC-SHA256 webhook signing, matching the scheme documented in
 * {@code 06_Security/HMAC_Request_Signing.md} (Stripe/Svix style).
 *
 * <p>The signed content is {@code timestamp + "." + payload}; the header value
 * is {@code v1=<hex>}. Including the timestamp lets receivers reject replays
 * outside a tolerance window.
 */
public final class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";
    public static final String VERSION_PREFIX = "v1";

    private HmacSigner() {
    }

    /** Returns the full header value, e.g. {@code v1=5257a869...}. */
    public static String sign(String secret, long timestampSeconds, String payload) {
        String signedContent = timestampSeconds + "." + payload;
        byte[] mac = hmac(secret, signedContent);
        return VERSION_PREFIX + "=" + toHex(mac);
    }

    private static byte[] hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
