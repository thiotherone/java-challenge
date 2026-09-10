package com.mendel.transactions.infrastructure.web;

import com.mendel.transactions.application.TransactionCommandService;
import com.mendel.transactions.application.TransactionQueryService;
import com.mendel.transactions.infrastructure.web.dto.StatusResponse;
import com.mendel.transactions.infrastructure.web.dto.SumResponse;
import com.mendel.transactions.infrastructure.web.dto.TransactionRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST adapter exposing the transaction service. */
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

    @PutMapping("/{transactionId}")
    public ResponseEntity<StatusResponse> put(@PathVariable long transactionId,
                                              @Valid @RequestBody TransactionRequest request) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @GetMapping("/types/{type}")
    public List<Long> findIdsByType(@PathVariable String type) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @GetMapping("/sum/{transactionId}")
    public SumResponse sum(@PathVariable long transactionId) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
