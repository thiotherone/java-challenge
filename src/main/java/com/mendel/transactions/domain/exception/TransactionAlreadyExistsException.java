package com.mendel.transactions.domain.exception;

/**
 * Raised when a client asked to create a transaction only if the identifier was free, using
 * {@code If-None-Match: *}, and the identifier turned out to be taken.
 */
public class TransactionAlreadyExistsException extends TransactionException {

    private final long id;

    public TransactionAlreadyExistsException(long id) {
        super("transaction " + id + " already exists");
        this.id = id;
    }

    public long id() {
        return id;
    }

    @Override
    public String code() {
        return "TRANSACTION_ALREADY_EXISTS";
    }
}
