package com.mendel.transactions.infrastructure.persistence;

import com.mendel.transactions.domain.SaveResult;
import com.mendel.transactions.domain.Transaction;
import com.mendel.transactions.domain.TransactionRepository;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.stereotype.Repository;

/**
 * In-memory {@link TransactionRepository}.
 *
 * <p>Besides the transactions themselves it maintains two secondary indexes, so neither query has to
 * scan the whole store: identifiers by type, and child identifiers by parent. The child index is
 * what lets the "sum everything linked" query walk a subtree directly instead of repeatedly
 * filtering every transaction by its parent.
 *
 * <p>A write has to update the store and both indexes together, so all three live behind one
 * {@link ReadWriteLock}: readers never observe a half-applied re-index, and writers are serialized.
 * Ordinary {@link HashMap} and {@link LinkedHashSet} are therefore safe here, and the linked sets
 * keep the query results in insertion order.
 */
@Repository
public class InMemoryTransactionRepository implements TransactionRepository {

    private final Map<Long, Transaction> byId = new HashMap<>();
    private final Map<String, Set<Long>> idsByType = new HashMap<>();
    private final Map<Long, Set<Long>> childIdsByParent = new HashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    @Override
    public SaveResult save(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        lock.writeLock().lock();
        try {
            Transaction previous = byId.put(transaction.id(), transaction);
            if (previous == null) {
                addToIndexes(transaction);
                return SaveResult.CREATED;
            }
            reIndex(previous, transaction);
            return SaveResult.REPLACED;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<Transaction> findById(long id) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(byId.get(id));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean existsById(long id) {
        lock.readLock().lock();
        try {
            return byId.containsKey(id);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<Long> findIdsByType(String type) {
        return read(idsByType, type);
    }

    @Override
    public List<Long> findChildIds(long parentId) {
        return read(childIdsByParent, parentId);
    }

    @Override
    public void deleteAll() {
        lock.writeLock().lock();
        try {
            byId.clear();
            idsByType.clear();
            childIdsByParent.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Copies the index entry out under the read lock, so callers hold a detached snapshot. */
    private <K> List<Long> read(Map<K, Set<Long>> index, K key) {
        lock.readLock().lock();
        try {
            Set<Long> ids = index.get(key);
            return ids == null ? List.of() : List.copyOf(ids);
        } finally {
            lock.readLock().unlock();
        }
    }

    private void addToIndexes(Transaction transaction) {
        add(idsByType, transaction.type(), transaction.id());
        transaction.parent().ifPresent(parentId -> add(childIdsByParent, parentId, transaction.id()));
    }

    /**
     * Moves the identifier between index entries, but only for the keys that actually changed, so a
     * replacement that keeps its type or its parent also keeps its place in the query results.
     */
    private void reIndex(Transaction previous, Transaction current) {
        long id = current.id();
        if (!previous.type().equals(current.type())) {
            remove(idsByType, previous.type(), id);
            add(idsByType, current.type(), id);
        }
        if (!Objects.equals(previous.parentId(), current.parentId())) {
            previous.parent().ifPresent(parentId -> remove(childIdsByParent, parentId, id));
            current.parent().ifPresent(parentId -> add(childIdsByParent, parentId, id));
        }
    }

    private static <K> void add(Map<K, Set<Long>> index, K key, long id) {
        index.computeIfAbsent(key, unused -> new LinkedHashSet<>()).add(id);
    }

    private static <K> void remove(Map<K, Set<Long>> index, K key, long id) {
        Set<Long> ids = index.get(key);
        if (ids != null && ids.remove(id) && ids.isEmpty()) {
            index.remove(key);
        }
    }
}
