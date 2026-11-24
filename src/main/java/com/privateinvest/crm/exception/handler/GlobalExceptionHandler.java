package com.privateinvest.crm.exception.handler;

import java.util.LinkedHashMap;
import java.util.Map;
import com.privateinvest.crm.exception.ApiExceptions.ConflictException;
import com.privateinvest.crm.exception.ApiExceptions.DuplicateBlockException;
import com.privateinvest.crm.exception.ApiExceptions.DuplicateWarningException;
import com.privateinvest.crm.exception.ApiExceptions.FieldValidationException;
import com.privateinvest.crm.exception.ApiExceptions.ForbiddenException;
import com.privateinvest.crm.exception.ApiExceptions.NotFoundException;
import com.privateinvest.crm.exception.ApiExceptions.VersionRequiredException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Every error is a problem response with a stable {@code code} the frontend switches on:
 * not-found, validation, duplicate-warning (overridable), duplicate-block (hard gate G1),
 * stale (changed by someone else) or version-required.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "not-found", ex.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    ProblemDetail forbidden(ForbiddenException ex) {
        return problem(HttpStatus.FORBIDDEN, "forbidden", ex.getMessage());
    }

    @ExceptionHandler(FieldValidationException.class)
    ProblemDetail validation(FieldValidationException ex) {
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "validation", ex.getMessage());
        p.setProperty("errors", ex.errors());
        return p;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail beanValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(e -> errors.putIfAbsent(e.getField(), e.getDefaultMessage()));
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "validation", "Some fields need attention.");
        p.setProperty("errors", errors);
        return p;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException ex) {
        return problem(HttpStatus.BAD_REQUEST, "validation", "The request body could not be read. Check the field names and value types.");
    }

    @ExceptionHandler(DuplicateWarningException.class)
    ProblemDetail duplicateWarning(DuplicateWarningException ex) {
        ProblemDetail p = problem(HttpStatus.CONFLICT, "duplicate-warning", ex.getMessage());
        p.setProperty("matches", ex.matches());
        return p;
    }

    @ExceptionHandler(DuplicateBlockException.class)
    ProblemDetail duplicateBlock(DuplicateBlockException ex) {
        ProblemDetail p = problem(HttpStatus.CONFLICT, "duplicate-block", ex.getMessage());
        p.setProperty("field", ex.field());
        p.setProperty("existingId", ex.existingId());
        p.setProperty("existingName", ex.existingName());
        return p;
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException ex) {
        ProblemDetail p = problem(HttpStatus.CONFLICT, ex.code(), ex.getMessage());
        ex.details().forEach(p::setProperty);
        return p;
    }

    @ExceptionHandler(VersionRequiredException.class)
    ProblemDetail versionRequired(VersionRequiredException ex) {
        return problem(HttpStatus.PRECONDITION_REQUIRED, "version-required", ex.getMessage());
    }

    /** A missing If-Match is a missing version; checked before the body, so it wins over body errors. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail missingHeader(MissingRequestHeaderException ex) {
        if (HttpHeaders.IF_MATCH.equalsIgnoreCase(ex.getHeaderName())) {
            return versionRequired(new VersionRequiredException());
        }
        return problem(HttpStatus.BAD_REQUEST, "validation", ex.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail staleUpdate(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "stale", "Someone else changed this record. Reload it and try again.");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setProperty("code", code);
        return p;
    }
}
