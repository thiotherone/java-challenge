package com.mendel.transactions.infrastructure.persistence;

import com.mendel.transactions.domain.SaveResult;
import com.mendel.transactions.domain.Transaction;
import com.mendel.transactions.domain.TransactionRepository;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * In-memory {@link TransactionRepository}.
 *
 * <p>Besides the transactions themselves it maintains two secondary indexes, so neither query has to
 * scan the whole store: identifiers by type, and child identifiers by parent. The child index is
 * what lets the "sum everything linked" query walk a subtree directly instead of repeatedly
 * filtering every transaction by its parent.
 *
 * <h2>Concurrency</h2>
 *
 * <p>Writes are already serialized upstream: the command service is the only write path and its
 * save is synchronized, because validating a parent link and storing it must be one step. So the
 * store's job is to let readers run concurrently with that single writer without blocking, which is
 * what the {@link ConcurrentHashMap} backing gives: every read sees a complete, safely published
 * value rather than a torn one.
 *
 * <p>The index entries are insertion-ordered sets, so both queries return identifiers in the order
 * the transactions were first stored. They are wrapped with
 * {@link Collections#synchronizedSet(Set)} because {@link LinkedHashSet} itself is not thread safe,
 * and iteration over such a set has to hold its monitor, which {@link #read(Map, Object)} does while
 * taking its snapshot.
 *
 * <p>What this deliberately does not provide is one atomic instant across all three maps: a reader
 * racing a replacement may briefly see the new transaction under an index entry still being moved.
 * Every read is individually consistent and the window closes within a single write, which is the
 * right trade for a store whose reads vastly outnumber its writes.
 */
@Repository
public class InMemoryTransactionRepository implements TransactionRepository {

    private final Map<Long, Transaction> byId = new ConcurrentHashMap<>();
    private final Map<String, Set<Long>> idsByType = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> childIdsByParent = new ConcurrentHashMap<>();

    @Override
    public SaveResult save(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        Transaction previous = byId.put(transaction.id(), transaction);
        if (previous == null) {
            add(idsByType, transaction.type(), transaction.id());
            transaction.parent().ifPresent(parentId -> add(childIdsByParent, parentId, transaction.id()));
            return SaveResult.CREATED;
        }
        reIndex(previous, transaction);
        return SaveResult.REPLACED;
    }

    @Override
    public Optional<Transaction> findById(long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public boolean existsById(long id) {
        return byId.containsKey(id);
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
        byId.clear();
        idsByType.clear();
        childIdsByParent.clear();
    }

    /** @return a detached snapshot of an index entry, taken under that entry's monitor. */
    private static <K> List<Long> read(Map<K, Set<Long>> index, K key) {
        Set<Long> ids = index.get(key);
        if (ids == null) {
            return List.of();
        }
        synchronized (ids) {
            return List.copyOf(ids);
        }
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
        index.compute(key, (unused, ids) -> {
            Set<Long> entry = ids == null
                    ? Collections.synchronizedSet(new LinkedHashSet<>())
                    : ids;
            entry.add(id);
            return entry;
        });
    }

    /** Drops the identifier, and the whole entry once it is empty, so unused keys do not accumulate. */
    private static <K> void remove(Map<K, Set<Long>> index, K key, long id) {
        index.computeIfPresent(key, (unused, ids) -> {
            ids.remove(id);
            return ids.isEmpty() ? null : ids;
        });
    }
}
