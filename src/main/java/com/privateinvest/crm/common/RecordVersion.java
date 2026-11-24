package com.privateinvest.crm.common;

import com.privateinvest.crm.exception.ApiExceptions.VersionRequiredException;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Stops a save from silently overwriting someone else's change. The record page loads a
 * {@code version}, sends it back in the {@code If-Match} header, and the save fails with
 * {@code stale} if the record has changed since.
 */
public final class RecordVersion {

    private RecordVersion() {
    }

    /** Reads {@code If-Match}: a bare number, a quoted ETag ({@code "3"}) or a weak one ({@code W/"3"}). */
    public static long parse(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new VersionRequiredException();
        }
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new VersionRequiredException();
        }
    }

    public static void check(long current, long expected) {
        if (current != expected) {
            throw new OptimisticLockingFailureException(
                    "Record is at version " + current + ", the save was based on version " + expected + ".");
        }
    }
}
