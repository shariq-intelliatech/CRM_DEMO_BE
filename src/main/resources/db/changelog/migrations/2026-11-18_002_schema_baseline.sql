--liquibase formatted sql

-- Schema baseline for the configurable Person and Entity records. Every business field of a Person or an Entity
-- is a row in custom_field_def (the field registry); values live in each record's custom_fields JSONB document,
-- checked by the check_custom_fields() trigger. One-off fields of a single record live in record_fields.
-- Generated from the proof-of-concept schema (CRM-Demo after migrations V1-V1013), so both behave the same.

--changeset privateinvest:002-extensions
CREATE EXTENSION IF NOT EXISTS pg_trgm;
--rollback DROP EXTENSION IF EXISTS pg_trgm;

--changeset privateinvest:002-platform
-- Users, roles and permissions, settings, option lists, object labels and the audit trail.
CREATE TABLE app_role (
    code character varying(40) NOT NULL,
    name character varying(100) NOT NULL
);

CREATE TABLE app_role_permission (
    role_code character varying(40) NOT NULL,
    permission character varying(60) NOT NULL
);

CREATE TABLE app_setting (
    setting_key character varying(80) NOT NULL,
    setting_value jsonb NOT NULL
);

CREATE TABLE app_user (
    id character varying(64) NOT NULL,
    name character varying(200) NOT NULL,
    email character varying(320) NOT NULL,
    initials character varying(4) NOT NULL,
    active boolean DEFAULT true NOT NULL
);

CREATE TABLE app_user_role (
    user_id character varying(64) NOT NULL,
    role_code character varying(40) NOT NULL
);

