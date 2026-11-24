package com.privateinvest.crm.record.search;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import com.privateinvest.crm.exception.ApiExceptions.FieldValidationException;
import com.privateinvest.crm.record.search.SearchDtos.FilterNode;

/**
 * Salesforce-style filter logic: numbered conditions combined by a text such as {@code 1 AND (2 OR NOT 3)}.
 * Rules, as in Salesforce: every condition must be used; AND and OR can't be mixed at one level without
 * parentheses; NOT applies to the number or bracket right after it. Blank logic means all conditions with AND.
 * The result is a {@link FilterNode} tree, so the query builder checks fields and values as usual.
 */
final class FilterLogic {

    static final int MAX_LENGTH = 500;

    private final String text;
    private final List<FilterNode> conditions;
    private final List<String> tokens = new ArrayList<>();
    private final BitSet used = new BitSet();
    private int pos;

    private FilterLogic(String text, List<FilterNode> conditions) {
        this.text = text;
        this.conditions = conditions;
    }

    /** The conditions combined by the logic, or null when there are no conditions. */
    static FilterNode parse(List<FilterNode> conditions, String logic) {
        if (conditions == null || conditions.isEmpty()) {
            if (logic != null && !logic.isBlank()) {
                throw error("Add filters before writing filter logic.");
            }
            return null;
        }
        for (FilterNode c : conditions) {
            if (c == null || c.isGroup()) {
                throw error("Each filter is one field, operator and value.");
            }
        }
        if (logic == null || logic.isBlank()) {
            return new FilterNode("AND", List.copyOf(conditions), null, null, null);
        }
        if (logic.length() > MAX_LENGTH) {
            throw error("Keep filter logic under " + MAX_LENGTH + " characters.");
        }
        FilterLogic p = new FilterLogic(logic, conditions);
        p.tokenize();
        FilterNode tree = p.expression();
        if (p.pos < p.tokens.size()) {
            throw error("Unexpected '" + p.tokens.get(p.pos) + "' in the filter logic.");
        }
        int missing = p.used.nextClearBit(1);
        if (missing <= conditions.size()) {
            throw error("Filter " + missing + " isn't used in the filter logic. Use every filter, or remove the ones you don't need.");
        }
        return tree;
    }

    private void tokenize() {
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '(' || c == ')') {
                tokens.add(String.valueOf(c));
                i++;
            } else if (Character.isDigit(c)) {
                int j = i;
                while (j < text.length() && Character.isDigit(text.charAt(j))) {
                    j++;
                }
                tokens.add(text.substring(i, j));
                i = j;
            } else if (Character.isLetter(c)) {
                int j = i;
                while (j < text.length() && Character.isLetter(text.charAt(j))) {
                    j++;
                }
                String word = text.substring(i, j).toUpperCase();
                if (!word.equals("AND") && !word.equals("OR") && !word.equals("NOT")) {
                    throw error("'" + text.substring(i, j) + "' isn't allowed. Use filter numbers, AND, OR, NOT and parentheses.");
                }
                tokens.add(word);
                i = j;
            } else {
                throw error("'" + c + "' isn't allowed. Use filter numbers, AND, OR, NOT and parentheses.");
            }
        }
        if (tokens.isEmpty()) {
            throw error("Enter filter logic, e.g. 1 AND (2 OR 3).");
        }
    }

    /** expression := factor ((AND factor)* | (OR factor)*) — one kind of operator per level. */
    private FilterNode expression() {
        List<FilterNode> parts = new ArrayList<>();
        parts.add(factor());
        String op = null;
        while (pos < tokens.size() && (peek("AND") || peek("OR"))) {
            String next = tokens.get(pos++);
            if (op != null && !op.equals(next)) {
                throw error("Use parentheses when you mix AND and OR, e.g. 1 AND (2 OR 3).");
            }
            op = next;
            parts.add(factor());
        }
        return parts.size() == 1 ? parts.getFirst() : new FilterNode(op, parts, null, null, null);
    }

    /** factor := NOT factor | ( expression ) | number */
    private FilterNode factor() {
        if (pos >= tokens.size()) {
            throw error("The filter logic ends too early. Add a filter number after AND, OR or NOT.");
        }
        String t = tokens.get(pos++);
        if (t.equals("NOT")) {
            return new FilterNode("NOT", List.of(factor()), null, null, null);
        }
        if (t.equals("(")) {
            FilterNode inner = expression();
            if (pos >= tokens.size() || !tokens.get(pos).equals(")")) {
                throw error("A '(' in the filter logic has no matching ')'.");
            }
            pos++;
            return inner;
        }
        if (Character.isDigit(t.charAt(0))) {
            int n = t.length() > 3 ? Integer.MAX_VALUE : Integer.parseInt(t);
            if (n < 1 || n > conditions.size()) {
                throw error("There is no filter " + t + ". Filters are numbered 1 to " + conditions.size() + ".");
            }
            used.set(n);
            return conditions.get(n - 1);
        }
        throw error("Unexpected '" + t + "' in the filter logic.");
    }

    private boolean peek(String token) {
        return tokens.get(pos).equals(token);
    }

    private static FieldValidationException error(String message) {
        return new FieldValidationException(Map.of("logic", message));
    }
}
