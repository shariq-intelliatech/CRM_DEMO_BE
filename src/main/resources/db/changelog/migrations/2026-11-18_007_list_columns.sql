--liquibase formatted sql

-- List columns as in the design: the record name is always the first column, so the name fields aren't repeated;
-- then contact details, sales stage, temperature, owner and state.

--changeset privateinvest:007-list-columns
UPDATE custom_field_def SET show_in_list = FALSE
WHERE object_api_name = 'person' AND api_name IN ('firstName', 'lastName', 'creditStatus', 'capitalStatus');
UPDATE custom_field_def d SET show_in_list = TRUE, list_order = o.n
FROM (VALUES ('emails', 1), ('phones', 2), ('salesStage', 3), ('engagementTemperature', 4), ('ownerId', 5), ('state', 6))
     AS o(api, n)
WHERE d.object_api_name = 'person' AND d.api_name = o.api;
UPDATE custom_field_def SET show_in_list = FALSE WHERE object_api_name = 'entity' AND api_name IN ('name');
UPDATE custom_field_def d SET show_in_list = TRUE, list_order = o.n
FROM (VALUES ('acn', 1), ('abn', 2), ('phones', 3), ('emails', 4), ('salesStage', 5), ('ownerId', 6), ('state', 7))
     AS o(api, n)
WHERE d.object_api_name = 'entity' AND d.api_name = o.api;
--rollback not required (list settings)