CREATE TABLE audit_event (
    id bigint NOT NULL,
    record_kind character varying(40) NOT NULL,
    record_id uuid,
    action character varying(40) NOT NULL,
    changes jsonb,
    reason character varying(1000),
    user_id character varying(64) NOT NULL,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE audit_event ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME audit_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE field_def (
    object_api_name character varying(60) NOT NULL,
    api_name character varying(60) NOT NULL,
    label character varying(150) NOT NULL,
    help_text character varying(500),
    sort_order integer DEFAULT 0 NOT NULL
);

CREATE TABLE object_def (
    api_name character varying(60) NOT NULL,
    label character varying(100) NOT NULL,
    label_plural character varying(100) NOT NULL
);

CREATE TABLE picklist_set (
    code character varying(60) NOT NULL,
    name character varying(150) NOT NULL,
    description character varying(500),
    multi_select boolean DEFAULT false NOT NULL,
    locked boolean DEFAULT false NOT NULL,
    placeholder boolean DEFAULT false NOT NULL,
    parent_set_code character varying(60),
    sort_order integer DEFAULT 0 NOT NULL
);

CREATE TABLE picklist_value (
    set_code character varying(60) NOT NULL,
    code character varying(80) NOT NULL,
    label character varying(150) NOT NULL,
    arm character varying(10) DEFAULT 'BOTH'::character varying NOT NULL,
    active boolean DEFAULT true NOT NULL,
    system_value boolean DEFAULT false NOT NULL,
    parent_code character varying(80),
    sort_order integer DEFAULT 0 NOT NULL,
    CONSTRAINT picklist_value_arm_check CHECK (((arm)::text = ANY ((ARRAY['CREDIT'::character varying, 'CAPITAL'::character varying, 'BOTH'::character varying])::text[])))
);

ALTER TABLE ONLY app_role_permission
    ADD CONSTRAINT app_role_permission_pkey PRIMARY KEY (role_code, permission);

ALTER TABLE ONLY app_role
    ADD CONSTRAINT app_role_pkey PRIMARY KEY (code);

ALTER TABLE ONLY app_setting
    ADD CONSTRAINT app_setting_pkey PRIMARY KEY (setting_key);

ALTER TABLE ONLY app_user
    ADD CONSTRAINT app_user_email_key UNIQUE (email);

ALTER TABLE ONLY app_user
    ADD CONSTRAINT app_user_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app_user_role
    ADD CONSTRAINT app_user_role_pkey PRIMARY KEY (user_id, role_code);

ALTER TABLE ONLY audit_event
    ADD CONSTRAINT audit_event_pkey PRIMARY KEY (id);

ALTER TABLE ONLY field_def
    ADD CONSTRAINT field_def_pkey PRIMARY KEY (object_api_name, api_name);

ALTER TABLE ONLY object_def
    ADD CONSTRAINT object_def_pkey PRIMARY KEY (api_name);

ALTER TABLE ONLY picklist_set
    ADD CONSTRAINT picklist_set_pkey PRIMARY KEY (code);

ALTER TABLE ONLY picklist_value
    ADD CONSTRAINT picklist_value_pkey PRIMARY KEY (set_code, code);

CREATE INDEX ix_audit_event_kind_action ON audit_event USING btree (record_kind, action, occurred_at DESC, id DESC);

CREATE INDEX ix_audit_event_link_entity ON audit_event USING btree (COALESCE(((changes -> 'after'::text) ->> 'entityId'::text), ((changes -> 'before'::text) ->> 'entityId'::text)), occurred_at DESC, id DESC) WHERE ((record_kind)::text = 'entity_link'::text);

CREATE INDEX ix_audit_event_link_person ON audit_event USING btree (COALESCE(((changes -> 'after'::text) ->> 'personId'::text), ((changes -> 'before'::text) ->> 'personId'::text)), occurred_at DESC, id DESC) WHERE ((record_kind)::text = 'entity_link'::text);

CREATE INDEX ix_audit_event_occurred ON audit_event USING btree (occurred_at DESC, id DESC);

CREATE INDEX ix_audit_event_record ON audit_event USING btree (record_kind, record_id, occurred_at DESC);

CREATE INDEX ix_audit_event_user ON audit_event USING btree (user_id, occurred_at DESC, id DESC);

ALTER TABLE ONLY app_role_permission
    ADD CONSTRAINT app_role_permission_role_code_fkey FOREIGN KEY (role_code) REFERENCES app_role(code);

ALTER TABLE ONLY app_user_role
    ADD CONSTRAINT app_user_role_role_code_fkey FOREIGN KEY (role_code) REFERENCES app_role(code);

ALTER TABLE ONLY app_user_role
    ADD CONSTRAINT app_user_role_user_id_fkey FOREIGN KEY (user_id) REFERENCES app_user(id);

ALTER TABLE ONLY field_def
    ADD CONSTRAINT field_def_object_api_name_fkey FOREIGN KEY (object_api_name) REFERENCES object_def(api_name);

ALTER TABLE ONLY picklist_set
    ADD CONSTRAINT picklist_set_parent_set_code_fkey FOREIGN KEY (parent_set_code) REFERENCES picklist_set(code);

ALTER TABLE ONLY picklist_value
    ADD CONSTRAINT picklist_value_set_code_fkey FOREIGN KEY (set_code) REFERENCES picklist_set(code);

--changeset privateinvest:002-field-registry
-- The field registry: every configurable field of every record type, and the sections that group them.
CREATE TABLE custom_field_def (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    object_api_name character varying(60) NOT NULL,
    api_name character varying(80) NOT NULL,
    label character varying(150) NOT NULL,
    help_text character varying(500),
    data_type character varying(20) NOT NULL,
    picklist_set_code character varying(60),
    expected boolean DEFAULT false NOT NULL,
    show_in_list boolean DEFAULT false NOT NULL,
    max_length integer,
    decimal_places integer,
    sort_order integer DEFAULT 0 NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64) NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by character varying(64) NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    mandatory boolean DEFAULT false NOT NULL,
    default_value jsonb,
    sensitive boolean DEFAULT false NOT NULL,
    storage character varying(10) DEFAULT 'CUSTOM'::character varying NOT NULL,
    system_key character varying(40),
    placeholder character varying(150),
    width character varying(4) DEFAULT 'HALF'::character varying NOT NULL,
    validation jsonb DEFAULT '{}'::jsonb NOT NULL,
    show_on_create boolean DEFAULT true NOT NULL,
    show_on_detail boolean DEFAULT true NOT NULL,
    list_order integer DEFAULT 0 NOT NULL,
    searchable boolean DEFAULT false NOT NULL,
    filterable boolean DEFAULT true NOT NULL,
    sortable boolean DEFAULT true NOT NULL,
    editable_after_create boolean DEFAULT true NOT NULL,
    required_locked boolean DEFAULT false NOT NULL,
    section character varying(100) DEFAULT 'Personal Information'::character varying NOT NULL,
    deleted_at timestamp with time zone,
    deleted_by character varying(64),
    display_rules jsonb DEFAULT '{}'::jsonb NOT NULL,
    formula jsonb,
    options_from character varying(80),
    arm character varying(10) DEFAULT 'BOTH'::character varying NOT NULL,
    CONSTRAINT ck_custom_field_def_arm CHECK (((arm)::text = ANY ((ARRAY['CREDIT'::character varying, 'CAPITAL'::character varying, 'BOTH'::character varying])::text[]))),
    CONSTRAINT ck_custom_field_def_display_rules CHECK ((jsonb_typeof(display_rules) = 'object'::text)),
    CONSTRAINT ck_custom_field_def_formula CHECK (((formula IS NULL) OR (jsonb_typeof(formula) = 'object'::text))),
    CONSTRAINT ck_custom_field_def_options_from CHECK (((options_from IS NULL) OR ((data_type)::text = ANY ((ARRAY['PHONE'::character varying, 'EMAIL'::character varying])::text[])))),
    CONSTRAINT ck_custom_field_def_required_locked CHECK (((NOT required_locked) OR mandatory)),
    CONSTRAINT ck_custom_field_def_system_storage CHECK ((((storage)::text = 'CUSTOM'::text) OR (system_key IS NOT NULL))),
    CONSTRAINT ck_custom_field_def_validation CHECK ((jsonb_typeof(validation) = 'object'::text)),
    CONSTRAINT custom_field_def_check CHECK ((((data_type)::text = ANY ((ARRAY['PICKLIST'::character varying, 'MULTI_PICKLIST'::character varying, 'PHONE_LIST'::character varying, 'EMAIL_LIST'::character varying])::text[])) = (picklist_set_code IS NOT NULL))),
    CONSTRAINT custom_field_def_data_type_check CHECK (((data_type)::text = ANY ((ARRAY['TEXT'::character varying, 'LONG_TEXT'::character varying, 'NUMBER'::character varying, 'CURRENCY'::character varying, 'PERCENT'::character varying, 'DATE'::character varying, 'CHECKBOX'::character varying, 'BOOLEAN'::character varying, 'PICKLIST'::character varying, 'MULTI_PICKLIST'::character varying, 'EMAIL'::character varying, 'PHONE'::character varying, 'URL'::character varying, 'USER'::character varying, 'PHONE_LIST'::character varying, 'EMAIL_LIST'::character varying])::text[]))),
    CONSTRAINT custom_field_def_decimal_places_check CHECK (((decimal_places IS NULL) OR ((decimal_places >= 0) AND (decimal_places <= 6)))),
    CONSTRAINT custom_field_def_max_length_check CHECK (((max_length IS NULL) OR ((max_length >= 1) AND (max_length <= 131072)))),
    CONSTRAINT custom_field_def_storage_check CHECK (((storage)::text = ANY ((ARRAY['CORE'::character varying, 'CHILD'::character varying, 'CUSTOM'::character varying])::text[]))),
    CONSTRAINT custom_field_def_width_check CHECK (((width)::text = ANY ((ARRAY['HALF'::character varying, 'FULL'::character varying])::text[])))
);

