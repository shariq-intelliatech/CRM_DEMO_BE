package com.privateinvest.crm.party.entity;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.privateinvest.crm.party.PartyQueries;
import com.privateinvest.crm.record.RecordDtos.RecordExtension;
import com.privateinvest.crm.record.RecordDtos.Related;
import org.springframework.stereotype.Component;

/** What an Entity adds to the shared record engine: its linked Persons with role, job title and department. */
@Component
@RequiredArgsConstructor
public class EntityExtension implements RecordExtension {

    private final PartyQueries queries;

    @Override
    public String object() {
        return "entity";
    }

    @Override
    public Related related(UUID id) {
        return new Related(null, queries.personsOfEntity(id), null, Map.of());
    }
}
