package com.mendel.transactions.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mendel.transactions.service.TransactionCommandService;
import com.mendel.transactions.service.TransactionQueryService;
import com.mendel.transactions.domain.SaveResult;
import com.mendel.transactions.domain.Transaction;
import com.mendel.transactions.domain.exception.CircularReferenceException;
import com.mendel.transactions.domain.exception.ParentNotFoundException;
import com.mendel.transactions.domain.exception.TransactionAlreadyExistsException;
import com.mendel.transactions.domain.exception.TransactionNotFoundException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller slice: the web layer against a mocked service.
 *
 * <p>Its job is the translation the full-stack tests cannot isolate, namely which domain failure
 * becomes which status code, and that the path and body reach the service unchanged.
 */
@WebMvcTest(TransactionController.class)
@DisplayName("TransactionController")
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransactionCommandService commandService;

    @MockitoBean
    private TransactionQueryService queryService;

    @Test
    @DisplayName("passes the path identifier and the body through to the service")
    void passesRequestThroughToService() throws Exception {
        when(commandService.save(any())).thenReturn(SaveResult.CREATED);

        mockMvc.perform(put("/transactions/{id}", 11L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 10000.5, "type": "shopping", "parent_id": 10}"""))
                .andExpect(status().isCreated());

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        org.mockito.Mockito.verify(commandService).save(saved.capture());
        assertThat(saved.getValue()).isEqualTo(new Transaction(11L, 10000.5, "shopping", 10L));
    }

    @Test
    @DisplayName("answers 201 with a Location header when the service created the transaction")
    void createdBecomes201() throws Exception {
        when(commandService.save(any())).thenReturn(SaveResult.CREATED);

        mockMvc.perform(put("/transactions/{id}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 5000, "type": "cars"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/transactions/10"))
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    @DisplayName("answers 200 without a Location header when the service replaced it")
    void replacedBecomes200() throws Exception {
        when(commandService.save(any())).thenReturn(SaveResult.REPLACED);

        mockMvc.perform(put("/transactions/{id}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 5000, "type": "cars"}"""))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    @DisplayName("If-None-Match: * routes the write to create rather than replace")
    void ifNoneMatchStarRoutesToCreate() throws Exception {
        when(commandService.create(any())).thenReturn(SaveResult.CREATED);

        mockMvc.perform(put("/transactions/{id}", 10L)
                        .header("If-None-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 5000, "type": "cars"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/transactions/10"));

        org.mockito.Mockito.verify(commandService, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("a taken identifier under If-None-Match: * becomes 412")
    void alreadyExistsBecomes412() throws Exception {
        when(commandService.create(any())).thenThrow(new TransactionAlreadyExistsException(10L));

        mockMvc.perform(put("/transactions/{id}", 10L)
                        .header("If-None-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 5000, "type": "cars"}"""))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.type")
                        .value("urn:mendel:transactions:transaction-already-exists"));
    }

    @Test
    @DisplayName("an entity-tag If-None-Match cannot match, since no ETag is issued, so the write proceeds")
    void entityTagIfNoneMatchProceeds() throws Exception {
        when(commandService.save(any())).thenReturn(SaveResult.REPLACED);

        mockMvc.perform(put("/transactions/{id}", 10L)
                        .header("If-None-Match", "\"some-etag\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 5000, "type": "cars"}"""))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(commandService, org.mockito.Mockito.never()).create(any());
    }

    @Test
    @DisplayName("a missing parent becomes 422")
    void parentNotFoundBecomes422() throws Exception {
        when(commandService.save(any())).thenThrow(new ParentNotFoundException(99L));

        mockMvc.perform(put("/transactions/{id}", 11L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 10000, "type": "shopping", "parent_id": 99}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:mendel:transactions:parent-not-found"));
    }

    @Test
    @DisplayName("a cycle becomes 409")
    void circularReferenceBecomes409() throws Exception {
        when(commandService.save(any())).thenThrow(new CircularReferenceException(10L, 12L));

        mockMvc.perform(put("/transactions/{id}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 5000, "type": "cars", "parent_id": 12}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:mendel:transactions:circular-reference"));
    }

    @Test
    @DisplayName("an unknown transaction becomes 404")
    void transactionNotFoundBecomes404() throws Exception {
        when(queryService.sumLinkedTo(404L)).thenThrow(new TransactionNotFoundException(404L));

        mockMvc.perform(get("/transactions/sum/{id}", 404L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:mendel:transactions:transaction-not-found"));
    }

    @Test
    @DisplayName("renders the sum and the type listing in the shapes the specification prescribes")
    void rendersSpecifiedShapes() throws Exception {
        when(queryService.sumLinkedTo(10L)).thenReturn(20000.0);
        when(queryService.findIdsByType("cars")).thenReturn(List.of(10L));

        mockMvc.perform(get("/transactions/sum/{id}", 10L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sum").value(20000.0));

        mockMvc.perform(get("/transactions/types/{type}", "cars"))
                .andExpect(status().isOk())
                .andExpect(content().json("[10]", true));
    }
}
