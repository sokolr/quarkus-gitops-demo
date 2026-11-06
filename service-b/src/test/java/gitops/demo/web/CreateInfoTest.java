package gitops.demo.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

@QuarkusTest
class CreateInfoTest {

    @Inject
    DataSource dataSource;

    @Test
    void postAddsRecordAndEmptyTableReturnsNoContent() throws Exception {
        String originalMessage;
        String originalDescription;
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT message, description FROM service_message WHERE id = 1")) {
            assertTrue(rows.next());
            originalMessage = rows.getString(1);
            originalDescription = rows.getString(2);
        }

        try {
            given().contentType(ContentType.JSON)
                    .body("{\"message\":\"Created\",\"description\":\"Via POST\"}")
                    .post("/api/info")
                    .then().statusCode(201)
                    .body("service", equalTo("B"))
                    .body("message", equalTo("Created"))
                    .body("description", equalTo("Via POST"));

            given().contentType(ContentType.JSON).body("{\"message\":\"Updated\"}")
                    .put("/api/info/message").then().statusCode(200)
                    .body("message", equalTo("Updated"));
            given().contentType(ContentType.JSON).body("{\"description\":\"Updated details\"}")
                    .put("/api/info/description").then().statusCode(200)
                    .body("message", equalTo("Updated"))
                    .body("description", equalTo("Updated details"));

            try (var connection = dataSource.getConnection();
                    var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT id, message, description FROM service_message ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
                assertEquals(originalMessage, rows.getString(2));
                assertTrue(rows.next());
                assertTrue(rows.getInt(1) > 1);
                assertEquals("Updated", rows.getString(2));
                assertEquals("Updated details", rows.getString(3));
                assertFalse(rows.next());
            }
            given().get("/api/info").then().statusCode(200)
                    .body("message", equalTo("Updated"));

            try (var connection = dataSource.getConnection();
                    var statement = connection.createStatement()) {
                assertEquals(2, statement.executeUpdate("DELETE FROM service_message"));
            }

            var emptyResponse = given().when().get("/api/info");
            emptyResponse.then().statusCode(204);
            assertEquals("", emptyResponse.asString());
            given().contentType(ContentType.JSON).body("{\"message\":\"Nothing to update\"}")
                    .put("/api/info/message").then().statusCode(204);
            given().contentType(ContentType.JSON).body("{\"description\":\"Nothing to update\"}")
                    .put("/api/info/description").then().statusCode(204);

            given().contentType(ContentType.JSON)
                    .body("{\"message\":\"After empty\"}")
                    .post("/api/info").then().statusCode(201)
                    .body("message", equalTo("After empty"));

            try (var connection = dataSource.getConnection();
                    var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT message, description FROM service_message")) {
                assertTrue(rows.next());
                assertEquals("After empty", rows.getString(1));
                assertFalse(rows.next());
            }
        } finally {
            try (var connection = dataSource.getConnection();
                    var delete = connection.createStatement()) {
                delete.executeUpdate("DELETE FROM service_message");
            }
            try (var connection = dataSource.getConnection();
                    var statement = connection.prepareStatement(
                            "INSERT INTO service_message (id, message, description) VALUES (1, ?, ?)")) {
                statement.setString(1, originalMessage);
                statement.setString(2, originalDescription);
                statement.executeUpdate();
            }
        }
    }

    @Test
    void invalidCreateReturnsBadRequest() {
        for (String body : new String[] {"{}", "{\"message\":\" \"}",
                "{\"message\":\"ok\",\"description\":\" \"}",
                "{\"message\":\"" + "x".repeat(256) + "\"}"}) {
            given().contentType(ContentType.JSON).body(body)
                    .post("/api/info").then().statusCode(400);
        }
    }
}