CREATE TABLE custom_field_section (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    object_api_name character varying(60) NOT NULL,
    name character varying(100) NOT NULL,
    description character varying(300),
    sort_order integer DEFAULT 0 NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64) DEFAULT 'migration'::character varying NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by character varying(64) DEFAULT 'migration'::character varying NOT NULL
);

ALTER TABLE ONLY custom_field_def
    ADD CONSTRAINT custom_field_def_object_api_name_api_name_key UNIQUE (object_api_name, api_name);

ALTER TABLE ONLY custom_field_def
    ADD CONSTRAINT custom_field_def_pkey PRIMARY KEY (id);

ALTER TABLE ONLY custom_field_section
    ADD CONSTRAINT custom_field_section_pkey PRIMARY KEY (id);

ALTER TABLE ONLY custom_field_def
    ADD CONSTRAINT ux_custom_field_def_id_object UNIQUE (id, object_api_name);

ALTER TABLE ONLY custom_field_def
    ADD CONSTRAINT ux_custom_field_def_system_key UNIQUE (object_api_name, system_key);

CREATE INDEX ix_custom_field_def_deleted ON custom_field_def USING btree (object_api_name, deleted_at) WHERE (deleted_at IS NOT NULL);

CREATE INDEX ix_custom_field_def_object ON custom_field_def USING btree (object_api_name, sort_order);

