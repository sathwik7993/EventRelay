package com.eventrelay.dispatcher.worker;

/**
 * Result of one HTTP delivery attempt.
 *
 * @param success   true if a 2xx was received
 * @param permanent true if the failure should not be retried (moves to DLQ)
 * @param httpStatus the HTTP status code, or null on a network/timeout error
 * @param error     human-readable failure detail, or null on success
 */
public record DeliveryOutcome(boolean success, boolean permanent, Integer httpStatus, String error) {

    public static DeliveryOutcome ok(int status) {
        return new DeliveryOutcome(true, false, status, null);
    }

    public static DeliveryOutcome transient_(Integer status, String error) {
        return new DeliveryOutcome(false, false, status, error);
    }

    public static DeliveryOutcome permanent(Integer status, String error) {
        return new DeliveryOutcome(false, true, status, error);
    }
}
