package com.aspora.interview.transfers.api;

import com.aspora.interview.transfers.domain.ApiException;
import com.aspora.interview.transfers.domain.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Every error leaves as {"error": CODE, "message": "..."}. */
@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    public record ApiError(String error, String message) {
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> business(ApiException e) {
        return ResponseEntity.status(e.code().httpStatus()).body(new ApiError(e.code().name(), e.getMessage()));
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> badRequest(Exception e) {
        String message = e instanceof HttpMessageNotReadableException ? "request body is not valid JSON for this endpoint"
                : e.getMessage();
        return ResponseEntity.badRequest().body(new ApiError(ErrorCode.VALIDATION.name(), message));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noRoute(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ErrorCode.NOT_FOUND.name(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("unhandled error", e);
        return ResponseEntity.internalServerError().body(new ApiError("INTERNAL", "unexpected error"));
    }
}
