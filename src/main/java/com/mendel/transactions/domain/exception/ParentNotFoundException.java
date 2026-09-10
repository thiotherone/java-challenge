package com.mendel.transactions.domain.exception;

/** Raised when a write references a parent that does not exist, which would leave a dangling link. */
public class ParentNotFoundException extends TransactionException {

    private final long parentId;

    public ParentNotFoundException(long parentId) {
        super("parent_id " + parentId + " does not exist");
        this.parentId = parentId;
    }

    public long parentId() {
        return parentId;
    }

    @Override
    public String code() {
        return "PARENT_NOT_FOUND";
    }
}
