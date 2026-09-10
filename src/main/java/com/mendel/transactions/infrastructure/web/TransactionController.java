package com.mendel.transactions.infrastructure.web;

import com.mendel.transactions.application.TransactionCommandService;
import com.mendel.transactions.application.TransactionQueryService;
import com.mendel.transactions.domain.SaveResult;
import com.mendel.transactions.domain.Transaction;
import com.mendel.transactions.infrastructure.web.dto.StatusResponse;
import com.mendel.transactions.infrastructure.web.dto.SumResponse;
import com.mendel.transactions.infrastructure.web.dto.TransactionRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST adapter exposing the transaction service.
 *
 * <p>Deliberately thin: it maps between the HTTP shapes and the domain, and lets
 * {@link ApiExceptionHandler} turn rule violations into status codes.
 */
@RestController
@RequestMapping("/transactions")
public class TransactionController {

    private final TransactionCommandService commandService;
    private final TransactionQueryService queryService;

    public TransactionController(TransactionCommandService commandService,
                                 TransactionQueryService queryService) {
        this.commandService = commandService;
        this.queryService = queryService;
    }

    /**
     * Stores a transaction under a client-chosen identifier.
     *
     * <p>PUT replaces, per RFC 9110: an unused identifier is created and answered with 201 and a
     * Location header, an identifier already in use is replaced and answered with 200. The body is
     * the acknowledgement the challenge specification prescribes in both cases.
     *
     * <p>A client that wants to create without ever replacing says so the standard way, with
     * {@code If-None-Match: *}, and gets 412 if the identifier is taken. Any other If-None-Match
     * value is an entity-tag list; this service issues no ETags, so nothing can match it and the
     * precondition passes, leaving the write to proceed.
     */
    @PutMapping("/{transactionId}")
    public ResponseEntity<StatusResponse> put(
            @PathVariable long transactionId,
            @Valid @RequestBody TransactionRequest request,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        Transaction transaction = new Transaction(
                transactionId, request.amount(), request.type(), request.parentId());

        SaveResult result = requiresAbsence(ifNoneMatch)
                ? commandService.create(transaction)
                : commandService.save(transaction);

        return result == SaveResult.CREATED
                ? ResponseEntity.created(URI.create("/transactions/" + transactionId))
                        .body(StatusResponse.ok())
                : ResponseEntity.ok(StatusResponse.ok());
    }

    /** @return whether the request asked to proceed only if the target resource does not exist. */
    private static boolean requiresAbsence(String ifNoneMatch) {
        return ifNoneMatch != null && "*".equals(ifNoneMatch.trim());
    }

    /** @return the identifiers of every transaction of that type; an empty array if there are none. */
    @GetMapping("/types/{type}")
    public List<Long> findIdsByType(@PathVariable String type) {
        return queryService.findIdsByType(type);
    }

    /** @return the total of every transaction transitively linked to this one through parent_id. */
    @GetMapping("/sum/{transactionId}")
    public SumResponse sum(@PathVariable long transactionId) {
        return new SumResponse(queryService.sumLinkedTo(transactionId));
    }
}
