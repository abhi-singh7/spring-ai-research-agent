package com.researchagent.service;

/**
 * The username is already registered (409 in the API).
 */
public class DuplicateUsernameException extends RuntimeException {

    public DuplicateUsernameException(String message) {
        super(message);
    }
}
