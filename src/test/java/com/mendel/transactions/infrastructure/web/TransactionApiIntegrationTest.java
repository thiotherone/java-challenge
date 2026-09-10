package com.mendel.transactions.infrastructure.web;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mendel.transactions.domain.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests over the whole application context: real controller, real service, real store.
 *
 * <p>These pin down the HTTP contract, including the status codes the challenge specification leaves
 * unspecified.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Transactions API")
class TransactionApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TransactionRepository repository;

    @BeforeEach
    void resetStore() {
        repository.deleteAll();
    }

    private void givenTransaction(long id, String body) throws Exception {
        mockMvc.perform(put("/transactions/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is2xxSuccessful());
    }

    @Nested
    @DisplayName("PUT /transactions/{transaction_id}")
    class PutTransaction {

        @Test
        @DisplayName("creates a transaction: 201 with the acknowledgement body and a Location header")
        void createsTransaction() throws Exception {
            mockMvc.perform(put("/transactions/{id}", 10L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount": 5000, "type": "cars"}"""))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/transactions/10"))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value("ok"));
        }

        @Test
        @DisplayName("replaces an existing transaction: 200, and the new state is what is queried")
        void replacesExistingTransaction() throws Exception {
            givenTransaction(10L, """
                    {"amount": 5000, "type": "cars"}""");

            mockMvc.perform(put("/transactions/{id}", 10L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount": 99, "type": "food"}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ok"));

            mockMvc.perform(get("/transactions/types/{type}", "cars"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
            mockMvc.perform(get("/transactions/types/{type}", "food"))
                    .andExpect(jsonPath("$", contains(10)));
            mockMvc.perform(get("/transactions/sum/{id}", 10L))
                    .andExpect(jsonPath("$.sum").value(99.0));
        }

        @Test
        @DisplayName("accepts parent_id in snake_case, as the specification writes it")
        void acceptsSnakeCaseParentId() throws Exception {
            givenTransaction(10L, """
                    {"amount": 5000, "type": "cars"}""");

            mockMvc.perform(put("/transactions/{id}", 11L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount": 10000, "type": "shopping", "parent_id": 10}"""))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/transactions/sum/{id}", 10L))
                    .andExpect(jsonPath("$.sum").value(15000.0));
        }

        @Test
        @DisplayName("accepts an explicitly null parent_id as a root transaction")
        void acceptsExplicitNullParentId() throws Exception {
            mockMvc.perform(put("/transactions/{id}", 10L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount": 5000, "type": "cars", "parent_id": null}"""))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("412 when If-None-Match: * asks to create and the identifier is taken")
        void refusesToReplaceUnderIfNoneMatch() throws Exception {
            givenTransaction(10L, """
                    {"amount": 5000, "type": "cars"}""");

            mockMvc.perform(put("/transactions/{id}", 10L)
                            .header("If-None-Match", "*")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount": 99, "type": "food"}"""))
                    .andExpect(status().isPreconditionFailed())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type")
                            .value("urn:mendel:transactions:transaction-already-exists"))
                    .andExpect(jsonPath("$.status").value(412));

            // The refused write must leave the stored transaction untouched.
            mockMvc.perform(get("/transactions/sum/{id}", 10L))
                    .andExpect(jsonPath("$.sum").value(5000.0));
            mockMvc.perform(get("/transactions/types/{type}", "cars"))
                    .andExpect(content().json("[10]", true));
        }

        @Test
        @DisplayName("201 when If-None-Match: * asks to create and the identifier is free")
        void createsUnderIfNoneMatchWhenFree() throws Exception {
            mockMvc.perform(put("/transactions/{id}", 10L)
                            .header("If-None-Match", "*")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount": 5000, "type": "cars"}"""))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/transactions/10"))
                    .andExpect(jsonPath("$.status").value("ok"));
        }

        @Test
        @DisplayName("422 when parent_id references a transaction that does not exist")
        void rejectsMissingParent() throws Exception {
            mockMvc.perform(put("/transactions/{id}", 11L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount": 10000, "type": "shopping", "parent_id": 99}"""))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("urn:mendel:transactions:parent-not-found"))
                    .andExpect(jsonPath("$.title").value("Parent transaction not found"))
                    .andExpect(jsonPath("$.status").value(422))
                    .andExpect(jsonPath("$.detail").value(containsString("99")));

            mockMvc.perform(get("/transactions/sum/{id}", 11L))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("409 when a transaction would become its own parent")
        void rejectsSelfParent() throws Exception {
            mockMvc.perform(put("/transactions/{id}", 10L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount": 5000, "type": "cars", "parent_id": 10}"""))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("urn:mendel:transactions:circular-reference"))
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("409 when re-parenting a transaction under its own descendant")
        void rejectsCycleThroughDescendant() throws Exception {
            givenTransaction(10L, """
                    {"amount": 5000, "type": "cars"}""");
            givenTransaction(11L, """
                    {"amount": 10000, "type": "shopping", "parent_id": 10}""");
            givenTransaction(12L, """
                    {"amount": 5000, "type": "shopping", "parent_id": 11}""");

            mockMvc.perform(put("/transactions/{id}", 10L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount": 5000, "type": "cars", "parent_id": 12}"""))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("urn:mendel:transactions:circular-reference"));

            mockMvc.perform(get("/transactions/sum/{id}", 10L))
                    .andExpect(jsonPath("$.sum").value(20000.0));
        }

        @Test
        @DisplayName("400 when amount is missing")
        void rejectsMissingAmount() throws Exception {
            mockMvc.perform(put("/transactions/{id}", 10L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"type": "cars"}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:mendel:transactions:validation-error"))
                    .andExpect(jsonPath("$.detail").value(containsString("amount")));
        }

        @Test
        @DisplayName("400 when type is missing or blank")
        void rejectsMissingOrBlankType() throws Exception {
            mockMvc.perform(put("/transactions/{id}", 10L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount": 5000}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(containsString("type")));

            mockMvc.perform(put("/transactions/{id}", 10L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount": 5000, "type": "   "}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:mendel:transactions:validation-error"));
        }

        @Test
        @DisplayName("400 when the body is malformed JSON")
        void rejectsMalformedJson() throws Exception {
            mockMvc.perform(put("/transactions/{id}", 10L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ not json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:mendel:transactions:malformed-request"));
        }

        @Test
        @DisplayName("400 when the identifier in the path is not a number")
        void rejectsNonNumericIdentifier() throws Exception {
            mockMvc.perform(put("/transactions/{id}", "abc")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount": 5000, "type": "cars"}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:mendel:transactions:validation-error"));
        }

        @Test
        @DisplayName("415 when the body is not JSON")
        void rejectsNonJsonContentType() throws Exception {
            mockMvc.perform(put("/transactions/{id}", 10L)
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("amount=5000"))
                    .andExpect(status().isUnsupportedMediaType());
        }

        @Test
        @DisplayName("405 for a verb the resource does not support")
        void rejectsUnsupportedMethod() throws Exception {
            mockMvc.perform(post("/transactions/{id}", 10L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount": 5000, "type": "cars"}"""))
                    .andExpect(status().isMethodNotAllowed());
        }

        @Test
        @DisplayName("accepts a negative amount")
        void acceptsNegativeAmount() throws Exception {
            mockMvc.perform(put("/transactions/{id}", 10L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount": -5000.5, "type": "refund"}"""))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/transactions/sum/{id}", 10L))
                    .andExpect(jsonPath("$.sum").value(-5000.5));
        }
    }

    @Nested
    @DisplayName("GET /transactions/types/{type}")
    class GetByType {

        @Test
        @DisplayName("returns a bare JSON array of identifiers")
        void returnsArrayOfIdentifiers() throws Exception {
            givenTransaction(10L, """
                    {"amount": 5000, "type": "cars"}""");
            givenTransaction(11L, """
                    {"amount": 10000, "type": "shopping", "parent_id": 10}""");
            givenTransaction(12L, """
                    {"amount": 5000, "type": "shopping", "parent_id": 11}""");

            mockMvc.perform(get("/transactions/types/{type}", "cars"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(content().json("[10]", true));

            mockMvc.perform(get("/transactions/types/{type}", "shopping"))
                    .andExpect(content().json("[11,12]", true));
        }

        @Test
        @DisplayName("returns 200 with an empty array for an unknown type")
        void returnsEmptyArrayForUnknownType() throws Exception {
            mockMvc.perform(get("/transactions/types/{type}", "unknown"))
                    .andExpect(status().isOk())
                    .andExpect(content().json("[]", true));
        }

        @Test
        @DisplayName("matches the type exactly, case included")
        void matchesTypeExactly() throws Exception {
            givenTransaction(10L, """
                    {"amount": 5000, "type": "cars"}""");

            mockMvc.perform(get("/transactions/types/{type}", "CARS"))
                    .andExpect(content().json("[]", true));
        }
    }

    @Nested
    @DisplayName("GET /transactions/sum/{transaction_id}")
    class GetSum {

        @Test
        @DisplayName("sums every transitively linked transaction, per the challenge example")
        void sumsLinkedTransactions() throws Exception {
            givenTransaction(10L, """
                    {"amount": 5000, "type": "cars"}""");
            givenTransaction(11L, """
                    {"amount": 10000, "type": "shopping", "parent_id": 10}""");
            givenTransaction(12L, """
                    {"amount": 5000, "type": "shopping", "parent_id": 11}""");

            mockMvc.perform(get("/transactions/sum/{id}", 10L))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.sum").value(20000.0));

            mockMvc.perform(get("/transactions/sum/{id}", 11L))
                    .andExpect(jsonPath("$.sum").value(15000.0));

            mockMvc.perform(get("/transactions/sum/{id}", 12L))
                    .andExpect(jsonPath("$.sum").value(5000.0));
        }

        @Test
        @DisplayName("404 for a transaction that does not exist")
        void notFoundForUnknownTransaction() throws Exception {
            mockMvc.perform(get("/transactions/sum/{id}", 404L))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("urn:mendel:transactions:transaction-not-found"))
                    .andExpect(jsonPath("$.title").value("Transaction not found"))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.detail").value(containsString("404")));
        }

        @Test
        @DisplayName("400 when the identifier is not a number")
        void rejectsNonNumericIdentifier() throws Exception {
            mockMvc.perform(get("/transactions/sum/{id}", "abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:mendel:transactions:validation-error"));
        }
    }
}
