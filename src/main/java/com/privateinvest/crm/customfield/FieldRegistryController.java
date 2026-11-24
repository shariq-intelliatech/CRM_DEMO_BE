package com.privateinvest.crm.customfield;

import java.util.List;
import lombok.RequiredArgsConstructor;
import com.privateinvest.crm.customfield.CustomFieldDtos.FieldView;
import com.privateinvest.crm.customfield.CustomFieldSectionDtos.SectionDef;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Every field of a record type with its operators: what forms, detail pages, lists and filters are built from. */
@RestController
@RequestMapping("/api/metadata/objects")
@RequiredArgsConstructor
public class FieldRegistryController {

    private final CustomFieldService service;
    private final CustomFieldSectionService sections;

    @GetMapping("/{object}/fields")
    List<FieldView> fields(@PathVariable String object, @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return service.views(object, includeDisabled);
    }

    @GetMapping("/{object}/sections")
    List<SectionDef> sections(@PathVariable String object) {
        return sections.list(object);
    }
}
