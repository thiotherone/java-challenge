package com.mendel.transactions.domain;

/** Outcome of a write, so callers can distinguish an insert from a replacement. */
public enum SaveResult {
    /** No transaction existed under that identifier. */
    CREATED,
    /** An existing transaction was replaced. */
    REPLACED
}
