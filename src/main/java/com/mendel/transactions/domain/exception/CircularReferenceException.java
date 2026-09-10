package com.mendel.transactions.domain.exception;

/**
 * Raised when a write would make a transaction its own ancestor, which would turn the parent links
 * into a cycle and make the "sum of everything linked" query non-terminating.
 */
public class CircularReferenceException extends TransactionException {

    private final long id;
    private final long parentId;

    public CircularReferenceException(long id, long parentId) {
        super("transaction " + id + " cannot have parent_id " + parentId
                + " because that would create a cycle");
        this.id = id;
        this.parentId = parentId;
    }

    public long id() {
        return id;
    }

    public long parentId() {
        return parentId;
    }

    @Override
    public String code() {
        return "CIRCULAR_REFERENCE";
    }
}
