package com.privateinvest.crm.record;

import lombok.RequiredArgsConstructor;
import com.privateinvest.crm.security.PermissionService;
import com.privateinvest.crm.record.ObjectRules.Rules;
import com.privateinvest.crm.record.ObjectRules.RulesView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Setup → Person rules / Entity rules: which fields make the record name, duplicate check, unique gate and queue. */
@RestController
@RequestMapping("/api/metadata/objects/{object}/rules")
@RequiredArgsConstructor
public class ObjectRulesController {

    private final ObjectRules rules;
    private final PermissionService permissions;

    @GetMapping
    RulesView get(@PathVariable String object) {
        permissions.require(RecordObjects.get(object).readPermission(), "see " + RecordObjects.get(object).plural());
        return rules.view(object);
    }

    @PutMapping
    RulesView update(@PathVariable String object, @RequestBody Rules body) {
        return rules.update(object, body);
    }
}
