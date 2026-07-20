package com.eventrelay.common.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Signing per the <a href="https://www.standardwebhooks.com/">Standard Webhooks</a>
 * specification, so that any off-the-shelf verification library works against
 * EventRelay deliveries.
 *
 * <p>The signed content is {@code "{msg_id}.{timestamp}.{payload}"} and the header
 * value is {@code "v1,<base64 signature>"}. Secrets carry the {@code whsec_} prefix
 * and the remainder is base64 — it is decoded to raw key bytes before signing.
 *
 * <p>This is emitted alongside the legacy {@code X-EventRelay-Signature} header
 * (see {@link HmacSigner}), so existing consumers keep working.
 */
public final class StandardWebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String SECRET_PREFIX = "whsec_";
    public static final String VERSION = "v1";

    private StandardWebhookSigner() {
    }

    /** Returns the {@code webhook-signature} header value, e.g. {@code v1,g0hM9S...}. */
    public static String sign(String secret, String messageId, long timestampSeconds, String payload) {
        String signedContent = messageId + "." + timestampSeconds + "." + payload;
        byte[] key = keyBytes(secret);
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            byte[] signature = mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8));
            return VERSION + "," + Base64.getEncoder().encodeToString(signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Standard Webhooks signing failed", e);
        }
    }

    /**
     * Resolves the raw key bytes from a signing secret. Spec-format secrets
     * ({@code whsec_<base64>}) are decoded; anything else is used verbatim so
     * older secrets keep verifying.
     */
    private static byte[] keyBytes(String secret) {
        if (secret != null && secret.startsWith(SECRET_PREFIX)) {
            String encoded = secret.substring(SECRET_PREFIX.length());
            try {
                return Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException notBase64) {
                return encoded.getBytes(StandardCharsets.UTF_8);
            }
        }
        return String.valueOf(secret).getBytes(StandardCharsets.UTF_8);
    }
}
