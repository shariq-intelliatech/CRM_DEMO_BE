package com.privateinvest.crm.metadata;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import com.privateinvest.crm.exception.ApiExceptions.NotFoundException;
import com.privateinvest.crm.security.CurrentUser;
import com.privateinvest.crm.security.PermissionService;
import com.privateinvest.crm.customfield.CustomFieldService;
import com.privateinvest.crm.metadata.MetadataDtos.FieldLabelUpdate;
import com.privateinvest.crm.metadata.MetadataDtos.Me;
import com.privateinvest.crm.metadata.MetadataDtos.Metadata;
import com.privateinvest.crm.metadata.MetadataDtos.ObjectLabelUpdate;
import com.privateinvest.crm.metadata.MetadataDtos.PicklistValue;
import com.privateinvest.crm.metadata.MetadataDtos.PicklistOrder;
import com.privateinvest.crm.metadata.MetadataDtos.PicklistValueCreate;
import com.privateinvest.crm.metadata.MetadataDtos.PicklistValueUpdate;
import com.privateinvest.crm.metadata.MetadataDtos.User;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MetadataController {

    private final MetadataService metadata;
    private final CurrentUser currentUser;
    private final CustomFieldService customFields;
    private final PermissionService permissions;

    @GetMapping("/metadata")
    Metadata metadata() {
        return metadata.load().withCustomFields(customFields.listCustom(null));
    }

    @GetMapping("/users")
    List<User> users() {
        return metadata.users();
    }

    @GetMapping("/me")
    Me me() {
        String id = currentUser.id();
        User u = metadata.users().stream().filter(x -> x.id().equals(id)).findFirst()
                .orElse(new User(id, id, "", id.substring(0, Math.min(2, id.length())).toUpperCase()));
        return new Me(u.id(), u.name(), u.email(), u.initials(), permissions.roles(), permissions.permissions());
    }

    @PutMapping("/metadata/objects/{apiName}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void updateObject(@PathVariable String apiName, @Valid @RequestBody ObjectLabelUpdate body) {
        requireSetup("rename record types");
        metadata.updateObjectLabel(apiName, body.label(), body.labelPlural());
    }

    @PutMapping("/metadata/fields/{object}/{apiName}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void updateField(@PathVariable String object, @PathVariable String apiName, @Valid @RequestBody FieldLabelUpdate body) {
        // Person and Entity fields live in the field registry; field_def only holds labels the registry lacks.
        try {
            customFields.relabel(object, apiName, body.label());
        } catch (NotFoundException e) {
            if ("person".equals(object)) {
                throw e;
            }
            requireSetup("relabel fields");
            metadata.updateFieldLabel(object, apiName, body.label());
        }
    }

    @PutMapping("/metadata/picklists/{setCode}/values/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void updateValue(@PathVariable String setCode, @PathVariable String code, @RequestBody PicklistValueUpdate body) {
        guardCustomPicklist(setCode);
        metadata.updateValue(setCode, code, body);
    }

    @PostMapping("/metadata/picklists/{setCode}/values")
    @ResponseStatus(HttpStatus.CREATED)
    PicklistValue addValue(@PathVariable String setCode, @Valid @RequestBody PicklistValueCreate body) {
        guardCustomPicklist(setCode);
        return metadata.addValue(setCode, body);
    }

    @PutMapping("/metadata/picklists/{setCode}/order")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void orderValues(@PathVariable String setCode, @Valid @RequestBody PicklistOrder body) {
        guardCustomPicklist(setCode);
        metadata.orderValues(setCode, body.codes());
    }

    /** Picklists are shared configuration (Setup → Picklists), so changing them needs CUSTOM_FIELD_MANAGE. */
    private void guardCustomPicklist(String setCode) {
        requireSetup(customFields.isCustomPicklist(setCode) ? "change custom field options" : "change picklists");
    }

    /** Setup changes (labels, picklists, business settings) are for administrators. */
    private void requireSetup(String action) {
        permissions.require(PermissionService.CUSTOM_FIELD_MANAGE, action);
    }

    @PutMapping("/metadata/settings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void updateSettings(@RequestBody Map<String, Object> body) {
        requireSetup("change business settings");
        metadata.updateSettings(body);
    }
}
