package com.eventrelay.common.net;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * SSRF guard for subscriber target URLs (FR-8.4 / NFR-5.6).
 *
 * <p>Without this, a tenant could register a subscription pointing at internal
 * infrastructure and use EventRelay as a confused deputy to reach it — the cloud
 * metadata endpoint (169.254.169.254) being the classic target. Every hostname is
 * resolved and every resolved address is checked, because a public hostname can
 * legitimately resolve to a private address.
 */
public final class TargetUrlPolicy {

    /** AWS/GCP/Azure instance metadata service. */
    private static final String METADATA_IPV4 = "169.254.169.254";

    private TargetUrlPolicy() {
    }

    /** Outcome of a policy check; {@code reason} is non-null only when denied. */
    public record Result(boolean allowed, String reason) {
        public static Result allow() {
            return new Result(true, null);
        }

        public static Result deny(String reason) {
            return new Result(false, reason);
        }
    }

    /**
     * @param allowInsecureScheme   permit plain {@code http://} (local development only)
     * @param allowPrivateAddresses permit loopback/private/link-local targets (local development only)
     */
    public static Result check(String url, boolean allowInsecureScheme, boolean allowPrivateAddresses) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return Result.deny("Target URL is not a valid URI");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            return Result.deny("Target URL must use http or https");
        }
        if (scheme.equals("http") && !allowInsecureScheme) {
            return Result.deny("Target URL must use https");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Result.deny("Target URL has no host");
        }

        if (allowPrivateAddresses) {
            return Result.allow();
        }

        String lowerHost = host.toLowerCase(Locale.ROOT);
        if (lowerHost.equals("localhost") || lowerHost.endsWith(".localhost")
                || lowerHost.endsWith(".internal") || lowerHost.endsWith(".local")) {
            return Result.deny("Target URL resolves to an internal host");
        }
        if (lowerHost.equals(METADATA_IPV4)) {
            return Result.deny("Target URL points at the cloud metadata endpoint");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return Result.deny("Target URL host cannot be resolved");
        }

        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                return Result.deny("Target URL resolves to a non-public address");
            }
        }
        return Result.allow();
    }

    private static boolean isBlocked(InetAddress address) {
        if (address.isLoopbackAddress()      // 127.0.0.0/8, ::1
                || address.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
                || address.isLinkLocalAddress()  // 169.254/16, fe80::/10
                || address.isAnyLocalAddress()   // 0.0.0.0, ::
                || address.isMulticastAddress()) {
            return true;
        }
        if (METADATA_IPV4.equals(address.getHostAddress())) {
            return true;
        }
        byte[] bytes = address.getAddress();
        // IPv6 unique local addresses (fc00::/7) are not covered by isSiteLocalAddress.
        if (bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC) {
            return true;
        }
        // IPv4 carrier-grade NAT range 100.64.0.0/10.
        if (bytes.length == 4 && (bytes[0] & 0xFF) == 100 && ((bytes[1] & 0xFF) >= 64 && (bytes[1] & 0xFF) <= 127)) {
            return true;
        }
        return false;
    }
}
