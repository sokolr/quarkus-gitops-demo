package gitops.demo.messaging;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

@QuarkusTest
class OutboxTest {

    @Inject
    DataSource dataSource;

    @Test
    void committedWriteCreatesOneVersionedEvent() throws Exception {
        String message = "Outbox " + UUID.randomUUID();
        long aggregateId = 0;
        try {
            given().contentType(ContentType.JSON)
                    .body("{\"message\":\"" + message + "\"}")
                    .post("/api/info").then().statusCode(201);

            try (var connection = dataSource.getConnection();
                    var statement = connection.prepareStatement(
                            "SELECT s.id, o.event_id::text, o.payload->>'eventType', "
                                    + "o.payload->>'message', o.payload->>'schemaVersion', o.published_at "
                                    + "FROM service_message s JOIN outbox_event o ON o.aggregate_id = s.id "
                                    + "WHERE s.message = ?")) {
                statement.setString(1, message);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    aggregateId = rows.getLong(1);
                    UUID.fromString(rows.getString(2));
                    assertEquals("InfoCreated", rows.getString(3));
                    assertEquals(message, rows.getString(4));
                    assertEquals("1", rows.getString(5));
                    assertNull(rows.getTimestamp(6));
                    assertFalse(rows.next());
                }
            }
        } finally {
            if (aggregateId != 0) {
                try (var connection = dataSource.getConnection();
                        var deleteEvents = connection.prepareStatement(
                                "DELETE FROM outbox_event WHERE aggregate_id = ?");
                        var deleteMessage = connection.prepareStatement(
                                "DELETE FROM service_message WHERE id = ?")) {
                    deleteEvents.setLong(1, aggregateId);
                    deleteEvents.executeUpdate();
                    deleteMessage.setLong(1, aggregateId);
                    deleteMessage.executeUpdate();
                }
            }
        }
    }

    @Test
    void outboxInsertFailureRollsBackBusinessWrite() throws Exception {
        String message = "Rolled back " + UUID.randomUUID();
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE OR REPLACE FUNCTION reject_outbox_test() RETURNS trigger "
                    + "LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'outbox rejected'; END $$");
            statement.execute("CREATE TRIGGER reject_outbox_test BEFORE INSERT ON outbox_event "
                    + "FOR EACH ROW EXECUTE FUNCTION reject_outbox_test()");
        }
        try {
            given().contentType(ContentType.JSON)
                    .body("{\"message\":\"" + message + "\"}")
                    .post("/api/info").then().statusCode(500);
            try (var connection = dataSource.getConnection();
                    var statement = connection.prepareStatement(
                            "SELECT count(*) FROM service_message WHERE message = ?")) {
                statement.setString(1, message);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getLong(1));
                }
            }
        } finally {
            try (var connection = dataSource.getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER IF EXISTS reject_outbox_test ON outbox_event");
                statement.execute("DROP FUNCTION IF EXISTS reject_outbox_test()");
            }
        }
    }
}
