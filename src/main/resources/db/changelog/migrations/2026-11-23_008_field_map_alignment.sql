--liquibase formatted sql

-- Brings Person fields in line with the client field map (EXT_CRM_Field_Map_MASTERContact.csv), where the build
-- had differed from it:
--   DEV-01  Address line 2 is Required.
--   DEV-03  Contact Source and Database source sit in the Contact profile block; Engagement Temperature in Flag.
-- Existing Persons with no Address line 2 keep saving: a field made mandatory later blocks only a new record and
-- clearing the value, never an edit of other fields.

--changeset privateinvest:008-field-map-alignment
UPDATE custom_field_def
SET mandatory = TRUE, updated_at = now(), updated_by = 'seed', version = version + 1
WHERE object_api_name = 'person' AND api_name = 'addressLine2' AND NOT mandatory;

UPDATE custom_field_def
SET section = 'Contact profile', updated_at = now(), updated_by = 'seed', version = version + 1
WHERE object_api_name = 'person' AND api_name IN ('contactSources', 'databaseSources') AND section <> 'Contact profile';

UPDATE custom_field_def
SET section = 'Flag', updated_at = now(), updated_by = 'seed', version = version + 1
WHERE object_api_name = 'person' AND api_name = 'engagementTemperature' AND section <> 'Flag';

-- Fields follow their section's order (forms group sections by field order), as migration 005 set them.
WITH ordered AS (
    SELECT d.id, row_number() OVER (PARTITION BY d.object_api_name ORDER BY coalesce(s.sort_order, 99), d.sort_order, d.label) * 10 AS n
    FROM custom_field_def d
    LEFT JOIN custom_field_section s ON s.object_api_name = d.object_api_name AND s.name = d.section
    WHERE d.object_api_name = 'person'
)
UPDATE custom_field_def d SET sort_order = o.n FROM ordered o WHERE d.id = o.id AND d.sort_order <> o.n;
--rollback UPDATE custom_field_def SET mandatory = FALSE WHERE object_api_name = 'person' AND api_name = 'addressLine2';
--rollback UPDATE custom_field_def SET section = 'Sales tracking' WHERE object_api_name = 'person' AND api_name IN ('contactSources', 'databaseSources', 'engagementTemperature');
