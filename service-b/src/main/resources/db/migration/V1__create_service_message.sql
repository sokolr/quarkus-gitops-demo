CREATE TABLE service_message (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    message VARCHAR(500) NOT NULL
);

INSERT INTO service_message (id, message)
VALUES (1, 'Hello from Service B!!!');
