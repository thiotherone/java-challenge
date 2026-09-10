package com.mendel.transactions.application;

import java.util.List;

/** Read side of the service. Split from the write side so readers do not depend on write rules. */
public interface TransactionQueryService {

    /** @return identifiers of every transaction of that exact type; empty when the type is unknown. */
    List<Long> findIdsByType(String type);

    /**
     * Sums the amounts of every transaction transitively linked to this one through {@code parent_id},
     * including the transaction itself.
     *
     * @throws com.mendel.transactions.domain.exception.TransactionNotFoundException if the transaction
     *         does not exist
     */
    double sumLinkedTo(long transactionId);
}
