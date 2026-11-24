package com.privateinvest.crm.listview;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.privateinvest.crm.common.RecordVersion;
import com.privateinvest.crm.listview.ListViewDtos.ListView;
import com.privateinvest.crm.listview.ListViewDtos.ListViewInput;
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

/** Saved list views for a record type's list screen. */
@RestController
@RequestMapping("/api/list-views")
@RequiredArgsConstructor
public class ListViewController {

    private final ListViewService service;

    @GetMapping
    List<ListView> list(@RequestParam(defaultValue = "person") String object) {
        return service.list(object);
    }

    @GetMapping("/{id}")
    ListView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ListView create(@RequestParam(defaultValue = "person") String object, @Valid @RequestBody ListViewInput body) {
        return service.create(object, body);
    }

    @PutMapping("/{id}")
    ListView update(@PathVariable UUID id, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody ListViewInput body) {
        return service.update(id, RecordVersion.parse(ifMatch), body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    /** Opens the list with this view by default. */
    @PutMapping("/{id}/pin")
    ListView pin(@PathVariable UUID id) {
        return service.pin(id);
    }

    @DeleteMapping("/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unpin(@RequestParam(defaultValue = "person") String object) {
        service.unpin(object);
    }
}
