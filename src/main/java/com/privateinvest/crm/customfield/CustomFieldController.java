package com.privateinvest.crm.customfield;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.privateinvest.crm.common.RecordVersion;
import com.privateinvest.crm.customfield.CustomFieldDtos.ActiveUpdate;
import com.privateinvest.crm.customfield.CustomFieldDtos.CustomFieldCreate;
import com.privateinvest.crm.customfield.CustomFieldDtos.CustomFieldDef;
import com.privateinvest.crm.customfield.CustomFieldDtos.CustomFieldUpdate;
import com.privateinvest.crm.customfield.CustomFieldDtos.DeletedField;
import com.privateinvest.crm.customfield.CustomFieldDtos.FieldUsage;
import com.privateinvest.crm.customfield.CustomFieldDtos.MoveRequest;
import com.privateinvest.crm.customfield.CustomFieldDtos.OrderRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Setup endpoints for field definitions. The list here shows fields admins created; the full registry
 * (system fields included) is {@code GET /api/metadata/objects/{object}/fields}.
 */
@RestController
@RequestMapping("/api/metadata/custom-fields")
@RequiredArgsConstructor
public class CustomFieldController {

    private final CustomFieldService service;

    @GetMapping
    List<CustomFieldDef> list(@RequestParam(required = false) String object) {
        return service.listCustom(object);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CustomFieldDef create(@Valid @RequestBody CustomFieldCreate body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    CustomFieldDef update(@PathVariable UUID id, @Valid @RequestBody CustomFieldUpdate body) {
        return service.update(id, body);
    }

    @PutMapping("/{id}/active")
    CustomFieldDef active(@PathVariable UUID id, @RequestBody ActiveUpdate body) {
        return service.setActive(id, body.active());
    }

    @PutMapping("/{id}/move")
    List<CustomFieldDef> move(@PathVariable UUID id, @RequestBody MoveRequest body) {
        return service.move(id, body.direction());
    }

    @PutMapping("/order")
    List<CustomFieldDef> order(@Valid @RequestBody OrderRequest body) {
        return service.order(body.object(), body.fieldIds(), body.list());
    }

    @GetMapping("/{id}/usage")
    FieldUsage usage(@PathVariable UUID id) {
        return service.usage(id);
    }

    /**
     * Deletes the field (soft): it leaves every screen, its values are kept, and it can be restored.
     * {@code permanent=true} on a field that is already deleted removes it and its values for good.
     */
    @DeleteMapping("/{id}")
    Map<String, Object> delete(@PathVariable UUID id, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestParam(defaultValue = "false") boolean permanent) {
        return service.delete(id, RecordVersion.parse(ifMatch), permanent);
    }

    /** Brings a deleted field back exactly as it was, values included. */
    @PostMapping("/{id}/restore")
    CustomFieldDef restore(@PathVariable UUID id) {
        return service.restore(id);
    }

    /** Setup → Deleted fields. */
    @GetMapping("/deleted")
    List<DeletedField> deleted(@RequestParam(defaultValue = "person") String object) {
        return service.deleted(object);
    }
}
