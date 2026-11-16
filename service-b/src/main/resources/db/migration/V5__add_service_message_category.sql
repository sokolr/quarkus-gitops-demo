-- Expand only: old Service B pods can still read and write this table.
ALTER TABLE service_message
    ADD COLUMN category VARCHAR(32) DEFAULT 'general';
