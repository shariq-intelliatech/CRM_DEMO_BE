package com.privateinvest.crm.common.util;

import java.util.regex.Pattern;

/** Data format checks behind hard gate G2 (brief §2.4): ACN, ABN and email. */
public final class Formats {

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int[] ACN_WEIGHTS = {8, 7, 6, 5, 4, 3, 2, 1};
    private static final int[] ABN_WEIGHTS = {10, 1, 3, 5, 7, 9, 11, 13, 15, 17, 19};

    private Formats() {
    }

    public static String digits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Australian Company Number: 9 digits, the last is a check digit. */
    public static boolean isValidAcn(String acn) {
        String d = digits(acn);
        if (d.length() != 9) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 8; i++) {
            sum += (d.charAt(i) - '0') * ACN_WEIGHTS[i];
        }
        int expected = (10 - (sum % 10)) % 10;
        return (d.charAt(8) - '0') == expected;
    }

    /** Australian Business Number: 11 digits, weight algorithm with first digit minus one. */
    public static boolean isValidAbn(String abn) {
        String d = digits(abn);
        if (d.length() != 11) {
            return false;
        }
        int sum = (d.charAt(0) - '0' - 1) * ABN_WEIGHTS[0];
        for (int i = 1; i < 11; i++) {
            sum += (d.charAt(i) - '0') * ABN_WEIGHTS[i];
        }
        return sum % 89 == 0;
    }

    /** Message for an invalid ACN, or null when it is valid. Says whether the length or the check digit is wrong. */
    public static String acnError(String acn) {
        int n = digits(acn).length();
        if (n != 9) {
            return "An ACN has 9 digits. This one has " + n + ".";
        }
        return isValidAcn(acn) ? null
                : "This isn't a valid ACN. The last digit doesn't match the ACN check digit, so a digit is probably mistyped. Check it on the ASIC register.";
    }

    /** Message for an invalid ABN, or null when it is valid. */
    public static String abnError(String abn) {
        int n = digits(abn).length();
        if (n != 11) {
            return "An ABN has 11 digits. This one has " + n + ".";
        }
        return isValidAbn(abn) ? null
                : "This isn't a valid ABN. It fails the ATO's ABN check, so a digit is probably mistyped. Check it on ABN Lookup.";
    }

    public static boolean isValidEmail(String email) {
        return email != null && EMAIL.matcher(email.trim()).matches();
    }

    /** Formats a 9-digit ACN as "123 456 789". */
    public static String formatAcn(String acn) {
        String d = digits(acn);
        return d.length() == 9 ? "%s %s %s".formatted(d.substring(0, 3), d.substring(3, 6), d.substring(6)) : acn;
    }

    /** Formats an 11-digit ABN as "12 345 678 901". */
    public static String formatAbn(String abn) {
        String d = digits(abn);
        return d.length() == 11
                ? "%s %s %s %s".formatted(d.substring(0, 2), d.substring(2, 5), d.substring(5, 8), d.substring(8))
                : abn;
    }

    /** Strips whitespace and returns null if blank, so DB columns don't hold empty strings. */
    public static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
