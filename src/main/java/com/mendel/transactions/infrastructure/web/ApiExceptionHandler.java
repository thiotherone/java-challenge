package com.mendel.transactions.infrastructure.web;

import com.mendel.transactions.domain.exception.CircularReferenceException;
import com.mendel.transactions.domain.exception.ParentNotFoundException;
import com.mendel.transactions.domain.exception.TransactionAlreadyExistsException;
import com.mendel.transactions.domain.exception.TransactionException;
import com.mendel.transactions.domain.exception.TransactionNotFoundException;
import java.net.URI;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Translates domain and binding failures into RFC 9457 problem details.
 *
 * <p>Errors are served as {@code application/problem+json} rather than an envelope of this
 * service's own invention, so a client already speaking HTTP needs no special-casing to read them.
 * The {@code type} URI is the stable machine-readable identifier; {@code detail} carries the part
 * that varies per request.
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

    private static final String PROBLEM_TYPE_PREFIX = "urn:mendel:transactions:";

    @ExceptionHandler(TransactionNotFoundException.class)
    public ProblemDetail handleTransactionNotFound(TransactionNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Transaction not found", exception);
    }

    @ExceptionHandler(ParentNotFoundException.class)
    public ProblemDetail handleParentNotFound(ParentNotFoundException exception) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Parent transaction not found", exception);
    }

    @ExceptionHandler(TransactionAlreadyExistsException.class)
    public ProblemDetail handleTransactionAlreadyExists(TransactionAlreadyExistsException exception) {
        return problem(HttpStatus.PRECONDITION_FAILED, "Transaction already exists", exception);
    }

    @ExceptionHandler(CircularReferenceException.class)
    public ProblemDetail handleCircularReference(CircularReferenceException exception) {
        return problem(HttpStatus.CONFLICT, "Circular parent reference", exception);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleInvalidBody(MethodArgumentNotValidException exception) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(ApiExceptionHandler::describe)
                .sorted()
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "Invalid transaction", "validation-error", detail);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid transaction", "validation-error",
                exception.getName() + " must be a number, but was '" + exception.getValue() + "'");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed request", "malformed-request",
                "the request body is not valid JSON");
    }

    /**
     * Catches invariants enforced by the domain itself, for values that pass bean validation but
     * that a Transaction still refuses, such as a non-finite amount.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid transaction", "validation-error",
                exception.getMessage());
    }

    private static ProblemDetail problem(HttpStatusCode status, String title,
                                         TransactionException exception) {
        return problem(status, title, slug(exception.code()), exception.getMessage());
    }

    private static ProblemDetail problem(HttpStatusCode status, String title, String type,
                                         String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(PROBLEM_TYPE_PREFIX + type));
        return problem;
    }

    /** Turns a domain error code such as PARENT_NOT_FOUND into the URI suffix parent-not-found. */
    private static String slug(String code) {
        return code.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String describe(FieldError error) {
        return error.getDefaultMessage() == null
                ? error.getField() + " is invalid"
                : error.getDefaultMessage();
    }
}
