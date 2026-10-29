package com.misho.exchange.user;

public class UserAlreadyExistsException extends IllegalArgumentException {

    public UserAlreadyExistsException() {
        super("Username or email is already registered.");
    }
}
