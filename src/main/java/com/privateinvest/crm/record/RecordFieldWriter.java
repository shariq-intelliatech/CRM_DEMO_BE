package com.privateinvest.crm.record;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import com.privateinvest.crm.exception.ApiExceptions.FieldValidationException;
import com.privateinvest.crm.security.CurrentUser;
import com.privateinvest.crm.customfield.CustomFieldDtos.CustomFieldDef;
import com.privateinvest.crm.customfield.CustomFieldDtos.DataType;
import com.privateinvest.crm.customfield.CustomFieldService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads and checks field values of any record type by api name. Values go through {@link CustomFieldService}
 * (types, options, required); this class adds the rules that need the whole record: the owner default on create,
 * conditional required fields, fields that pick from the record's own phone or email list, CRM-set stamp
 * fields, derived formula values and the record name.
 */
@Component
@RequiredArgsConstructor
public class RecordFieldWriter {

    private final CustomFieldService customFields;
    private final CurrentUser currentUser;
    private final ObjectRules rules;

    /** The CRM's time zone: "today" for formulas such as weeks since a date. */
    @Value("${crm.time-zone:Australia/Sydney}")
    private String timeZone;

    public List<CustomFieldDef> fields(String object) {
        return customFields.list(RecordObjects.get(object).apiName());
    }

    public LocalDate today() {
        return LocalDate.now(ZoneId.of(timeZone));
    }

    /**
     * Fields removed from this record that stay removed. A field made required since then shows again, so the
     * record can still be saved.
     */
    public List<String> hidden(String object, Collection<String> hiddenFields) {
        Set<String> required = fields(object).stream().filter(d -> d.active() && d.mandatory()).map(CustomFieldDef::apiName)
                .collect(Collectors.toSet());
        return hiddenFields.stream().filter(f -> !required.contains(f)).toList();
    }

    /**
     * Values of enabled fields the current user may see (sensitive ones are already left out), in form order,
     * with derived formula values worked out.
     */
    public Map<String, Object> values(String object, Map<String, Object> stored) {
        List<CustomFieldDef> defs = fields(object);
        Set<String> active = defs.stream().filter(CustomFieldDef::active).map(CustomFieldDef::apiName).collect(Collectors.toSet());
        Map<String, Object> out = new LinkedHashMap<>();
        stored.forEach((k, v) -> {
            if (active.contains(k)) {
                out.put(k, v);
            }
        });
        FieldLogic.derive(defs, out, today());
        return out;
    }

    /**
     * Expected fields (shown as missing, never blocking) that are empty, leaving out fields removed from this
     * record and fields its display conditions hide.
     */
    public List<String> dataGaps(String object, Map<String, Object> values, List<String> hidden, Map<String, Object> context) {
        return fields(object).stream()
                .filter(d -> d.active() && d.expected() && !d.computed() && !hidden.contains(d.apiName())
                        && FieldLogic.visible(d, values, context)
                        && CustomFieldService.isEmpty(values.get(d.apiName())))
                .map(CustomFieldDef::apiName).toList();
    }

    /** "Olivia Chen": the values of the record-name fields, joined. */
    public String title(String object, Map<String, Object> values) {
        String t = rules.effective(object).nameFields().stream()
                .map(values::get).filter(v -> v != null && !String.valueOf(v).isBlank()).map(String::valueOf)
                .collect(Collectors.joining(" "));
        return t.isBlank() ? "(No name)" : t;
    }

    /**
     * Checks a change against the field registry and the record-level rules, and throws every problem at once:
     * <ul>
     *   <li>unknown, disabled, forbidden and CRM-set fields are refused, as are changes to fields not editable
     *       after create; mandatory fields must have a value (all in {@link CustomFieldService#prepare});</li>
     *   <li>on create, the owner field (if the owner rule names one) defaults to the current user;</li>
     *   <li>a field whose requiredWhen conditions hold must have a value (fields removed from the record and
     *       fields its conditions hide are skipped);</li>
     *   <li>a field that picks from the record's phone or email list must hold one of that list's values;</li>
     *   <li>a CHANGED_BY stamp field is set to the current user when any field it watches changes.</li>
     * </ul>
     */
    public CustomFieldService.Prepared check(String object, Map<String, Object> patch, boolean creating,
                                             Map<String, Object> current, List<String> hidden, Map<String, Object> context) {
        String api = RecordObjects.get(object).apiName();
        Map<String, Object> incoming = new LinkedHashMap<>(patch == null ? Map.of() : patch);
        String owner = rules.effective(api).ownerField();
        if (creating && owner != null && CustomFieldService.isEmpty(incoming.get(owner))) {
            incoming.put(owner, currentUser.id());
        }
        CustomFieldService.Prepared prepared = customFields.prepare(api, current, incoming, creating, Set.of());
        Map<String, String> errors = new LinkedHashMap<>(prepared.errors());
        Map<String, Object> before = prepared.change().before();
        Map<String, Object> after = prepared.change().after();

        Map<String, Object> resulting = new LinkedHashMap<>(current);
        after.forEach((k, v) -> {
            if (v == null) {
                resulting.remove(k);
            } else {
                resulting.put(k, v);
            }
        });
        for (CustomFieldDef d : fields(api)) {
            if (!d.active() || d.computed() || errors.containsKey(d.apiName()) || hidden.contains(d.apiName())) {
                continue;
            }
            Object value = resulting.get(d.apiName());
            if (CustomFieldService.isEmpty(value) && FieldLogic.requiredNow(d, resulting, context)) {
                errors.put(d.apiName(), d.label() + " is required.");
            }
            if (d.optionsFrom() != null && !CustomFieldService.isEmpty(value)
                    && (after.containsKey(d.apiName()) || after.containsKey(d.optionsFrom()))) {
                List<String> options = CustomFieldService.listValues(resulting.get(d.optionsFrom()), null);
                boolean phone = d.dataType() == DataType.PHONE;
                boolean found = options.stream().anyMatch(o -> phone
                        ? com.privateinvest.crm.common.util.Formats.digits(o).equals(com.privateinvest.crm.common.util.Formats.digits(String.valueOf(value)))
                        : o.equalsIgnoreCase(String.valueOf(value)));
                if (!found) {
                    errors.put(d.apiName(), "Choose one of this record's " + (phone ? "phone numbers." : "email addresses."));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        // Stamps: CHANGED_BY takes the current user, CHANGED_ON today's date, when any watched field changes.
        for (CustomFieldDef d : fields(api)) {
            if (!d.active() || !d.stamp() || !(d.formula().get("fields") instanceof List<?> watched)
                    || watched.stream().noneMatch(w -> after.containsKey(String.valueOf(w)))) {
                continue;
            }
            Object stamp = "CHANGED_ON".equals(d.formula().get("fn")) ? today().toString() : currentUser.id();
            if (!stamp.equals(current.get(d.apiName()))) {
                before.put(d.apiName(), current.get(d.apiName()));
                after.put(d.apiName(), stamp);
            }
        }
        return prepared;
    }
}
