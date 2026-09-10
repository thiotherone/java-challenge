package com.mendel.transactions.service;

import com.mendel.transactions.domain.SaveResult;
import com.mendel.transactions.domain.Transaction;
import com.mendel.transactions.domain.TransactionRepository;
import com.mendel.transactions.domain.exception.CircularReferenceException;
import com.mendel.transactions.domain.exception.ParentNotFoundException;
import com.mendel.transactions.domain.exception.TransactionAlreadyExistsException;
import com.mendel.transactions.domain.exception.TransactionNotFoundException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Default implementation of both sides of the service.
 *
 * <p>It owns the two rules the store itself cannot enforce: a parent must already exist, and a
 * parent link must never make a transaction its own ancestor. Together they keep the parent links a
 * forest, which is what makes {@link #sumLinkedTo(long)} terminate.
 */
@Service
public class DefaultTransactionService implements TransactionCommandService, TransactionQueryService {

    private final TransactionRepository repository;

    public DefaultTransactionService(TransactionRepository repository) {
        this.repository = repository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Synchronized because validating the parent link and storing the transaction have to happen
     * as one step: two concurrent writes that each re-parent one end of a chain would otherwise both
     * pass validation and close a cycle between them. This is the only write path into the store, so
     * serializing it is enough.
     */
    @Override
    public synchronized SaveResult save(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        transaction.parent().ifPresent(parentId -> validateParent(transaction.id(), parentId));
        return repository.save(transaction);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Synchronized for the same reason as {@link #save(Transaction)}, and here it additionally
     * makes "is the identifier free?" and the write itself one step, so two concurrent conditional
     * creates cannot both find the identifier free.
     */
    @Override
    public synchronized SaveResult create(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        if (repository.existsById(transaction.id())) {
            throw new TransactionAlreadyExistsException(transaction.id());
        }
        transaction.parent().ifPresent(parentId -> validateParent(transaction.id(), parentId));
        return repository.save(transaction);
    }

    @Override
    public List<Long> findIdsByType(String type) {
        return repository.findIdsByType(type);
    }

    @Override
    public double sumLinkedTo(long transactionId) {
        Transaction root = repository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        // Iterative on purpose: chains can be far deeper than the stack allows to recurse.
        double sum = 0;
        Deque<Transaction> pending = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        pending.push(root);
        visited.add(root.id());
        while (!pending.isEmpty()) {
            Transaction current = pending.pop();
            sum += current.amount();
            for (Long childId : repository.findChildIds(current.id())) {
                if (visited.add(childId)) {
                    repository.findById(childId).ifPresent(pending::push);
                }
            }
        }
        return sum;
    }

    private void validateParent(long id, long parentId) {
        if (parentId == id) {
            throw new CircularReferenceException(id, parentId);
        }
        if (!repository.existsById(parentId)) {
            throw new ParentNotFoundException(parentId);
        }
        if (isDescendantOf(parentId, id)) {
            throw new CircularReferenceException(id, parentId);
        }
    }

    /**
     * Walks up the parent chain from {@code candidateId}, which is cheaper than walking down the
     * whole subtree of {@code ancestorId}. The visited set only guards against a malformed store:
     * the links are a forest, so the walk already terminates at a root.
     */
    private boolean isDescendantOf(long candidateId, long ancestorId) {
        Set<Long> visited = new HashSet<>();
        Long current = candidateId;
        while (current != null && visited.add(current)) {
            if (current == ancestorId) {
                return true;
            }
            current = repository.findById(current)
                    .map(Transaction::parentId)
                    .orElse(null);
        }
        return false;
    }
}
