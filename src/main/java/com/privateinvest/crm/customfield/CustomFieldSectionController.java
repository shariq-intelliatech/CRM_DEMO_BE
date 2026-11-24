package com.privateinvest.crm.customfield;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.privateinvest.crm.common.RecordVersion;
import com.privateinvest.crm.customfield.CustomFieldSectionDtos.SectionCreate;
import com.privateinvest.crm.customfield.CustomFieldSectionDtos.SectionDef;
import com.privateinvest.crm.customfield.CustomFieldSectionDtos.SectionMoveRequest;
import com.privateinvest.crm.customfield.CustomFieldSectionDtos.SectionOrderRequest;
import com.privateinvest.crm.customfield.CustomFieldSectionDtos.SectionUpdate;
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

/** Setup endpoints for managing custom field sections (create, rename, reorder, delete). */
@RestController
@RequestMapping("/api/metadata/custom-field-sections")
@RequiredArgsConstructor
public class CustomFieldSectionController {

    private final CustomFieldSectionService service;

    @GetMapping
    List<SectionDef> list(@RequestParam(defaultValue = "person") String object) {
        return service.list(object);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SectionDef create(@Valid @RequestBody SectionCreate body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    SectionDef update(@PathVariable UUID id, @Valid @RequestBody SectionUpdate body) {
        return service.update(id, body);
    }

    @PutMapping("/{id}/move")
    List<SectionDef> move(@PathVariable UUID id, @RequestBody SectionMoveRequest body) {
        return service.move(id, body.direction());
    }

    @PutMapping("/order")
    List<SectionDef> order(@Valid @RequestBody SectionOrderRequest body) {
        return service.order(body.object(), body.sectionIds());
    }

    @DeleteMapping("/{id}")
    Map<String, Object> delete(@PathVariable UUID id, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestParam(required = false) UUID moveTo) {
        return service.delete(id, RecordVersion.parse(ifMatch), moveTo);
    }
}
