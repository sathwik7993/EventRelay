package com.eventrelay.core.service;

/** Raised when a subscription target URL is rejected by the SSRF policy. */
public class InvalidTargetUrlException extends RuntimeException {

    public InvalidTargetUrlException(String message) {
        super(message);
    }
}
