package com.mendel.transactions.infrastructure;

import com.mendel.transactions.domain.SaveResult;
import com.mendel.transactions.domain.Transaction;
import com.mendel.transactions.domain.TransactionRepository;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicReference;
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
 * <p>Every structure is concurrent, so there is no global lock and writes to different identifiers
 * never contend. The index entries are {@link ConcurrentSkipListSet}s: thread safe and naturally
 * sorted, so both queries return identifiers in ascending order with no sorting on read and no
 * synchronized block anywhere.
 *
 * <p>A write does its whole index maintenance inside {@code byId.compute(...)}. {@link
 * ConcurrentHashMap} holds that key's bin for the duration of the mapping function, so "drop the
 * previous transaction from its old type and parent entries, then add the new one" is atomic for
 * that identifier: no writer can observe a half-moved entry for it.
 *
 * <p>What this deliberately does not provide is a transactional snapshot across all three maps: a
 * sum traversal running concurrently with writes may observe a tree that changed under it. That is
 * the standard trade for an in-memory store, and far cheaper than serializing every read behind a
 * global lock. The traversal carries a visited set, so a concurrent re-parent can never make it
 * loop forever.
 */
@Repository
public class InMemoryTransactionRepository implements TransactionRepository {

    private final Map<Long, Transaction> byId = new ConcurrentHashMap<>();
    private final Map<String, NavigableSet<Long>> idsByType = new ConcurrentHashMap<>();
    private final Map<Long, NavigableSet<Long>> childIdsByParent = new ConcurrentHashMap<>();

    @Override
    public SaveResult save(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        AtomicReference<SaveResult> outcome = new AtomicReference<>();

        byId.compute(transaction.id(), (id, previous) -> {
            if (previous == null) {
                add(idsByType, transaction.type(), id);
                transaction.parent().ifPresent(parentId -> add(childIdsByParent, parentId, id));
                outcome.set(SaveResult.CREATED);
            } else {
                reIndex(previous, transaction);
                outcome.set(SaveResult.REPLACED);
            }
            return transaction;
        });

        return outcome.get();
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

    /**
     * Empties the store.
     *
     * <p>Deliberately not on {@link TransactionRepository}: nothing the service does requires
     * discarding every transaction, and a port should declare what its callers need rather than
     * everything its implementation can do. It exists so tests sharing this singleton across a
     * cached Spring context can start from a known state, and they reach it by depending on this
     * class rather than on the port.
     */
    public void deleteAll() {
        byId.clear();
        idsByType.clear();
        childIdsByParent.clear();
    }

    /**
     * @return a detached snapshot of an index entry, so callers can neither mutate the index nor
     *         trip over a concurrent modification while iterating.
     */
    private static <K> List<Long> read(Map<K, NavigableSet<Long>> index, K key) {
        NavigableSet<Long> ids = index.get(key);
        return ids == null ? List.of() : List.copyOf(ids);
    }

    /**
     * Moves the identifier between index entries, but only for the keys that actually changed, so a
     * replacement that keeps its type or its parent does no index work at all.
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

    private static <K> void add(Map<K, NavigableSet<Long>> index, K key, long id) {
        index.computeIfAbsent(key, unused -> new ConcurrentSkipListSet<>()).add(id);
    }

    /** Drops the identifier, and the whole entry once it is empty, so unused keys do not accumulate. */
    private static <K> void remove(Map<K, NavigableSet<Long>> index, K key, long id) {
        index.computeIfPresent(key, (unused, ids) -> {
            ids.remove(id);
            return ids.isEmpty() ? null : ids;
        });
    }
}
