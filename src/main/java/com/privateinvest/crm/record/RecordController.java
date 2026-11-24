package com.privateinvest.crm.record;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import com.privateinvest.crm.audit.AuditDtos.AuditPage;
import com.privateinvest.crm.audit.AuditQueryService;
import com.privateinvest.crm.common.RecordVersion;
import com.privateinvest.crm.customfield.CustomFieldDtos.ValuesUpdate;
import com.privateinvest.crm.customfield.RecordFieldDtos.RecordFieldCreate;
import com.privateinvest.crm.customfield.RecordFieldDtos.RecordFieldUpdate;
import com.privateinvest.crm.record.RecordDtos.ArchiveRequest;
import com.privateinvest.crm.record.RecordDtos.HotUpdate;
import com.privateinvest.crm.record.RecordDtos.RecordCreate;
import com.privateinvest.crm.record.RecordDtos.RecordDetail;
import com.privateinvest.crm.record.RecordDtos.RecordPatch;
import com.privateinvest.crm.record.RecordDtos.RestoreRequest;
import com.privateinvest.crm.record.search.RecordSearchService;
import com.privateinvest.crm.record.search.SearchDtos.LookupPage;
import com.privateinvest.crm.record.search.SearchDtos.SearchPage;
import com.privateinvest.crm.record.search.SearchDtos.SearchRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The endpoints every configurable record type has (Persons at /api/persons, Entities at /api/entities). A record
 * is read and written as field values by api name. Saves need the version the page loaded in If-Match; quick
 * actions (Hot, one-off fields, removing a field from one record) do not.
 */
public abstract class RecordController {

    private final String object;
    protected final RecordService service;
    protected final RecordSearchService search;
    protected final AuditQueryService audit;

    protected RecordController(String object, RecordService service, RecordSearchService search, AuditQueryService audit) {
        this.object = object;
        this.service = service;
        this.search = search;
        this.audit = audit;
    }

    /** Filter by any filterable field (numbered filters with logic), sort by sortable fields, page through results. */
    @PostMapping("/search")
    SearchPage search(@RequestBody SearchRequest body) {
        return search.search(object, body);
    }

    /**
     * Server-side picker: live records matching q, a page at a time; ids resolves labels of chosen records.
     * {@code field} and {@code values} narrow the choice, e.g. field=entityType&values=AGGREGATOR_GROUP.
     */
    @GetMapping("/lookup")
    LookupPage lookup(@RequestParam(required = false) String q,
                      @RequestParam(defaultValue = "0") int page,
                      @RequestParam(defaultValue = "20") int size,
                      @RequestParam(required = false, defaultValue = "") List<UUID> exclude,
                      @RequestParam(required = false, defaultValue = "") List<UUID> ids,
                      @RequestParam(required = false) String field,
                      @RequestParam(required = false, defaultValue = "") List<String> values) {
        return search.lookup(object, q, page, size, exclude, ids, field, values);
    }

    @GetMapping("/{id}")
    RecordDetail get(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.get(object, id, includeArchived);
    }

    /** Append-only change history for this record, including linked-entity events (brief §6.4). */
    @GetMapping("/{id}/history")
    AuditPage history(@PathVariable UUID id,
                      @RequestParam(defaultValue = "0") int page,
                      @RequestParam(defaultValue = "25") int size) {
        return audit.forRecord(object, id, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RecordDetail create(@Valid @RequestBody RecordCreate body) {
        return service.create(object, body);
    }

    @PatchMapping("/{id}")
    RecordDetail patch(@PathVariable UUID id, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody RecordPatch body) {
        return service.patch(object, id, RecordVersion.parse(ifMatch), body.values(), "update");
    }

    /** The original custom-fields save; the same as PATCH. */
    @PutMapping("/{id}/custom-fields")
    RecordDetail customFields(@PathVariable UUID id, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody ValuesUpdate body) {
        return service.patch(object, id, RecordVersion.parse(ifMatch), body.values(), "update-custom-fields");
    }

    /** Archives the record (soft delete); it can be restored. */
    @DeleteMapping("/{id}")
    RecordDetail archive(@PathVariable UUID id, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestBody(required = false) ArchiveRequest body) {
        return service.archive(object, id, RecordVersion.parse(ifMatch), body == null ? null : body.reason());
    }

    @PostMapping("/{id}/restore")
    RecordDetail restore(@PathVariable UUID id, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestBody(required = false) RestoreRequest body) {
        return service.restore(object, id, RecordVersion.parse(ifMatch), body == null ? null : body.duplicateReason());
    }

    /** Removes a field from this record only (value deleted; other records keep the field). */
    @DeleteMapping("/{id}/fields/{apiName}")
    RecordDetail removeField(@PathVariable UUID id, @PathVariable String apiName) {
        return service.removeField(object, id, apiName);
    }

    /** Adds a field removed from this record back to it. */
    @PostMapping("/{id}/fields/{apiName}")
    RecordDetail addFieldBack(@PathVariable UUID id, @PathVariable String apiName) {
        return service.addFieldBack(object, id, apiName);
    }

    @PostMapping("/{id}/record-fields")
    RecordDetail addRecordField(@PathVariable UUID id, @Valid @RequestBody RecordFieldCreate body) {
        return service.addRecordField(object, id, body);
    }

    @PutMapping("/{id}/record-fields/{fieldId}")
    RecordDetail updateRecordField(@PathVariable UUID id, @PathVariable UUID fieldId, @Valid @RequestBody RecordFieldUpdate body) {
        return service.updateRecordField(object, id, fieldId, body);
    }

    /** Archives the field in the record's document (never a hard delete); it stays in the audit trail. */
    @DeleteMapping("/{id}/record-fields/{fieldId}")
    RecordDetail removeRecordField(@PathVariable UUID id, @PathVariable UUID fieldId) {
        return service.removeRecordField(object, id, fieldId);
    }

    @PutMapping("/{id}/hot")
    RecordDetail hot(@PathVariable UUID id, @RequestBody HotUpdate body) {
        return service.setHot(object, id, body.hot());
    }
}
