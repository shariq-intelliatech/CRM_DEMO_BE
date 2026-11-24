--liquibase formatted sql

-- Fields the design (Index.html) shows that the client field map does not: Sales tracking and Lending appetite on
-- Person; Sales tracking, Country of incorporation and Registry, compliance and addresses on Entity. Sections
-- follow the design order. All of it stays configurable in Setup.

--changeset privateinvest:005-design-fields splitStatements:false
CREATE OR REPLACE FUNCTION pg_temp.code_of(label TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
    SELECT btrim(regexp_replace(upper(btrim(label)), '[^A-Z0-9]+', '_', 'g'), '_')
$$;

-- Makes a list hold exactly these options, in this order. An item is 'Label' or 'CODE|Label' (to keep an existing
-- code under a new label). Options not listed are retired (active = false), never deleted.
CREATE OR REPLACE FUNCTION pg_temp.options(p_set TEXT, p_name TEXT, p_multi BOOLEAN, p_items TEXT[]) RETURNS VOID
LANGUAGE plpgsql AS $$
DECLARE
    i INT := 0;
    item TEXT;
    v_code TEXT;
    v_label TEXT;
    keep TEXT[] := '{}';
BEGIN
    INSERT INTO picklist_set (code, name, description, multi_select, locked, placeholder, sort_order)
    VALUES (p_set, p_name, 'Options from the client field map.', p_multi, FALSE, FALSE,
            (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM picklist_set))
    ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, multi_select = EXCLUDED.multi_select;
    FOREACH item IN ARRAY p_items LOOP
        IF position('|' IN item) > 0 THEN
            v_code := split_part(item, '|', 1);
            v_label := btrim(split_part(item, '|', 2));
        ELSE
            v_code := pg_temp.code_of(item);
            v_label := btrim(item);
        END IF;
        INSERT INTO picklist_value (set_code, code, label, arm, active, system_value, sort_order)
        VALUES (p_set, v_code, v_label, 'BOTH', TRUE, FALSE, i)
        ON CONFLICT (set_code, code) DO UPDATE SET label = EXCLUDED.label, active = TRUE, sort_order = EXCLUDED.sort_order;
        keep := keep || v_code;
        i := i + 1;
    END LOOP;
    UPDATE picklist_value SET active = FALSE, sort_order = 1000 + sort_order
    WHERE set_code = p_set AND NOT (code = ANY (keep)) AND active;
END $$;

-- Creates or updates one field definition. o holds the optional settings.
CREATE OR REPLACE FUNCTION pg_temp.field(p_object TEXT, p_api TEXT, p_label TEXT, p_type TEXT, p_set TEXT, p_section TEXT,
                              p_sort INT, p_mandatory BOOLEAN, p_arm TEXT, o JSONB DEFAULT '{}') RETURNS VOID
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO custom_field_def (object_api_name, api_name, label, help_text, data_type, picklist_set_code, mandatory,
        expected, show_in_list, list_order, max_length, decimal_places, sort_order, active, created_by, updated_by,
        storage, width, validation, show_on_create, searchable, filterable, sortable, section, display_rules, formula,
        options_from, arm)
    VALUES (p_object, p_api, p_label, o ->> 'help', p_type, p_set, p_mandatory,
        COALESCE((o ->> 'expected')::boolean, FALSE), COALESCE((o ->> 'list')::boolean, FALSE),
        COALESCE((o ->> 'listOrder')::int, 100), (o ->> 'maxLength')::int, (o ->> 'decimals')::int, p_sort, TRUE,
        'seed', 'seed', 'CUSTOM', COALESCE(o ->> 'width', 'HALF'), COALESCE(o -> 'validation', '{}'),
        COALESCE((o ->> 'onCreate')::boolean, TRUE), COALESCE((o ->> 'searchable')::boolean, FALSE),
        COALESCE((o ->> 'filterable')::boolean, TRUE), COALESCE((o ->> 'sortable')::boolean, p_type <> 'MULTI_PICKLIST'),
        p_section, COALESCE(o -> 'rules', '{}'), o -> 'formula', o ->> 'optionsFrom', p_arm)
    ON CONFLICT (object_api_name, api_name) DO UPDATE SET
        label = EXCLUDED.label,
        help_text = COALESCE(EXCLUDED.help_text, custom_field_def.help_text),
        data_type = EXCLUDED.data_type,
        picklist_set_code = EXCLUDED.picklist_set_code,
        mandatory = EXCLUDED.mandatory,
        expected = EXCLUDED.expected,
        show_in_list = CASE WHEN o ? 'list' THEN EXCLUDED.show_in_list ELSE custom_field_def.show_in_list END,
        list_order = CASE WHEN o ? 'listOrder' THEN EXCLUDED.list_order ELSE custom_field_def.list_order END,
        decimal_places = EXCLUDED.decimal_places,
        sort_order = EXCLUDED.sort_order,
        active = TRUE,
        deleted_at = NULL,
        deleted_by = NULL,
        validation = EXCLUDED.validation,
        width = EXCLUDED.width,
        show_on_create = EXCLUDED.show_on_create,
        searchable = CASE WHEN o ? 'searchable' THEN EXCLUDED.searchable ELSE custom_field_def.searchable END,
        filterable = EXCLUDED.filterable,
        sortable = EXCLUDED.sortable,
        section = EXCLUDED.section,
        display_rules = EXCLUDED.display_rules,
        formula = EXCLUDED.formula,
        options_from = EXCLUDED.options_from,
        arm = EXCLUDED.arm,
        required_locked = custom_field_def.required_locked AND EXCLUDED.mandatory,
        updated_at = now(),
        updated_by = 'seed',
        version = custom_field_def.version + 1;
END $$;


-- ---------------------------------------------------------------------------------------------------------------
-- Option lists the design adds
-- ---------------------------------------------------------------------------------------------------------------
SELECT pg_temp.options('SALES_STAGE', 'Sales stage', FALSE, ARRAY['Suspect', 'Prospect', 'Active']);
SELECT pg_temp.options('APPETITE_LEVEL', 'Lending appetite', FALSE, ARRAY['Preferred', 'Will consider', 'Excluded']);
SELECT pg_temp.options('TAX_RESIDENCY', 'Tax residency', FALSE, ARRAY['Australian resident', 'Foreign resident', 'Not yet known']);
SELECT pg_temp.options('ENTITY_STATUS', 'Entity status', FALSE, ARRAY['Active', 'Deregistered', 'Under external administration', 'Not yet known']);
SELECT pg_temp.options('KYC_STATUS', 'ID / KYC status', FALSE, ARRAY['Not started', 'In progress', 'Verified', 'Expired']);

-- ---------------------------------------------------------------------------------------------------------------
-- Person: Sales tracking and Lending appetite (design: Person record)
-- ---------------------------------------------------------------------------------------------------------------
SELECT pg_temp.field('person', 'salesStage', 'Sales stage', 'PICKLIST', 'SALES_STAGE', 'Sales tracking', 1, FALSE, 'BOTH',
    '{"list": true, "listOrder": 5, "help": "Where the relationship is: Suspect, Prospect or Active."}');
SELECT pg_temp.field('person', 'salesStageChangedOn', 'Sales stage changed on', 'DATE', NULL, 'Sales tracking', 2, FALSE, 'BOTH',
    '{"onCreate": false, "help": "Set by the CRM when the sales stage changes.",
      "formula": {"fn": "CHANGED_ON", "fields": ["salesStage"]}}');
-- Lending appetite: shown for investors (design: "Investor only").
SELECT pg_temp.field('person', 'appetite' || x.api, x.label, 'PICKLIST', 'APPETITE_LEVEL', 'Lending appetite', 500 + x.n, FALSE, 'CAPITAL',
    '{"rules": {"visibleWhen": [{"field": "personTypes", "operator": "CONTAINS_ANY", "value": ["INVESTOR"]}]}}')
FROM (VALUES (1, 'Commercial', 'Commercial'), (2, 'Industrial', 'Industrial'), (3, 'MixedUse', 'Mixed use'),
             (4, 'Residential', 'Residential'), (5, 'Retail', 'Retail'), (6, 'Alternative', 'Alternative')) AS x(n, api, label);
SELECT pg_temp.field('person', 'preferredLoanSizeMin', 'Preferred loan size (min)', 'CURRENCY', NULL, 'Lending appetite', 510, FALSE, 'CAPITAL',
    '{"decimals": 0, "rules": {"visibleWhen": [{"field": "personTypes", "operator": "CONTAINS_ANY", "value": ["INVESTOR"]}]}}');
SELECT pg_temp.field('person', 'preferredLoanSizeMax', 'Preferred loan size (max)', 'CURRENCY', NULL, 'Lending appetite', 511, FALSE, 'CAPITAL',
    '{"decimals": 0, "rules": {"visibleWhen": [{"field": "personTypes", "operator": "CONTAINS_ANY", "value": ["INVESTOR"]}]}}');
SELECT pg_temp.field('person', 'preferredLocations', 'Preferred location', 'MULTI_PICKLIST', 'STATE', 'Lending appetite', 512, FALSE, 'CAPITAL',
    '{"rules": {"visibleWhen": [{"field": "personTypes", "operator": "CONTAINS_ANY", "value": ["INVESTOR"]}]}}');
SELECT pg_temp.field('person', 'maxLvr', 'Max LVR', 'PERCENT', NULL, 'Lending appetite', 513, FALSE, 'CAPITAL',
    '{"decimals": 1, "validation": {"min": 0, "max": 100},
      "rules": {"visibleWhen": [{"field": "personTypes", "operator": "CONTAINS_ANY", "value": ["INVESTOR"]}]}}');
SELECT pg_temp.field('person', 'maxTermMonths', 'Max term (months)', 'NUMBER', NULL, 'Lending appetite', 514, FALSE, 'CAPITAL',
    '{"decimals": 0, "validation": {"min": 1, "max": 600},
      "rules": {"visibleWhen": [{"field": "personTypes", "operator": "CONTAINS_ANY", "value": ["INVESTOR"]}]}}');

-- The design keeps owner, engagement temperature and the sources with the sales stage.
UPDATE custom_field_def SET section = 'Sales tracking'
WHERE object_api_name = 'person' AND api_name IN ('ownerId', 'engagementTemperature', 'contactSources', 'databaseSources');

-- ---------------------------------------------------------------------------------------------------------------
-- Entity: Sales tracking, overview extras, and Registry, compliance and addresses (design: Entity record)
-- ---------------------------------------------------------------------------------------------------------------
SELECT pg_temp.field('entity', 'salesStage', 'Sales stage', 'PICKLIST', 'SALES_STAGE', 'Sales tracking', 1, FALSE, 'BOTH',
    '{"list": true, "listOrder": 8}');
SELECT pg_temp.field('entity', 'salesStageChangedOn', 'Sales stage changed on', 'DATE', NULL, 'Sales tracking', 2, FALSE, 'BOTH',
    '{"onCreate": false, "help": "Set by the CRM when the sales stage changes.",
      "formula": {"fn": "CHANGED_ON", "fields": ["salesStage"]}}');
SELECT pg_temp.field('entity', 'contactSources', 'Contact source', 'MULTI_PICKLIST', 'CONTACT_SOURCE', 'Sales tracking', 3, FALSE, 'BOTH');
SELECT pg_temp.field('entity', 'databaseSources', 'Database source', 'MULTI_PICKLIST', 'DATABASE_SOURCE', 'Sales tracking', 4, FALSE, 'BOTH');
SELECT pg_temp.field('entity', 'countryOfIncorporation', 'Country of incorporation', 'TEXT', NULL, 'Entity details', 70, FALSE, 'BOTH');
UPDATE custom_field_def SET default_value = '"Australia"' WHERE object_api_name = 'entity' AND api_name = 'countryOfIncorporation';

SELECT pg_temp.field('entity', 'registeredAddress', 'Registered address', 'LONG_TEXT', NULL, 'Registry, compliance and addresses', 600, FALSE, 'BOTH',
    '{"width": "FULL"}');
SELECT pg_temp.field('entity', 'taxResidency', 'Tax residency', 'PICKLIST', 'TAX_RESIDENCY', 'Registry, compliance and addresses', 610, FALSE, 'BOTH');
SELECT pg_temp.field('entity', 'entityStatus', 'Entity status', 'PICKLIST', 'ENTITY_STATUS', 'Registry, compliance and addresses', 620, FALSE, 'BOTH');
SELECT pg_temp.field('entity', 'kycStatus', 'ID / KYC status', 'PICKLIST', 'KYC_STATUS', 'Registry, compliance and addresses', 630, FALSE, 'BOTH');
SELECT pg_temp.field('entity', 'sharepointFolder', 'SharePoint folder', 'URL', NULL, 'Registry, compliance and addresses', 640, FALSE, 'BOTH');
SELECT pg_temp.field('entity', 'lastReviewDate', 'Last review', 'DATE', NULL, 'Registry, compliance and addresses', 650, FALSE, 'BOTH');

-- ---------------------------------------------------------------------------------------------------------------
-- Sections in the design's order; fields follow their section's order (forms group sections by field order).
-- ---------------------------------------------------------------------------------------------------------------
INSERT INTO custom_field_section (object_api_name, name, sort_order, created_by, updated_by)
SELECT DISTINCT d.object_api_name, d.section, 0, 'seed', 'seed' FROM custom_field_def d
WHERE NOT EXISTS (SELECT 1 FROM custom_field_section s
                  WHERE s.object_api_name = d.object_api_name AND lower(btrim(s.name)) = lower(btrim(d.section)));

WITH wanted(object_api_name, name, n) AS (VALUES
    ('person', 'Sales tracking', 1), ('person', 'Investor profile', 2), ('person', 'Lending appetite', 3),
    ('person', 'Fund registry details', 4), ('person', 'Personal details', 5), ('person', 'Classification', 6),
    ('person', 'Communication consent', 7), ('person', 'Contact profile', 8), ('person', 'Legal details', 9),
    ('person', 'Marketing', 10), ('person', 'Marketing / LinkedIn', 11), ('person', 'Marketing / Mailchimp', 12),
    ('person', 'Flag', 13), ('person', 'Broker profile', 14), ('person', 'Relationship & Status', 15),
    ('entity', 'Sales tracking', 1), ('entity', 'Entity details', 2), ('entity', 'Contact', 3),
    ('entity', 'Registry, compliance and addresses', 4), ('entity', 'First source', 5), ('entity', 'Status', 6),
    ('entity', 'Flags', 7))
UPDATE custom_field_section s SET sort_order = w.n, updated_at = now()
FROM wanted w WHERE s.object_api_name = w.object_api_name AND s.name = w.name;

WITH ordered AS (
    SELECT d.id, row_number() OVER (PARTITION BY d.object_api_name ORDER BY coalesce(s.sort_order, 99), d.sort_order, d.label) * 10 AS n
    FROM custom_field_def d
    LEFT JOIN custom_field_section s ON s.object_api_name = d.object_api_name AND s.name = d.section
)
UPDATE custom_field_def d SET sort_order = o.n FROM ordered o WHERE d.id = o.id;

-- Seeded rows carry the seed author, not the POC's migration name.
UPDATE custom_field_def SET created_by = 'seed', updated_by = 'seed', version = 0;
--rollback not required (configuration seed)
