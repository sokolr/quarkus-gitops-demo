package gitops.demo.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.flywaydb.core.Flyway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

@QuarkusTest
class RestControllerTest {

    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    @Inject
    DataSource dataSource;

    private final List<String> logMessages = new CopyOnWriteArrayList<>();
    private final Logger requestLogger = Logger.getLogger(CorrelationIdFilter.class.getName());
    private final Handler logHandler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logMessages.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void captureRequestLogs() {
        requestLogger.addHandler(logHandler);
    }

    @AfterEach
    void releaseLogHandler() {
        requestLogger.removeHandler(logHandler);
    }

    @Test
    void readsAndUpdatesMessageInPostgres() throws Exception {
        String original = databaseMessage();
        String replacement = "Changed in PostgreSQL " + UUID.randomUUID();

        try {
            given()
                    .when().get("/api/info")
                    .then()
                    .statusCode(200)
                    .contentType(ContentType.JSON)
                    .body("service", equalTo("B"))
                    .body("message", equalTo(original));

            given()
                    .contentType(ContentType.JSON)
                    .body("{\"message\":\"" + replacement + "\"}")
                    .when().put("/api/info/message")
                    .then()
                    .statusCode(200)
                    .body("service", equalTo("B"))
                    .body("message", equalTo(replacement));

            assertEquals(replacement, databaseMessage());
            given()
                    .when().get("/api/info")
                    .then()
                    .statusCode(200)
                    .body("message", equalTo(replacement));
        } finally {
            given().contentType(ContentType.JSON)
                    .body("{\"message\":\"" + original + "\"}")
                    .put("/api/info/message")
                    .then().statusCode(200);
        }
    }

    @Test
    void rejectsInvalidMessages() {
        for (String body : List.of("{}", "{\"message\":\"\"}", "{\"message\":\"   \"}")) {
            given().contentType(ContentType.JSON)
                    .body(body)
                    .put("/api/info/message")
                    .then().statusCode(400);
        }

        given().contentType(ContentType.JSON)
                .body("{\"message\":\"" + "x".repeat(501) + "\"}")
                .put("/api/info/message")
                .then().statusCode(400);
    }

    @Test
    void updatesOptionalDescription() throws Exception {
        String original = databaseDescription();
        String replacement = "Description " + UUID.randomUUID();

        try {
            given().contentType(ContentType.JSON)
                    .body("{\"description\":\"" + replacement + "\"}")
                    .put("/api/info/description")
                    .then().statusCode(200)
                    .body("message", equalTo(databaseMessage()))
                    .body("description", equalTo(replacement));

            assertEquals(replacement, databaseDescription());
            given().get("/api/info")
                    .then().statusCode(200)
                    .body("description", equalTo(replacement));
        } finally {
            setDescription(original);
        }
    }

    @Test
    void rejectsInvalidDescriptions() {
        for (String body : List.of("{}", "{\"description\":null}", "{\"description\":\"\"}",
                "{\"description\":\"   \"}", "{\"description\":\"" + "x".repeat(501) + "\"}")) {
            given().contentType(ContentType.JSON)
                    .body(body)
                    .put("/api/info/description")
                    .then().statusCode(400);
        }
    }

