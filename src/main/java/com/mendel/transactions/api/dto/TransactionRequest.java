package com.mendel.transactions.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body of {@code PUT /transactions/{transaction_id}}.
 *
 * <p>The identifier is not part of the body: it is the resource being addressed.
 */
public record TransactionRequest(

        @NotNull(message = "amount is required")
        @Positive(message = "amount must be greater than zero")
        Double amount,

        @NotBlank(message = "type is required")
        String type,

        @JsonProperty("parent_id")
        Long parentId) {
}
