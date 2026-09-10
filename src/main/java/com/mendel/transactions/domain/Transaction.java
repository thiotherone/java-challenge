package com.mendel.transactions.domain;

import java.util.Optional;

/**
 * A transaction held by the service.
 *
 * <p>The identifier is chosen by the client (the API exposes it as the path of a {@code PUT}),
 * so it is part of the value rather than something the store assigns.
 *
 * @param id       client-chosen identifier
 * @param amount   monetary amount; may be negative, must be finite
 * @param type     non-blank classifier used by the "find by type" query
 * @param parentId identifier of the parent transaction, or {@code null} when this is a root
 */
public record Transaction(long id, double amount, String type, Long parentId) {

    /** @return the parent identifier, empty when this transaction is a root. */
    public Optional<Long> parent() {
        return Optional.ofNullable(parentId);
    }

    /** @return whether this transaction is linked to a parent. */
    public boolean hasParent() {
        return parentId != null;
    }
}
