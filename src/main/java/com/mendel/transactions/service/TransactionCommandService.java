package com.mendel.transactions.service;

import com.mendel.transactions.domain.SaveResult;
import com.mendel.transactions.domain.Transaction;

/** Write side of the service: the only way a transaction enters the store. */
public interface TransactionCommandService {

    /**
     * Stores the transaction, replacing any transaction under the same identifier.
     *
     * @throws com.mendel.transactions.domain.exception.ParentNotFoundException    if the referenced
     *         parent does not exist
     * @throws com.mendel.transactions.domain.exception.CircularReferenceException if the parent link
     *         would make the transaction its own ancestor
     */
    SaveResult save(Transaction transaction);

    /**
     * Stores the transaction only if its identifier is free, which is what a client asks for by
     * sending {@code If-None-Match: *} on the PUT.
     *
     * @throws com.mendel.transactions.domain.exception.TransactionAlreadyExistsException if the
     *         identifier is already in use
     * @throws com.mendel.transactions.domain.exception.ParentNotFoundException    if the referenced
     *         parent does not exist
     * @throws com.mendel.transactions.domain.exception.CircularReferenceException if the parent link
     *         would make the transaction its own ancestor
     */
    SaveResult create(Transaction transaction);
}
