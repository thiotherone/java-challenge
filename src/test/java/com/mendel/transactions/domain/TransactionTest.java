package com.mendel.transactions.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Transaction")
class TransactionTest {

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("rejects a null type")
        void rejectsNullType() {
            assertThatThrownBy(() -> new Transaction(1L, 100.0, null, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("type");
        }

        @ParameterizedTest(name = "rejects a blank type: [{0}]")
        @ValueSource(strings = {"", " ", "\t", "\n"})
        void rejectsBlankType(String blank) {
            assertThatThrownBy(() -> new Transaction(1L, 100.0, blank, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("type");
        }

        @ParameterizedTest(name = "rejects a non-finite amount: {0}")
        @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
        void rejectsNonFiniteAmount(double amount) {
            assertThatThrownBy(() -> new Transaction(1L, amount, "cars", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("amount");
        }

        @Test
        @DisplayName("accepts a negative amount, since a transaction may be an outflow")
        void acceptsNegativeAmount() {
            assertThat(new Transaction(1L, -5000.0, "cars", null).amount()).isEqualTo(-5000.0);
        }

        @Test
        @DisplayName("accepts a zero amount")
        void acceptsZeroAmount() {
            assertThat(new Transaction(1L, 0.0, "cars", null).amount()).isZero();
        }
    }

    @Nested
    @DisplayName("parent link")
    class ParentLink {

        @Test
        @DisplayName("a transaction without parent_id is a root")
        void rootHasNoParent() {
            Transaction root = new Transaction(10L, 5000.0, "cars", null);

            assertThat(root.hasParent()).isFalse();
            assertThat(root.parent()).isEmpty();
        }

        @Test
        @DisplayName("a transaction with parent_id exposes it")
        void childExposesParent() {
            Transaction child = new Transaction(11L, 10000.0, "shopping", 10L);

            assertThat(child.hasParent()).isTrue();
            assertThat(child.parent()).contains(10L);
        }
    }
}