CREATE INDEX ix_custom_field_section_order ON custom_field_section USING btree (object_api_name, sort_order, name);

CREATE UNIQUE INDEX ux_custom_field_def_label ON custom_field_def USING btree (object_api_name, lower((label)::text)) WHERE (deleted_at IS NULL);

CREATE UNIQUE INDEX ux_custom_field_section_name ON custom_field_section USING btree (object_api_name, lower(btrim((name)::text)));

ALTER TABLE ONLY custom_field_def
    ADD CONSTRAINT custom_field_def_object_api_name_fkey FOREIGN KEY (object_api_name) REFERENCES object_def(api_name);

ALTER TABLE ONLY custom_field_def
    ADD CONSTRAINT custom_field_def_picklist_set_code_fkey FOREIGN KEY (picklist_set_code) REFERENCES picklist_set(code);

ALTER TABLE ONLY custom_field_section
    ADD CONSTRAINT custom_field_section_object_api_name_fkey FOREIGN KEY (object_api_name) REFERENCES object_def(api_name);

--changeset privateinvest:002-functions splitStatements:false
-- Value checks shared by the record tables, and the company-name key used by the similar-name check.
CREATE FUNCTION check_custom_fields() RETURNS trigger
    LANGUAGE plpgsql
    AS $_$
DECLARE
    item  RECORD;
    def   custom_field_def%ROWTYPE;
    ok    BOOLEAN;