    @Test
    void appliesBothVersionedMigrations() throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "SELECT version, success FROM flyway_schema_history WHERE version IN ('1', '2') ORDER BY version");
                var rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertEquals("1", rows.getString(1));
            assertTrue(rows.getBoolean(2));
            assertTrue(rows.next());
            assertEquals("2", rows.getString(1));
            assertTrue(rows.getBoolean(2));
        }

        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "SELECT is_nullable, character_maximum_length FROM information_schema.columns "
                                + "WHERE table_name = 'service_message' AND column_name = 'description'");
                var rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertEquals("YES", rows.getString(1));
            assertEquals(500, rows.getInt(2));
        }
    }

    @Test
    void v1ClientWorksAgainstV2Schema(@TempDir Path migrations) throws Exception {
        copyMigration(migrations, "V1__create_service_message.sql");

        String originalMessage = databaseMessage();
        String originalDescription = databaseDescription();
        String replacement = "V1 write " + UUID.randomUUID();
        try {
            setDescription("V2 value to preserve");

            // This is the V1 Flyway view and V1 SQL after V2 has already been applied.
            var v1Flyway = Flyway.configure().dataSource(dataSource)
                    .locations("filesystem:" + migrations).load();
            assertEquals(0, v1Flyway.migrate().migrationsExecuted);
            try (var connection = dataSource.getConnection();
                    var statement = connection.prepareStatement(
                            "UPDATE service_message SET message = ? WHERE id = 1")) {
                statement.setString(1, replacement);
                assertEquals(1, statement.executeUpdate());
            }

            assertEquals(replacement, databaseMessage());
            assertEquals("V2 value to preserve", databaseDescription());
        } finally {
            try (var connection = dataSource.getConnection();
                    var statement = connection.prepareStatement(
                            "UPDATE service_message SET message = ? WHERE id = 1")) {
                statement.setString(1, originalMessage);
                statement.executeUpdate();
            }
            setDescription(originalDescription);
        }
    }

    @Test
    void upgradesPopulatedV1DatabaseWithoutLosingData(@TempDir Path migrations) throws Exception {
        copyMigration(migrations, "V1__create_service_message.sql");
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String persisted = "Saved before V2 " + UUID.randomUUID();

        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        try {
            var v1 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("filesystem:" + migrations).load();
            assertEquals(1, v1.migrate().migrationsExecuted);

            try (var connection = dataSource.getConnection();
                    var statement = connection.prepareStatement(
                            "UPDATE " + schema + ".service_message SET message = ? WHERE id = 1")) {
                statement.setString(1, persisted);
                assertEquals(1, statement.executeUpdate());
            }

            copyMigration(migrations, "V2__add_service_description.sql");
            var v2 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("filesystem:" + migrations).load();
            assertEquals(1, v2.migrate().migrationsExecuted);

            try (var connection = dataSource.getConnection();
                    var statement = connection.prepareStatement(
                            "SELECT message, description FROM " + schema + ".service_message WHERE id = 1");
                    var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(persisted, rows.getString(1));
                assertNull(rows.getString(2));
            }
        } finally {
            try (var connection = dataSource.getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void returnsAndLogsIncomingCorrelationId() {
        String correlationId = "caller-" + UUID.randomUUID();

        given()
                .header(CORRELATION_HEADER, correlationId)
                .when().get("/api/info")
                .then()
                .statusCode(200)
                .header(CORRELATION_HEADER, correlationId);

        assertRequestLogged(correlationId);
    }

    @Test
    void generatesCorrelationIdWhenHeaderIsMissing() {
        String correlationId = given()
                .when().get("/api/info")
                .then()
                .statusCode(200)
                .extract().header(CORRELATION_HEADER);

        assertEquals(correlationId, UUID.fromString(correlationId).toString());
        assertRequestLogged(correlationId);
    }

    @Test
    void generatesCorrelationIdWhenHeaderIsEmpty() {
        String correlationId = given()
                .header(CORRELATION_HEADER, "")
                .when().get("/api/info")
                .then()
                .statusCode(200)
                .extract().header(CORRELATION_HEADER);

        assertEquals(correlationId, UUID.fromString(correlationId).toString());
        assertRequestLogged(correlationId);
    }

    @Test
    void generatesCorrelationIdWhenHeaderIsBlank() {
        String correlationId = given()
                .header(CORRELATION_HEADER, "   ")
                .when().get("/api/info")
                .then()
                .statusCode(200)
                .extract().header(CORRELATION_HEADER);

        assertEquals(correlationId, UUID.fromString(correlationId).toString());
    }

    @Test
    void keepsSequentialRequestsIndependent() {
        String suppliedId = "first-" + UUID.randomUUID();
        given()
                .header(CORRELATION_HEADER, suppliedId)
                .when().get("/api/info")
                .then()
                .statusCode(200)
                .header(CORRELATION_HEADER, suppliedId);

        String generatedId = given()
                .when().get("/api/info")
                .then()
                .statusCode(200)
                .extract().header(CORRELATION_HEADER);
        String nextGeneratedId = given()
                .when().get("/api/info")
                .then()
                .statusCode(200)
                .extract().header(CORRELATION_HEADER);

        assertNotEquals(suppliedId, generatedId);
        assertNotEquals(generatedId, nextGeneratedId);
        assertRequestLogged(suppliedId);
        assertRequestLogged(generatedId);
        assertRequestLogged(nextGeneratedId);
    }

    @Test
    void keepsParallelRequestsIndependent() throws Exception {
        String batchId = UUID.randomUUID().toString();
        List<String> ids = IntStream.range(0, 12)
                .mapToObj(index -> batchId + "-" + index)
                .toList();
        List<Callable<Void>> requests = ids.stream()
                .<Callable<Void>>map(id -> () -> {
                    given()
                            .header(CORRELATION_HEADER, id)
                            .when().get("/api/info")
                            .then()
                            .statusCode(200)
                            .header(CORRELATION_HEADER, id);
                    return null;
                })
                .toList();

        try (var executor = Executors.newFixedThreadPool(6)) {
            for (var result : executor.invokeAll(requests, 20, TimeUnit.SECONDS)) {
                result.get();
            }
        }

        ids.forEach(this::assertRequestLogged);
    }

    private void assertRequestLogged(String correlationId) {
        assertTrue(logMessages.stream().anyMatch(message -> message.contains("correlationId=" + correlationId + " ")
                && message.contains("method=GET")
                && message.contains("path=/api/info")
                && message.contains("status=200")),
                () -> "Missing completed request log for " + correlationId + ": " + logMessages);
    }

    private String databaseMessage() throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("SELECT message FROM service_message WHERE id = 1");
                var rows = statement.executeQuery()) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private String databaseDescription() throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("SELECT description FROM service_message WHERE id = 1");
                var rows = statement.executeQuery()) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private void setDescription(String description) throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "UPDATE service_message SET description = ? WHERE id = 1")) {
            statement.setString(1, description);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void copyMigration(Path directory, String filename) throws Exception {
        try (var migration = getClass().getResourceAsStream("/db/migration/" + filename)) {
            assertNotNull(migration);
            Files.copy(migration, directory.resolve(filename));
        }
    }
}
