package com.privateinvest.crm.exception;

import com.privateinvest.crm.exception.handler.GlobalExceptionHandler;
import com.privateinvest.crm.common.RecordVersion;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Errors the API turns into RFC 7807 problem responses (see {@link GlobalExceptionHandler}). */
public final class ApiExceptions {

    private ApiExceptions() {
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String what, Object id) {
            super(what + " " + id + " was not found.");
        }
    }

    /** The signed-in user lacks the permission for this action (403). */
    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) {
            super(message);
        }
    }

    /** A record save came without the version it was based on (see {@link RecordVersion}). */
    public static class VersionRequiredException extends RuntimeException {
        public VersionRequiredException() {
            super("Send the record's version in the If-Match header.");
        }
    }

    /** Field-level validation failures, including invalid data format (hard gate G2). */
    public static class FieldValidationException extends RuntimeException {
        private final Map<String, String> errors;

        public FieldValidationException(Map<String, String> errors) {
            super("Some fields need attention.");
            this.errors = errors;
        }

        public Map<String, String> errors() {
            return errors;
        }
    }

    /** A record that might already exist. The caller may save anyway by giving a reason (brief §3.8.3). */
    public static class DuplicateWarningException extends RuntimeException {
        private final List<DuplicateMatch> matches;

        public DuplicateWarningException(List<DuplicateMatch> matches) {
            super("This may already be in the CRM. Open the existing record, or give a reason and save.");
            this.matches = matches;
        }

        public List<DuplicateMatch> matches() {
            return matches;
        }
    }

    /** Hard gate G1: the ACN or ABN already belongs to another Entity. Cannot be overridden. */
    public static class DuplicateBlockException extends RuntimeException {
        private final String field;
        private final UUID existingId;
        private final String existingName;

        public DuplicateBlockException(String field, UUID existingId, String existingName) {
            this(field, existingId, existingName, "Entities");
        }

        public DuplicateBlockException(String field, UUID existingId, String existingName, String plural) {
            super("This " + field + " already belongs to " + existingName + ". Two " + plural + " can't share "
                    + (field.matches("^[AEIOU].*") ? "an " : "a ") + field + ".");
            this.field = field;
            this.existingId = existingId;
            this.existingName = existingName;
        }

        public String field() {
            return field;
        }

        public UUID existingId() {
            return existingId;
        }

        public String existingName() {
            return existingName;
        }
    }

    /** A request the current state refuses (409), with a stable code and extra details for the screen. */
    public static class ConflictException extends RuntimeException {
        private final String code;
        private final Map<String, Object> details;

        public ConflictException(String code, String message, Map<String, Object> details) {
            super(message);
            this.code = code;
            this.details = details;
        }

        public String code() {
            return code;
        }

        public Map<String, Object> details() {
            return details;
        }
    }

    public record DuplicateMatch(UUID id, String name, List<String> reasons) {
    }
}
