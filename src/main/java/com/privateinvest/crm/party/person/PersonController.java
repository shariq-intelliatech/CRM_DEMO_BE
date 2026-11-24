package com.privateinvest.crm.party.person;

import com.privateinvest.crm.audit.AuditQueryService;
import com.privateinvest.crm.record.RecordController;
import com.privateinvest.crm.record.RecordService;
import com.privateinvest.crm.record.search.RecordSearchService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Persons (brief §3.2): every endpoint of a configurable record type. */
@RestController
@RequestMapping("/api/persons")
public class PersonController extends RecordController {

    public PersonController(RecordService service, RecordSearchService search, AuditQueryService audit) {
        super("person", service, search, audit);
    }
}
