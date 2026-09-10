package com.mendel.transactions.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mendel.transactions.domain.Transaction;
import com.mendel.transactions.infrastructure.InMemoryTransactionRepository;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;

/**
 * The service against the real store, on a chain far longer than anything the other tests build.
 *
 * <p>Two properties are at stake, and neither shows up at small sizes: appending to a chain must
 * not cost more as the chain grows, and summing one must not recurse. The timeouts are what make
 * the first property a test rather than a comment — a quadratic append passes every correctness
 * assertion and simply takes forever.
 */
@DisplayName("Deep chains")
class DeepChainTest {

    private static final int LENGTH = 100_000;

    private DefaultTransactionService service;

    @BeforeEach
    void setUp() {
        service = new DefaultTransactionService(new InMemoryTransactionRepository());
    }

    /** Builds 0 → 1 → 2 → … each holding an amount of 1, so the total is the length. */
    private void buildChain() {
        service.create(new Transaction(0L, 1.0, "chain", null));
        for (long id = 1; id < LENGTH; id++) {
            service.create(new Transaction(id, 1.0, "chain", id - 1));
        }
    }

    @Test
    @Timeout(value = 30, threadMode = ThreadMode.SEPARATE_THREAD)
    @DisplayName("appending stays affordable as the chain grows, so building one is not quadratic")
    void appendingStaysAffordable() {
        assertThat(Duration.ofNanos(timed(this::buildChain)))
                .isLessThan(Duration.ofSeconds(30));
        assertThat(service.findIdsByType("chain")).hasSize(LENGTH);
    }

    @Test
    @Timeout(value = 30, threadMode = ThreadMode.SEPARATE_THREAD)
    @DisplayName("summing the root walks the whole chain without recursing into a stack overflow")
    void summingDoesNotOverflowTheStack() {
        buildChain();

        assertThat(service.sumLinkedTo(0L)).isEqualTo(LENGTH);
        assertThat(service.sumLinkedTo(LENGTH - 1)).isEqualTo(1.0);
        assertThat(service.sumLinkedTo(LENGTH / 2)).isEqualTo(LENGTH / 2.0);
    }

    private static long timed(Runnable work) {
        long start = System.nanoTime();
        work.run();
        return System.nanoTime() - start;
    }
}
