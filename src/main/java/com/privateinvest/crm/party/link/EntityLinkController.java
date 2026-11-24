package com.privateinvest.crm.party.link;

import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.privateinvest.crm.party.PartyDtos.LinkCreate;
import com.privateinvest.crm.party.PartyDtos.LinkUpdate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/entity-links")
@RequiredArgsConstructor
public class EntityLinkController {

    private final EntityLinkService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> create(@Valid @RequestBody LinkCreate body) {
        return Map.of("id", service.create(body));
    }

    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void update(@PathVariable UUID id, @Valid @RequestBody LinkUpdate body) {
        service.update(id, body);
    }

    /** Unlinks the Person from the Entity; both records stay. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PutMapping("/{id}/primary")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void makePrimary(@PathVariable UUID id) {
        service.makePrimary(id);
    }
}
