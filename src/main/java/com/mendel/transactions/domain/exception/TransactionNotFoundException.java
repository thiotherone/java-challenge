package com.mendel.transactions.domain.exception;

/** Raised when a query targets a transaction that was never stored. */
public class TransactionNotFoundException extends TransactionException {

    private final long id;

    public TransactionNotFoundException(long id) {
        super("transaction " + id + " does not exist");
        this.id = id;
    }

    public long id() {
        return id;
    }

    @Override
    public String code() {
        return "TRANSACTION_NOT_FOUND";
    }
}
