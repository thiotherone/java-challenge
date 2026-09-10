package com.mendel.transactions.infrastructure.web.dto;

/** Body of {@code GET /transactions/sum/{transaction_id}}: {@code {"sum":double}}. */
public record SumResponse(double sum) {
}
