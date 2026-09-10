package com.mendel.transactions.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mendel.transactions.domain.SaveResult;
import com.mendel.transactions.domain.Transaction;
import com.mendel.transactions.domain.TransactionRepository;
import com.mendel.transactions.domain.exception.CircularReferenceException;
import com.mendel.transactions.domain.exception.ParentNotFoundException;
import com.mendel.transactions.domain.exception.TransactionNotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultTransactionService")
class DefaultTransactionServiceTest {

    @Mock
    private TransactionRepository repository;

    private DefaultTransactionService service;

    @BeforeEach
    void setUp() {
        service = new DefaultTransactionService(repository);
    }

    /** Stubs the repository so it answers as if exactly these transactions were stored. */
    private void givenStored(List<Transaction> transactions) {
        Map<Long, Transaction> byId = new LinkedHashMap<>();
        Map<Long, List<Long>> childrenByParent = new LinkedHashMap<>();
        for (Transaction transaction : transactions) {
            byId.put(transaction.id(), transaction);
            transaction.parent().ifPresent(parentId -> childrenByParent
                    .computeIfAbsent(parentId, key -> new ArrayList<>())
                    .add(transaction.id()));
        }
        lenient().when(repository.findById(anyLong()))
                .thenAnswer(call -> Optional.ofNullable(byId.get(call.<Long>getArgument(0))));
        lenient().when(repository.existsById(anyLong()))
                .thenAnswer(call -> byId.containsKey(call.<Long>getArgument(0)));
        lenient().when(repository.findChildIds(anyLong()))
                .thenAnswer(call -> childrenByParent.getOrDefault(call.<Long>getArgument(0), List.of()));
    }

    private void givenStored(Transaction... transactions) {
        givenStored(List.of(transactions));
    }

    @Nested
    @DisplayName("save")
    class Save {

        @Test
        @DisplayName("stores a root transaction and reports the repository outcome")
        void storesRootTransaction() {
            givenStored();
            Transaction root = new Transaction(10L, 5000.0, "cars", null);
            when(repository.save(root)).thenReturn(SaveResult.CREATED);

            assertThat(service.save(root)).isEqualTo(SaveResult.CREATED);
            verify(repository).save(root);
        }

        @Test
        @DisplayName("reports REPLACED when the repository replaced an existing transaction")
        void reportsReplaced() {
            givenStored(new Transaction(10L, 5000.0, "cars", null));
            Transaction replacement = new Transaction(10L, 99.0, "food", null);
            when(repository.save(replacement)).thenReturn(SaveResult.REPLACED);

            assertThat(service.save(replacement)).isEqualTo(SaveResult.REPLACED);
        }

        @Test
        @DisplayName("stores a transaction whose parent exists")
        void storesTransactionWithExistingParent() {
            givenStored(new Transaction(10L, 5000.0, "cars", null));
            Transaction child = new Transaction(11L, 10000.0, "shopping", 10L);
            when(repository.save(child)).thenReturn(SaveResult.CREATED);

            assertThat(service.save(child)).isEqualTo(SaveResult.CREATED);
        }

