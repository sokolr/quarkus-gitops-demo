package gitops.demo.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
class RestControllerTest {

    private static final String CORRELATION_HEADER = "X-Correlation-ID";

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
//
//    @Test
//    void returnsServiceInfo() {
//        given()
//                .when().get("/api/info")
//                .then()
//                .statusCode(200)
//                .contentType(ContentType.JSON)
//                .body("service", equalTo("B"))
//                .body("message", equalTo("Hello from Service B"));
//    }

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
}
