package com.mendel.transactions.infrastructure.web;

import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Translates domain and binding failures into the API error contract. */
@RestControllerAdvice
public class ApiExceptionHandler {
    // Handlers are added in the "green" step, driven by the tests.
}
