package com.mendel.transactions.domain.exception;

/** Base type for domain rule violations, carrying the stable code exposed by the API. */
public abstract class TransactionException extends RuntimeException {

    protected TransactionException(String message) {
        super(message);
    }

    /** @return machine-readable code returned to API clients. */
    public abstract String code();
}
