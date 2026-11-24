package com.privateinvest.crm.record.search;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request and response shapes for record search and pickers (Person, Entity). Fields are named by api name. */
public final class SearchDtos {

    private SearchDtos() {
    }

    /**
     * A filter tree. A group has {@code op} (AND / OR) and {@code conditions}; a condition has {@code field},
     * {@code operator} and, for most operators, {@code value} (a list for IN, BETWEEN, CONTAINS_ANY/ALL).
     */
    public record FilterNode(String op, List<FilterNode> conditions, String field, String operator, Object value) {
        boolean isGroup() {
            return conditions != null;
        }
    }

    public record SortKey(String field, String dir) {
    }

    public record SearchRequest(
            /** Quick search across fields marked searchable, plus this record's one-off field values. */
            String q,
            /** ACTIVE (default) or ARCHIVED. */
            String scope,
            /** All records (ALL, default) or the current user's (MINE). */
            String ownerScope,
            /** Only flagged records: POSSIBLE_DUPLICATE (the duplicate review queue, brief §3.8.3) or HOT. */
            String flag,
            /** A filter tree; combined with AND with the numbered conditions below. */
            FilterNode filter,
            /** Numbered conditions (1, 2, 3...) as in a Salesforce list view. */
            List<FilterNode> conditions,
            /** How the numbered conditions combine, e.g. "1 AND (2 OR NOT 3)". Blank means all with AND. */
            String logic,
            List<SortKey> sort,
            /** Fields to return per row; defaults to fields shown in the list, in list order. */
            List<String> columns,
            Integer page,
            Integer size) {
    }

    public record SearchItem(UUID id, long version, String title, boolean archived, Map<String, Object> values) {
    }

    public record SearchPage(List<SearchItem> items, List<String> columns, int page, int size, long totalElements, int totalPages) {
    }

    public record LookupItem(UUID id, String title, String subtitle) {
    }

    public record LookupPage(List<LookupItem> items, int page, int size, boolean hasMore) {
    }
}
