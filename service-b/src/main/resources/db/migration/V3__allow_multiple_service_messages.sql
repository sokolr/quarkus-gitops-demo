ALTER TABLE service_message DROP CONSTRAINT service_message_id_check;

CREATE SEQUENCE service_message_id_seq START WITH 2 OWNED BY service_message.id;

ALTER TABLE service_message ALTER COLUMN id SET DEFAULT nextval('service_message_id_seq');
