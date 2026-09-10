package com.mendel.transactions.infrastructure.web.dto;

/**
 * Error body shared by every failure response.
 *
 * @param error   stable machine-readable code
 * @param message human-readable explanation
 */
public record ErrorResponse(String error, String message) {
}
