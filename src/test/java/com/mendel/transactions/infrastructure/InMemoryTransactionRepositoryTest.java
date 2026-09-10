package com.mendel.transactions.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mendel.transactions.domain.SaveResult;
import com.mendel.transactions.domain.Transaction;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("InMemoryTransactionRepository")
class InMemoryTransactionRepositoryTest {

    private InMemoryTransactionRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTransactionRepository();
    }

    @Nested
    @DisplayName("save")
    class Save {

        @Test
        @DisplayName("reports CREATED when the identifier is free")
        void createsWhenIdentifierIsFree() {
            assertThat(repository.save(new Transaction(10L, 5000.0, "cars", null)))
                    .isEqualTo(SaveResult.CREATED);
        }

        @Test
        @DisplayName("reports REPLACED when the identifier is taken")
        void replacesWhenIdentifierIsTaken() {
            repository.save(new Transaction(10L, 5000.0, "cars", null));

            assertThat(repository.save(new Transaction(10L, 99.0, "food", null)))
                    .isEqualTo(SaveResult.REPLACED);
        }

        @Test
        @DisplayName("a replacement overwrites the stored value")
        void replacementOverwritesStoredValue() {
            repository.save(new Transaction(10L, 5000.0, "cars", null));
            repository.save(new Transaction(10L, 99.0, "food", null));

            assertThat(repository.findById(10L))
                    .contains(new Transaction(10L, 99.0, "food", null));
        }

        @Test
        @DisplayName("rejects a null transaction")
        void rejectsNull() {
            assertThatThrownBy(() -> repository.save(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("findById / existsById")
    class Lookup {

        @Test
        @DisplayName("returns the stored transaction")
        void returnsStoredTransaction() {
            Transaction stored = new Transaction(10L, 5000.0, "cars", null);
            repository.save(stored);

            assertThat(repository.findById(10L)).contains(stored);
            assertThat(repository.existsById(10L)).isTrue();
        }

        @Test
        @DisplayName("returns empty for an unknown identifier")
        void returnsEmptyForUnknownIdentifier() {
            assertThat(repository.findById(404L)).isEmpty();
            assertThat(repository.existsById(404L)).isFalse();
        }
    }

    @Nested
    @DisplayName("findIdsByType")
    class FindIdsByType {

        @Test
        @DisplayName("returns the identifiers of that type in ascending order, whatever the write order")
        void returnsIdentifiersInAscendingOrder() {
            repository.save(new Transaction(30L, 1.0, "shopping", null));
            repository.save(new Transaction(10L, 5000.0, "cars", null));
            repository.save(new Transaction(20L, 2.0, "shopping", null));

            assertThat(repository.findIdsByType("shopping")).containsExactly(20L, 30L);
            assertThat(repository.findIdsByType("cars")).containsExactly(10L);
        }

        @Test
        @DisplayName("returns an empty list for an unknown type")
        void returnsEmptyListForUnknownType() {
            assertThat(repository.findIdsByType("unknown")).isEmpty();
        }

        @Test
        @DisplayName("matches the type exactly, case included")
        void matchesTypeExactly() {
            repository.save(new Transaction(10L, 5000.0, "cars", null));

            assertThat(repository.findIdsByType("CARS")).isEmpty();
            assertThat(repository.findIdsByType("Cars")).isEmpty();
        }

        @Test
        @DisplayName("re-indexes when a replacement changes the type")
        void reIndexesWhenReplacementChangesType() {
            repository.save(new Transaction(10L, 5000.0, "cars", null));
            repository.save(new Transaction(10L, 5000.0, "food", null));

            assertThat(repository.findIdsByType("cars")).isEmpty();
            assertThat(repository.findIdsByType("food")).containsExactly(10L);
        }

        @Test
        @DisplayName("keeps the index intact when a replacement keeps the type")
        void keepsIndexWhenReplacementKeepsType() {
            repository.save(new Transaction(10L, 5000.0, "cars", null));
            repository.save(new Transaction(10L, 42.0, "cars", null));

            assertThat(repository.findIdsByType("cars")).containsExactly(10L);
        }

        @Test
        @DisplayName("returns a detached list that callers cannot use to mutate the index")
        void returnsDetachedList() {
            repository.save(new Transaction(10L, 5000.0, "cars", null));

            List<Long> ids = repository.findIdsByType("cars");
            assertThatThrownBy(() -> ids.add(999L)).isInstanceOf(UnsupportedOperationException.class);
            assertThat(repository.findIdsByType("cars")).containsExactly(10L);
        }
    }

    @Nested
    @DisplayName("findChildIds")
    class FindChildIds {

        @Test
        @DisplayName("returns the direct children in ascending order")
        void returnsDirectChildren() {
            repository.save(new Transaction(10L, 5000.0, "cars", null));
            repository.save(new Transaction(13L, 1.0, "shopping", 10L));
            repository.save(new Transaction(11L, 10000.0, "shopping", 10L));
            repository.save(new Transaction(12L, 5000.0, "shopping", 11L));

            assertThat(repository.findChildIds(10L)).containsExactly(11L, 13L);
            assertThat(repository.findChildIds(11L)).containsExactly(12L);
        }

        @Test
        @DisplayName("returns an empty list for a leaf and for an unknown identifier")
        void returnsEmptyListForLeafAndUnknown() {
            repository.save(new Transaction(10L, 5000.0, "cars", null));

            assertThat(repository.findChildIds(10L)).isEmpty();
            assertThat(repository.findChildIds(404L)).isEmpty();
        }

        @Test
        @DisplayName("re-indexes when a replacement changes the parent")
        void reIndexesWhenReplacementChangesParent() {
            repository.save(new Transaction(10L, 5000.0, "cars", null));
            repository.save(new Transaction(20L, 1.0, "cars", null));
            repository.save(new Transaction(11L, 10000.0, "shopping", 10L));

            repository.save(new Transaction(11L, 10000.0, "shopping", 20L));

            assertThat(repository.findChildIds(10L)).isEmpty();
            assertThat(repository.findChildIds(20L)).containsExactly(11L);
        }

        @Test
        @DisplayName("drops the link when a replacement removes the parent")
        void dropsLinkWhenReplacementRemovesParent() {
            repository.save(new Transaction(10L, 5000.0, "cars", null));
            repository.save(new Transaction(11L, 10000.0, "shopping", 10L));

            repository.save(new Transaction(11L, 10000.0, "shopping", null));

            assertThat(repository.findChildIds(10L)).isEmpty();
        }

        @Test
        @DisplayName("adds the link when a replacement introduces a parent")
        void addsLinkWhenReplacementIntroducesParent() {
            repository.save(new Transaction(10L, 5000.0, "cars", null));
            repository.save(new Transaction(11L, 10000.0, "shopping", null));

            repository.save(new Transaction(11L, 10000.0, "shopping", 10L));

            assertThat(repository.findChildIds(10L)).containsExactly(11L);
        }

        @Test
        @DisplayName("returns a detached list")
        void returnsDetachedList() {
            repository.save(new Transaction(10L, 5000.0, "cars", null));
            repository.save(new Transaction(11L, 1.0, "shopping", 10L));

            List<Long> children = repository.findChildIds(10L);
            assertThatThrownBy(() -> children.add(999L))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("deleteAll")
    class DeleteAll {

        @Test
        @DisplayName("clears the transactions and every secondary index")
        void clearsEverything() {
            repository.save(new Transaction(10L, 5000.0, "cars", null));
            repository.save(new Transaction(11L, 10000.0, "shopping", 10L));

            repository.deleteAll();

            assertThat(repository.findById(10L)).isEmpty();
            assertThat(repository.findIdsByType("cars")).isEmpty();
            assertThat(repository.findChildIds(10L)).isEmpty();
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("keeps every write visible under concurrent saves of distinct identifiers")
        void keepsEveryConcurrentWriteVisible() throws Exception {
            int writes = 500;
            try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
                List<Callable<SaveResult>> tasks = IntStream.range(0, writes)
                        .<Callable<SaveResult>>mapToObj(i ->
                                () -> repository.save(new Transaction(i, 1.0, "cars", null)))
                        .toList();

                pool.invokeAll(tasks);
                pool.shutdown();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(repository.findIdsByType("cars")).hasSize(writes);
            IntStream.range(0, writes)
                    .forEach(i -> assertThat(repository.existsById(i)).isTrue());
        }

        /*
         * The race this pins: one write empties an index entry and drops it, while a write to a
         * different identifier adds to that same entry. If the add lands in the set after it was
         * dropped from the map, the identifier is stored but missing from the index. Each round
         * gets its own type so every removal empties its entry, and the two writes of a round are
         * submitted back to back so they tend to run at the same moment. One round rarely hits the
         * window; thousands of them do, reliably, against an index that adds outside the entry's
         * own compute.
         */
        @Test
        @DisplayName("never loses an identifier that joins an index entry another write is emptying")
        void neverLosesAnIdentifierJoiningAnEntryBeingEmptied() throws Exception {
            int rounds = 20_000;
            IntStream.range(0, rounds)
                    .forEach(r -> repository.save(new Transaction(leaving(r), 1.0, "type-" + r, null)));

            try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
                List<Callable<SaveResult>> tasks = IntStream.range(0, rounds)
                        .boxed()
                        .<Callable<SaveResult>>mapMulti((r, next) -> {
                            next.accept(() -> repository.save(
                                    new Transaction(leaving(r), 1.0, "elsewhere", null)));
                            next.accept(() -> repository.save(
                                    new Transaction(joining(r), 1.0, "type-" + r, null)));
                        })
                        .toList();

                pool.invokeAll(tasks);
                pool.shutdown();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            }

            List<Long> lost = IntStream.range(0, rounds)
                    .filter(r -> !repository.findIdsByType("type-" + r).equals(List.of(joining(r))))
                    .mapToObj(r -> joining(r))
                    .toList();
            assertThat(lost).isEmpty();
        }

        private static long leaving(int round) {
            return 2L * round;
        }

        private static long joining(int round) {
            return 2L * round + 1;
        }
    }
}
