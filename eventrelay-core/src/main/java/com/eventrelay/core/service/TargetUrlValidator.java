package com.eventrelay.core.service;

import com.eventrelay.common.net.TargetUrlPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Applies {@link TargetUrlPolicy} using environment configuration.
 *
 * <p>Defaults are the secure ones: HTTPS only, no private/loopback targets. Local
 * development enables the relaxed flags so subscriptions can point at a receiver
 * on localhost.
 */
@Component
public class TargetUrlValidator {

    private final boolean allowInsecureScheme;
    private final boolean allowPrivateAddresses;

    public TargetUrlValidator(
            @Value("${eventrelay.security.allow-insecure-target-urls:false}") boolean allowInsecureScheme,
            @Value("${eventrelay.security.allow-private-target-urls:false}") boolean allowPrivateAddresses) {
        this.allowInsecureScheme = allowInsecureScheme;
        this.allowPrivateAddresses = allowPrivateAddresses;
    }

    public TargetUrlPolicy.Result check(String url) {
        return TargetUrlPolicy.check(url, allowInsecureScheme, allowPrivateAddresses);
    }

    public boolean isAllowed(String url) {
        return check(url).allowed();
    }

    /** Throws {@link InvalidTargetUrlException} if the URL violates the policy. */
    public void validate(String url) {
        TargetUrlPolicy.Result result = check(url);
        if (!result.allowed()) {
            throw new InvalidTargetUrlException(result.reason());
        }
    }
}
