package com.mendel.transactions.api;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.mendel.transactions.domain.TransactionRepository;
import com.mendel.transactions.api.dto.StatusResponse;
import com.mendel.transactions.api.dto.SumResponse;
import java.util.List;
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

    @Autowired
    private TransactionRepository repository;

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
