package com.mendel.transactions.infrastructure.web;

import com.mendel.transactions.domain.exception.CircularReferenceException;
import com.mendel.transactions.domain.exception.ParentNotFoundException;
import com.mendel.transactions.domain.exception.TransactionNotFoundException;
import com.mendel.transactions.infrastructure.web.dto.ErrorResponse;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Translates domain and binding failures into the API error contract.
 *
 * <p>The status codes follow RFC 9110 rather than the habit of answering 404 for anything missing:
 *
 * <ul>
 *   <li>404 only when the addressed transaction does not exist.
 *   <li>422 when a well-formed request fails a referential rule, which is the case for a parent_id
 *       that resolves to nothing. Answering 404 there would claim the target URI does not exist,
 *       when that URI is precisely the resource being created.
 *   <li>409 when the request conflicts with the current state, which is what a parent link that
 *       would close a cycle is.
 *   <li>400 when the request itself cannot be understood or fails input validation.
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(TransactionNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleTransactionNotFound(TransactionNotFoundException exception) {
        return new ErrorResponse(exception.code(), exception.getMessage());
    }

    @ExceptionHandler(ParentNotFoundException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse handleParentNotFound(ParentNotFoundException exception) {
        return new ErrorResponse(exception.code(), exception.getMessage());
    }

    @ExceptionHandler(CircularReferenceException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleCircularReference(CircularReferenceException exception) {
        return new ErrorResponse(exception.code(), exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleInvalidBody(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(ApiExceptionHandler::describe)
                .sorted()
                .collect(Collectors.joining("; "));
        return new ErrorResponse("VALIDATION_ERROR", message);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return new ErrorResponse("VALIDATION_ERROR",
                exception.getName() + " must be a number, but was '" + exception.getValue() + "'");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleUnreadableBody(HttpMessageNotReadableException exception) {
        return new ErrorResponse("MALFORMED_REQUEST", "the request body is not valid JSON");
    }

    /**
     * Catches invariants enforced by the domain itself, for values that pass bean validation but
     * that a Transaction still refuses, such as a non-finite amount.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleIllegalArgument(IllegalArgumentException exception) {
        return new ErrorResponse("VALIDATION_ERROR", exception.getMessage());
    }

    private static String describe(FieldError error) {
        return error.getDefaultMessage() == null
                ? error.getField() + " is invalid"
                : error.getDefaultMessage();
    }
}
