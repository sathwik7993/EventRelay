package com.eventrelay.core.security;

import com.eventrelay.common.net.TargetUrlPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** SSRF policy unit tests (no Docker required — these always run in CI). */
class TargetUrlPolicyTest {

    private static final boolean STRICT_SCHEME = false;
    private static final boolean STRICT_ADDRESSES = false;

    @ParameterizedTest
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data/",   // cloud metadata
            "https://169.254.169.254/latest/meta-data/",
            "https://localhost/webhook",
            "https://127.0.0.1/webhook",
            "https://10.0.0.5/webhook",                    // private class A
            "https://192.168.1.10/webhook",                // private class C
            "https://172.16.4.4/webhook",                  // private class B
            "https://0.0.0.0/webhook",
            "https://db.internal/webhook",
            "https://service.local/webhook",
    })
    void blocksInternalAndMetadataTargets(String url) {
        TargetUrlPolicy.Result result = TargetUrlPolicy.check(url, STRICT_SCHEME, STRICT_ADDRESSES);
        assertThat(result.allowed())
                .as("expected %s to be blocked", url)
                .isFalse();
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void blocksPlainHttpWhenInsecureSchemeDisallowed() {
        assertThat(TargetUrlPolicy.check("http://example.com/hook", false, false).allowed()).isFalse();
    }

    @Test
    void allowsPlainHttpWhenExplicitlyEnabled() {
        assertThat(TargetUrlPolicy.check("http://example.com/hook", true, false).allowed()).isTrue();
    }

    @Test
    void rejectsNonHttpSchemes() {
        assertThat(TargetUrlPolicy.check("file:///etc/passwd", true, true).allowed()).isFalse();
        assertThat(TargetUrlPolicy.check("gopher://example.com/", true, true).allowed()).isFalse();
    }

    @Test
    void rejectsUrlWithoutHost() {
        assertThat(TargetUrlPolicy.check("https:///webhook", false, false).allowed()).isFalse();
    }

    @Test
    void allowsLoopbackOnlyWhenPrivateTargetsEnabled() {
        assertThat(TargetUrlPolicy.check("http://127.0.0.1:9999/ok", true, true).allowed()).isTrue();
        assertThat(TargetUrlPolicy.check("http://127.0.0.1:9999/ok", true, false).allowed()).isFalse();
    }
}
