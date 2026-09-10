package com.mendel.transactions.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.mendel.transactions.infrastructure.InMemoryTransactionRepository;
import com.mendel.transactions.api.dto.StatusResponse;
import com.mendel.transactions.api.dto.SumResponse;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * End-to-end test over real HTTP against a server on a random port, walking the exact scenario
 * printed in the challenge specification.
 *
 * <p>Where {@code TransactionApiIntegrationTest} exercises the contract through the servlet stack in
 * process, this one proves the application also behaves once packaged behind a real connector.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@DisplayName("Transactions API over HTTP")
class TransactionHttpEndToEndTest {

    private static final ParameterizedTypeReference<List<Long>> ID_LIST =
            new ParameterizedTypeReference<>() {};

    // The concrete adapter, not the port: resetting the store is not something the port offers,
    // and these tests are already tied to in-memory semantics.
    @Autowired
    private InMemoryTransactionRepository repository;

    private RestTestClient client;

    @BeforeEach
    void setUp(@LocalServerPort int port) {
        repository.deleteAll();
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private RestTestClient.ResponseSpec putTransaction(long id, String json) {
        return client.put()
                .uri("/transactions/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .exchange();
    }

    @Test
    @DisplayName("walks the scenario from the specification")
    void walksSpecificationScenario() {
        putTransaction(10, """
                {"amount": 5000, "type": "cars"}""")
                .expectStatus().isCreated()
                .expectBody(StatusResponse.class).isEqualTo(StatusResponse.ok());

        putTransaction(11, """
                {"amount": 10000, "type": "shopping", "parent_id": 10}""")
                .expectStatus().isCreated();

        putTransaction(12, """
                {"amount": 5000, "type": "shopping", "parent_id": 11}""")
                .expectStatus().isCreated();

        client.get().uri("/transactions/types/cars").exchange()
                .expectStatus().isOk()
                .expectBody(ID_LIST).isEqualTo(List.of(10L));

        client.get().uri("/transactions/sum/10").exchange()
                .expectStatus().isOk()
                .expectBody(SumResponse.class).isEqualTo(new SumResponse(20000.0));

        client.get().uri("/transactions/sum/11").exchange()
                .expectStatus().isOk()
                .expectBody(SumResponse.class).isEqualTo(new SumResponse(15000.0));
    }

    @Test
    @DisplayName("serves the documented error contract over the wire")
    void servesErrorContract() {
        client.get().uri("/transactions/sum/404").exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo("urn:mendel:transactions:transaction-not-found")
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.detail").exists();

        putTransaction(11, """
                {"amount": 10000, "type": "shopping", "parent_id": 99}""")
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.type").isEqualTo("urn:mendel:transactions:parent-not-found");
    }

    @Test
    @DisplayName("stores every write when many clients PUT at once, and sums them correctly")
    void handlesConcurrentWrites() throws Exception {
        int clients = 8;
        int writesPerClient = 1_000;
        int total = clients * writesPerClient + 1;   // the root counts toward its own subtree

        putTransaction(0, """
                {"amount": 1, "type": "root"}""").expectStatus().isCreated();

        // Every identifier is distinct and every transaction hangs off the same root, so the store
        // is exercised on all three indexes at once and the root sum is a single number that only
        // comes out right if no write was lost.
        try (ExecutorService pool = Executors.newFixedThreadPool(clients)) {
            List<Callable<Void>> tasks = IntStream.range(0, clients)
                    .<Callable<Void>>mapToObj(client -> () -> {
                        for (int n = 0; n < writesPerClient; n++) {
                            long id = 1L + (long) client * writesPerClient + n;
                            putTransaction(id, """
                                    {"amount": 1, "type": "concurrent", "parent_id": 0}""")
                                    .expectStatus().isCreated();
                        }
                        return null;
                    })
                    .toList();

            for (Future<Void> finished : pool.invokeAll(tasks)) {
                finished.get(2, TimeUnit.MINUTES);
            }
        }

        client.get().uri("/transactions/sum/0").exchange()
                .expectStatus().isOk()
                .expectBody(SumResponse.class).isEqualTo(new SumResponse(total));

        client.get().uri("/transactions/types/concurrent").exchange()
                .expectStatus().isOk()
                .expectBody(ID_LIST).value(ids -> assertThat(ids).hasSize(total - 1));
    }

    @Test
    @DisplayName("keeps state across requests, since the store lives for the process lifetime")
    void keepsStateAcrossRequests() {
        putTransaction(10, """
                {"amount": 5000, "type": "cars"}""").expectStatus().isCreated();
        putTransaction(20, """
                {"amount": 1500, "type": "cars"}""").expectStatus().isCreated();

        client.get().uri("/transactions/types/cars").exchange()
                .expectStatus().isOk()
                .expectBody(ID_LIST).isEqualTo(List.of(10L, 20L));
    }
}
