package com.fraud.service;

public class DuplicateTransactionConflictException extends RuntimeException {

    public DuplicateTransactionConflictException() {
        super("Transaction ID was already used with a different request.");
    }
}