BEGIN
    IF jsonb_typeof(NEW.custom_fields) <> 'object' THEN
        RAISE EXCEPTION 'custom_fields must be a JSON object' USING ERRCODE = 'check_violation';
    END IF;
    FOR item IN SELECT key, value FROM jsonb_each(NEW.custom_fields) LOOP
        SELECT * INTO def FROM custom_field_def
        WHERE object_api_name = TG_ARGV[0] AND api_name = item.key AND storage = 'CUSTOM'
        FOR KEY SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'There is no % custom field called %', TG_ARGV[0], item.key USING ERRCODE = 'check_violation';
        END IF;
        ok := CASE
            WHEN def.data_type IN ('TEXT', 'LONG_TEXT', 'EMAIL', 'PHONE', 'URL') THEN jsonb_typeof(item.value) = 'string'
            WHEN def.data_type IN ('NUMBER', 'CURRENCY', 'PERCENT') THEN jsonb_typeof(item.value) = 'number'
            WHEN def.data_type = 'DATE' THEN jsonb_typeof(item.value) = 'string'
                                           AND (item.value #>> '{}') ~ '^\d{4}-\d{2}-\d{2}$'
                                           AND pg_input_is_valid(item.value #>> '{}', 'date')
            WHEN def.data_type IN ('CHECKBOX', 'BOOLEAN') THEN jsonb_typeof(item.value) = 'boolean'
            -- A user value must be a real user (this replaces the old owner_id foreign key).
            WHEN def.data_type = 'USER' THEN jsonb_typeof(item.value) = 'string'
                AND EXISTS (SELECT 1 FROM app_user WHERE id = item.value #>> '{}')
            WHEN def.data_type = 'PICKLIST' THEN jsonb_typeof(item.value) = 'string'
                AND EXISTS (SELECT 1 FROM picklist_value
                            WHERE set_code = def.picklist_set_code AND code = item.value #>> '{}')
            WHEN def.data_type = 'MULTI_PICKLIST' THEN jsonb_typeof(item.value) = 'array'
                AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(item.value) e
                                WHERE jsonb_typeof(e) <> 'string'
                                   OR NOT EXISTS (SELECT 1 FROM picklist_value
                                                  WHERE set_code = def.picklist_set_code AND code = e #>> '{}'))
            WHEN def.data_type IN ('PHONE_LIST', 'EMAIL_LIST') THEN jsonb_typeof(item.value) = 'array'
                AND jsonb_array_length(item.value) BETWEEN 1 AND 10
                AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(item.value) e
                                WHERE jsonb_typeof(e) <> 'object'
                                   OR jsonb_typeof(e -> 'value') IS DISTINCT FROM 'string'
                                   OR jsonb_typeof(e -> 'primary') IS DISTINCT FROM 'boolean'
                                   OR NOT EXISTS (SELECT 1 FROM picklist_value
                                                  WHERE set_code = def.picklist_set_code AND code = e ->> 'label'))
                AND (SELECT count(*) FROM jsonb_array_elements(item.value) e WHERE (e ->> 'primary')::boolean) = 1
            ELSE FALSE
        END;
        IF NOT ok THEN
            RAISE EXCEPTION 'Value % is not valid for % custom field % (%)', item.value, TG_ARGV[0], item.key, def.data_type
                USING ERRCODE = 'check_violation';
        END IF;
    END LOOP;
    RETURN NEW;
END $_$;

CREATE FUNCTION entity_name_key(name text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    AS $$
SELECT btrim(regexp_replace(
           regexp_replace(lower(name), '\m(pty|ltd|limited|proprietary|the|inc|incorporated|llc|plc|co|company|corporation|corp)\M\.?', ' ', 'g'),
           '[^a-z0-9]+', ' ', 'g'))
$$;

CREATE FUNCTION record_fields_valid(doc jsonb) RETURNS boolean
    LANGUAGE sql IMMUTABLE
    AS $$
    SELECT jsonb_typeof(doc) = 'object'
       AND NOT EXISTS (
           SELECT 1 FROM jsonb_each(doc) f
           WHERE jsonb_typeof(f.value) <> 'object'
              OR NOT pg_input_is_valid(f.key, 'uuid')
              OR coalesce(btrim(f.value ->> 'label'), '') = ''
              OR jsonb_typeof(f.value -> 'value') IS NULL
              OR jsonb_typeof(f.value -> 'value') = 'null'
              OR jsonb_typeof(f.value -> 'version') <> 'number'
              OR NOT CASE
                  WHEN f.value ->> 'dataType' IN ('TEXT', 'LONG_TEXT', 'EMAIL', 'PHONE', 'URL', 'DATE')
                      THEN jsonb_typeof(f.value -> 'value') = 'string'
                  WHEN f.value ->> 'dataType' IN ('NUMBER', 'CURRENCY', 'PERCENT')
                      THEN jsonb_typeof(f.value -> 'value') = 'number'
                  WHEN f.value ->> 'dataType' = 'BOOLEAN'
                      THEN jsonb_typeof(f.value -> 'value') = 'boolean'
                  ELSE FALSE
              END)
       -- Labels are unique per record among fields that haven't been removed.
       AND (SELECT count(*) = count(DISTINCT lower(btrim(f.value ->> 'label')))
            FROM jsonb_each(doc) f WHERE f.value ->> 'removedAt' IS NULL)
$$;

--changeset privateinvest:002-records
-- Persons, Entities, the links between them, and saved list views.
CREATE TABLE entity (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    credit_inactive_reason character varying(80),
    capital_inactive_reason character varying(80),
    hot_since timestamp with time zone,
    possible_duplicate_reason character varying(1000),
    lms_id character varying(60),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64) NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by character varying(64) NOT NULL,
    archived_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    custom_fields jsonb DEFAULT '{}'::jsonb NOT NULL,
    record_fields jsonb DEFAULT '{}'::jsonb NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(1000),
    hidden_fields text[] DEFAULT '{}'::text[] NOT NULL,
    CONSTRAINT ck_entity_record_fields CHECK (record_fields_valid(record_fields))
);

CREATE TABLE entity_link (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    person_id uuid NOT NULL,
    entity_id uuid NOT NULL,
    role character varying(80) NOT NULL,
    job_title character varying(150),
    department character varying(150),
    date_linked date DEFAULT CURRENT_DATE NOT NULL,
    email character varying(320),
    is_primary boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64) NOT NULL
);

CREATE TABLE list_view (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    object_api_name character varying(60) NOT NULL,
    name character varying(80) NOT NULL,
    owner_id character varying(64) NOT NULL,
    shared boolean DEFAULT false NOT NULL,
    owner_scope character varying(10) DEFAULT 'ALL'::character varying NOT NULL,
    conditions jsonb DEFAULT '[]'::jsonb NOT NULL,
    logic character varying(500),
    columns jsonb DEFAULT '[]'::jsonb NOT NULL,
    sort jsonb DEFAULT '[]'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64) NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by character varying(64) NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT list_view_columns_check CHECK ((jsonb_typeof(columns) = 'array'::text)),
    CONSTRAINT list_view_conditions_check CHECK ((jsonb_typeof(conditions) = 'array'::text)),
    CONSTRAINT list_view_name_check CHECK ((btrim((name)::text) <> ''::text)),
    CONSTRAINT list_view_owner_scope_check CHECK (((owner_scope)::text = ANY ((ARRAY['ALL'::character varying, 'MINE'::character varying])::text[]))),
    CONSTRAINT list_view_sort_check CHECK ((jsonb_typeof(sort) = 'array'::text))
);

CREATE TABLE list_view_pin (
    user_id character varying(64) NOT NULL,
    object_api_name character varying(60) NOT NULL,
    view_id uuid NOT NULL
);

CREATE TABLE person (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    credit_inactive_reason character varying(80),
    capital_inactive_reason character varying(80),
    hot_since timestamp with time zone,
    possible_duplicate_reason character varying(1000),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(64) NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by character varying(64) NOT NULL,
    archived_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    custom_fields jsonb DEFAULT '{}'::jsonb NOT NULL,
    record_fields jsonb DEFAULT '{}'::jsonb NOT NULL,
    archived_by character varying(64),
    archive_reason character varying(1000),
    hidden_fields text[] DEFAULT '{}'::text[] NOT NULL,
    CONSTRAINT ck_person_record_fields CHECK (record_fields_valid(record_fields))
);

ALTER TABLE ONLY entity_link
    ADD CONSTRAINT entity_link_person_id_entity_id_key UNIQUE (person_id, entity_id);

ALTER TABLE ONLY entity_link
    ADD CONSTRAINT entity_link_pkey PRIMARY KEY (id);

ALTER TABLE ONLY entity
    ADD CONSTRAINT entity_pkey PRIMARY KEY (id);

ALTER TABLE ONLY list_view_pin
    ADD CONSTRAINT list_view_pin_pkey PRIMARY KEY (user_id, object_api_name);

ALTER TABLE ONLY list_view
    ADD CONSTRAINT list_view_pkey PRIMARY KEY (id);

ALTER TABLE ONLY person
    ADD CONSTRAINT person_pkey PRIMARY KEY (id);

CREATE INDEX ix_entity_archived ON entity USING btree (archived_at) WHERE (archived_at IS NOT NULL);

CREATE INDEX ix_entity_custom_fields ON entity USING gin (custom_fields jsonb_path_ops);

CREATE INDEX ix_entity_link_entity ON entity_link USING btree (entity_id);

CREATE INDEX ix_entity_name_key_trgm ON entity USING gin (entity_name_key((custom_fields ->> 'name'::text)) gin_trgm_ops);

CREATE INDEX ix_list_view_owner ON list_view USING btree (object_api_name, owner_id);

CREATE INDEX ix_person_archived ON person USING btree (archived_at) WHERE (archived_at IS NOT NULL);

CREATE INDEX ix_person_custom_fields ON person USING gin (custom_fields);

CREATE UNIQUE INDEX ux_entity_abn ON entity USING btree (((custom_fields ->> 'abn'::text))) WHERE ((archived_at IS NULL) AND ((custom_fields ->> 'abn'::text) IS NOT NULL));

CREATE UNIQUE INDEX ux_entity_acn ON entity USING btree (((custom_fields ->> 'acn'::text))) WHERE ((archived_at IS NULL) AND ((custom_fields ->> 'acn'::text) IS NOT NULL));

CREATE UNIQUE INDEX ux_entity_link_primary ON entity_link USING btree (person_id) WHERE is_primary;

CREATE UNIQUE INDEX ux_list_view_owner_name ON list_view USING btree (object_api_name, owner_id, lower(btrim((name)::text))) WHERE (NOT shared);

CREATE UNIQUE INDEX ux_list_view_shared_name ON list_view USING btree (object_api_name, lower(btrim((name)::text))) WHERE shared;

CREATE TRIGGER trg_entity_custom_fields BEFORE INSERT OR UPDATE OF custom_fields ON entity FOR EACH ROW EXECUTE FUNCTION check_custom_fields('entity');

CREATE TRIGGER trg_person_custom_fields BEFORE INSERT OR UPDATE OF custom_fields ON person FOR EACH ROW EXECUTE FUNCTION check_custom_fields('person');

ALTER TABLE ONLY entity_link
    ADD CONSTRAINT entity_link_entity_id_fkey FOREIGN KEY (entity_id) REFERENCES entity(id);

ALTER TABLE ONLY entity_link
    ADD CONSTRAINT entity_link_person_id_fkey FOREIGN KEY (person_id) REFERENCES person(id);

ALTER TABLE ONLY list_view
    ADD CONSTRAINT list_view_object_api_name_fkey FOREIGN KEY (object_api_name) REFERENCES object_def(api_name);

ALTER TABLE ONLY list_view
    ADD CONSTRAINT list_view_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES app_user(id);

ALTER TABLE ONLY list_view_pin
    ADD CONSTRAINT list_view_pin_object_api_name_fkey FOREIGN KEY (object_api_name) REFERENCES object_def(api_name);

ALTER TABLE ONLY list_view_pin
    ADD CONSTRAINT list_view_pin_user_id_fkey FOREIGN KEY (user_id) REFERENCES app_user(id);

ALTER TABLE ONLY list_view_pin
    ADD CONSTRAINT list_view_pin_view_id_fkey FOREIGN KEY (view_id) REFERENCES list_view(id) ON DELETE CASCADE;