        @Test
        @DisplayName("rejects a transaction whose parent does not exist, leaving nothing stored")
        void rejectsMissingParent() {
            givenStored();

            assertThatThrownBy(() -> service.save(new Transaction(11L, 10000.0, "shopping", 99L)))
                    .isInstanceOf(ParentNotFoundException.class)
                    .hasMessageContaining("99");
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("rejects a transaction that is its own parent")
        void rejectsSelfParent() {
            givenStored(new Transaction(10L, 5000.0, "cars", null));

            assertThatThrownBy(() -> service.save(new Transaction(10L, 5000.0, "cars", 10L)))
                    .isInstanceOf(CircularReferenceException.class);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("rejects a self parent even when the identifier is not stored yet")
        void rejectsSelfParentOnUnknownIdentifier() {
            givenStored();

            assertThatThrownBy(() -> service.save(new Transaction(10L, 5000.0, "cars", 10L)))
                    .isInstanceOf(CircularReferenceException.class);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("rejects re-parenting a transaction under its own direct child")
        void rejectsReParentingUnderDirectChild() {
            givenStored(
                    new Transaction(10L, 5000.0, "cars", null),
                    new Transaction(11L, 10000.0, "shopping", 10L));

            assertThatThrownBy(() -> service.save(new Transaction(10L, 5000.0, "cars", 11L)))
                    .isInstanceOf(CircularReferenceException.class);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("rejects re-parenting a transaction under a deeper descendant")
        void rejectsReParentingUnderDeeperDescendant() {
            givenStored(
                    new Transaction(10L, 5000.0, "cars", null),
                    new Transaction(11L, 10000.0, "shopping", 10L),
                    new Transaction(12L, 5000.0, "shopping", 11L));

            assertThatThrownBy(() -> service.save(new Transaction(10L, 5000.0, "cars", 12L)))
                    .isInstanceOf(CircularReferenceException.class);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("allows re-parenting under a transaction outside its own subtree")
        void allowsReParentingOutsideOwnSubtree() {
            givenStored(
                    new Transaction(10L, 5000.0, "cars", null),
                    new Transaction(11L, 10000.0, "shopping", 10L),
                    new Transaction(20L, 1.0, "cars", null));
            Transaction reParented = new Transaction(11L, 10000.0, "shopping", 20L);
            when(repository.save(reParented)).thenReturn(SaveResult.REPLACED);

            assertThatCode(() -> service.save(reParented)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects a null transaction")
        void rejectsNull() {
            assertThatThrownBy(() -> service.save(null)).isInstanceOf(NullPointerException.class);
            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("findIdsByType")
    class FindIdsByType {

        @Test
        @DisplayName("delegates to the repository")
        void delegatesToRepository() {
            when(repository.findIdsByType("cars")).thenReturn(List.of(10L, 20L));

            assertThat(service.findIdsByType("cars")).containsExactly(10L, 20L);
        }

        @Test
        @DisplayName("returns an empty list for an unknown type rather than failing")
        void returnsEmptyListForUnknownType() {
            when(repository.findIdsByType("unknown")).thenReturn(List.of());

            assertThat(service.findIdsByType("unknown")).isEmpty();
        }
    }

    @Nested
    @DisplayName("sumLinkedTo")
    class SumLinkedTo {

        @Test
        @DisplayName("fails when the transaction does not exist")
        void failsForUnknownTransaction() {
            givenStored();

            assertThatThrownBy(() -> service.sumLinkedTo(404L))
                    .isInstanceOf(TransactionNotFoundException.class)
                    .hasMessageContaining("404");
        }

        @Test
        @DisplayName("a leaf sums to its own amount")
        void leafSumsToOwnAmount() {
            givenStored(new Transaction(10L, 5000.0, "cars", null));

            assertThat(service.sumLinkedTo(10L)).isEqualTo(5000.0);
        }

        @Test
        @DisplayName("sums the whole subtree, per the challenge example")
        void sumsWholeSubtree() {
            givenStored(
                    new Transaction(10L, 5000.0, "cars", null),
                    new Transaction(11L, 10000.0, "shopping", 10L),
                    new Transaction(12L, 5000.0, "shopping", 11L));

            assertThat(service.sumLinkedTo(10L)).isEqualTo(20000.0);
            assertThat(service.sumLinkedTo(11L)).isEqualTo(15000.0);
            assertThat(service.sumLinkedTo(12L)).isEqualTo(5000.0);
        }

        @Test
        @DisplayName("sums across several branches")
        void sumsAcrossSeveralBranches() {
            givenStored(
                    new Transaction(1L, 1.0, "root", null),
                    new Transaction(2L, 2.0, "branch", 1L),
                    new Transaction(3L, 4.0, "branch", 1L),
                    new Transaction(4L, 8.0, "leaf", 2L),
                    new Transaction(5L, 16.0, "leaf", 3L));

            assertThat(service.sumLinkedTo(1L)).isEqualTo(31.0);
            assertThat(service.sumLinkedTo(2L)).isEqualTo(10.0);
            assertThat(service.sumLinkedTo(3L)).isEqualTo(20.0);
        }

        @Test
        @DisplayName("does not include ancestors or unrelated transactions")
        void excludesAncestorsAndUnrelated() {
            givenStored(
                    new Transaction(10L, 5000.0, "cars", null),
                    new Transaction(11L, 10000.0, "shopping", 10L),
                    new Transaction(20L, 777.0, "other", null));

            assertThat(service.sumLinkedTo(11L)).isEqualTo(10000.0);
        }

        @Test
        @DisplayName("handles a chain far deeper than the stack, so traversal must be iterative")
        void handlesVeryDeepChain() {
            int depth = 10_000;
            List<Transaction> chain = new ArrayList<>(depth);
            chain.add(new Transaction(0L, 1.0, "chain", null));
            for (long id = 1; id < depth; id++) {
                chain.add(new Transaction(id, 1.0, "chain", id - 1));
            }
            givenStored(chain);

            assertThat(service.sumLinkedTo(0L)).isEqualTo(depth);
        }
    }
}
