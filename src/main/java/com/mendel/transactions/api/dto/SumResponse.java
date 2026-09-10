package com.mendel.transactions.api.dto;

/** Body of {@code GET /transactions/sum/{transaction_id}}: {@code {"sum":double}}. */
public record SumResponse(double sum) {
}
