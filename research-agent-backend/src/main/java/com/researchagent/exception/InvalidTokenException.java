package com.researchagent.security;

/**
 * Thrown when a JWT fails validation: malformed, bad signature, expired or wrong issuer.
 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }
}
