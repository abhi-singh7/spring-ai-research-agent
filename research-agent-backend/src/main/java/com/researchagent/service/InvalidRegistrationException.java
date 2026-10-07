package com.researchagent.service;

/**
 * Registration input violates a username/password rule (400 in the API).
 */
public class InvalidRegistrationException extends RuntimeException {

    public InvalidRegistrationException(String message) {
        super(message);
    }
}
