package com.fraud.controller;

import com.fraud.service.DuplicateTransactionConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> errors = exception.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        error -> error.getField(),
                        error -> error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage(),
                        (first, ignored) -> first,
                        TreeMap::new));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "One or more request fields are invalid.");
        problem.setTitle("Invalid request");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadableBody() {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "The request body is missing or malformed.");
    }

    @ExceptionHandler(DuplicateTransactionConflictException.class)
    ResponseEntity<ProblemDetail> handleIdempotencyConflict() {
        return problem(HttpStatus.CONFLICT, "Transaction ID conflict",
                "This transaction ID was already used with different transaction details.");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> handleRedisUnavailable(DataAccessException exception) {
        LOGGER.error("Redis operation failed while processing a request: {}",
                exception.getClass().getSimpleName());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable",
                "The transaction check is temporarily unavailable.");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return ResponseEntity.status(status).body(problem);
    }
}
