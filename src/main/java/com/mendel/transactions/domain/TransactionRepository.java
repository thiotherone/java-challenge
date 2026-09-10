package com.mendel.transactions.domain;

import java.util.List;
import java.util.Optional;

/**
 * Storage port for transactions.
 *
 * <p>Implementations own the secondary indexes that make {@link #findIdsByType(String)} and
 * {@link #findChildIds(long)} cheap, and must keep them consistent when {@link #save(Transaction)}
 * replaces a transaction whose type or parent changed.
 */
public interface TransactionRepository {

    /**
     * Inserts the transaction, replacing any transaction already stored under the same identifier.
     *
     * @return {@link SaveResult#CREATED} on insert, {@link SaveResult#REPLACED} on replacement
     */
    SaveResult save(Transaction transaction);

    Optional<Transaction> findById(long id);

    boolean existsById(long id);

    /** @return identifiers of every transaction of that exact type, in ascending order. */
    List<Long> findIdsByType(String type);

    /** @return identifiers of the direct children of that transaction, in ascending order. */
    List<Long> findChildIds(long parentId);

    /** Removes every transaction. Primarily a test seam for the in-memory store. */
    void deleteAll();
}
