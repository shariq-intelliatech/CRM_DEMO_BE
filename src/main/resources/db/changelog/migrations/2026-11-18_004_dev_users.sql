--liquibase formatted sql

-- Dev users for local development and tests (X-Dev-User header, crm.auth.dev-users=true). Production runs
-- Liquibase with contexts=default, so these never reach it; real users come from sign-in.

--changeset privateinvest:004-dev-users context:dev
INSERT INTO app_user (id, name, email, initials, active) VALUES ('u1', 'Sophie Turner', 'sophie.turner@privateinvest.example', 'ST', true);
INSERT INTO app_user (id, name, email, initials, active) VALUES ('u2', 'Daniel Ng', 'daniel.ng@privateinvest.example', 'DN', true);
INSERT INTO app_user (id, name, email, initials, active) VALUES ('u3', 'Priya Raman', 'priya.raman@privateinvest.example', 'PR', true);
INSERT INTO app_user (id, name, email, initials, active) VALUES ('u4', 'Marcus Webb', 'marcus.webb@privateinvest.example', 'MW', true);
INSERT INTO app_user_role (user_id, role_code) VALUES ('u1', 'ADMIN');
INSERT INTO app_user_role (user_id, role_code) VALUES ('u2', 'USER');
INSERT INTO app_user_role (user_id, role_code) VALUES ('u3', 'USER');
INSERT INTO app_user_role (user_id, role_code) VALUES ('u4', 'USER');
--rollback DELETE FROM app_user_role; DELETE FROM app_user;
