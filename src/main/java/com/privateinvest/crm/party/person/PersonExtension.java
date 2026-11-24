package com.privateinvest.crm.party.person;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.privateinvest.crm.party.PartyDtos.PersonLink;
import com.privateinvest.crm.party.PartyQueries;
import com.privateinvest.crm.record.RecordDtos.RecordExtension;
import com.privateinvest.crm.record.RecordDtos.Related;
import org.springframework.stereotype.Component;

/**
 * What a Person adds to the shared record engine: its Entity links, the Entity type it inherits through the
 * primary link (brief §3.4), and the types of all its linked Entities for display conditions such as the
 * Broker profile ("show when the Person links to an Aggregator").
 */
@Component
@RequiredArgsConstructor
public class PersonExtension implements RecordExtension {

    private final PartyQueries queries;

    @Override
    public String object() {
        return "person";
    }

    @Override
    public Related related(UUID id) {
        List<PersonLink> links = queries.linksOfPerson(id);
        String inherited = links.stream().filter(PersonLink::primary).map(PersonLink::entityType).findFirst().orElse(null);
        List<String> types = links.stream().map(PersonLink::entityType).filter(t -> t != null).distinct().toList();
        return new Related(links, null, inherited, Map.of("linkedEntityType", types));
    }
}
