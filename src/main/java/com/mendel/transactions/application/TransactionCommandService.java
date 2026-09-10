package com.mendel.transactions.application;

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
}
