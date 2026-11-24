package com.privateinvest.crm.listview;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.privateinvest.crm.record.search.SearchDtos.FilterNode;
import com.privateinvest.crm.record.search.SearchDtos.SortKey;

/** Saved list views (Salesforce: list views): filters, filter logic, columns and sort, kept under a name. */
public final class ListViewDtos {

    private ListViewDtos() {
    }

    public record ListView(
            UUID id,
            String object,
            String name,
            String ownerId,
            String ownerName,
            /** Visible to everyone; otherwise only to its owner. */
            boolean shared,
            /** ALL or MINE ("Show me" in Salesforce). */
            String ownerScope,
            /** Numbered filters, 1 first. */
            List<FilterNode> conditions,
            String logic,
            List<String> columns,
            List<SortKey> sort,
            /** The view this user's list opens with. */
            boolean pinned,
            /** This user may change or delete the view. */
            boolean canEdit,
            OffsetDateTime updatedAt,
            long version) {
    }

    public record ListViewInput(
            @NotBlank(message = "Enter a list view name.") @Size(max = 80, message = "Use at most 80 characters.") String name,
            boolean shared,
            String ownerScope,
            List<FilterNode> conditions,
            String logic,
            List<String> columns,
            List<SortKey> sort) {
    }
}
