--liquibase formatted sql

--changeset privateinvest:001_initial_infrastructure_setup
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Java configuration store (AES encryption keys, infrastructure parameters)
CREATE TABLE IF NOT EXISTS java_config (
    key   VARCHAR(100) PRIMARY KEY,
    value TEXT         NOT NULL
);

INSERT INTO java_config (key, value) VALUES
    ('aesSecretKey', 'PrivateInvestCRMKey16ByteLength!'),
    ('aesIv',        'PrivateInvestIv16')
ON CONFLICT (key) DO NOTHING;

--rollback DROP TABLE IF EXISTS java_config;
