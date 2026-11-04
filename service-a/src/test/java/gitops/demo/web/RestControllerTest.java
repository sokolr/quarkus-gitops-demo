package gitops.demo.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
@QuarkusTestResource(value = WireMockResource.class, restrictToAnnotatedClass = true)
class RestControllerTest {

    private final List<String> failureLogs = new CopyOnWriteArrayList<>();
    private final Logger controllerLogger = Logger.getLogger(RestController.class.getName());
    private final Handler logHandler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            failureLogs.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void resetStubs() {
        WireMockResource.server.resetAll();
        controllerLogger.addHandler(logHandler);
    }

    @AfterEach
    void releaseLogHandler() {
        controllerLogger.removeHandler(logHandler);
    }

    @Test
    void returnsCombinedResponseAndPropagatesCorrelationId() {
        WireMockResource.server.stubFor(get(urlEqualTo("/api/info"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"service\":\"B\",\"message\":\"Hello from Service B\","
                                + "\"description\":\"Added in V2\"}")));

        given()
                .header("X-Correlation-ID", "demo-123")
                .when().get("/api/aggregate")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .header("X-Correlation-ID", "demo-123")
                .body("service", equalTo("A"))
                .body("downstream.service", equalTo("B"))
                .body("downstream.message", equalTo("Hello from Service B"));

        WireMockResource.server.verify(getRequestedFor(urlEqualTo("/api/info"))
                .withHeader("X-Correlation-ID", com.github.tomakehurst.wiremock.client.WireMock.equalTo("demo-123")));
    }

    @Test
    void generatesAndPropagatesCorrelationId() {
        WireMockResource.server.stubFor(get(urlEqualTo("/api/info"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"service\":\"B\",\"message\":\"ok\"}")));

        String id = given().when().get("/api/aggregate")
                .then().statusCode(200)
                .extract().header("X-Correlation-ID");

        assertEquals(id, UUID.fromString(id).toString());
        WireMockResource.server.verify(getRequestedFor(urlEqualTo("/api/info"))
                .withHeader("X-Correlation-ID", com.github.tomakehurst.wiremock.client.WireMock.equalTo(id)));
    }

    @Test
    void mapsServiceBErrorToBadGateway() {
        WireMockResource.server.stubFor(get(urlEqualTo("/api/info"))
                .willReturn(aResponse().withStatus(503)));

        given().header("X-Correlation-ID", "failure-123")
                .when().get("/api/aggregate")
                .then().statusCode(502)
                .contentType(ContentType.JSON)
                .header("X-Correlation-ID", "failure-123")
                .body("error", equalTo("SERVICE_B_UNAVAILABLE"));
        assertFailureLogged("failure-123", "reason=HTTP_ERROR upstreamStatus=503");
    }

    @Test
    void mapsBrokenConnectionToBadGateway() {
        WireMockResource.server.stubFor(get(urlEqualTo("/api/info"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        given().header("X-Correlation-ID", "disconnected-123")
                .when().get("/api/aggregate")
                .then().statusCode(502)
                .contentType(ContentType.JSON)
                .header("X-Correlation-ID", "disconnected-123")
                .body("error", equalTo("SERVICE_B_UNAVAILABLE"));
        assertFailureLogged("disconnected-123", "reason=CONNECTION_ERROR");
    }

    @Test
    void timesOutWhenServiceBIsSlow() {
        WireMockResource.server.stubFor(get(urlEqualTo("/api/info"))
                .willReturn(aResponse().withStatus(200)
                        .withFixedDelay(2500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"service\":\"B\",\"message\":\"late\"}")));

        given().header("X-Correlation-ID", "slow-123")
                .when().get("/api/aggregate")
                .then().statusCode(502)
                .header("X-Correlation-ID", "slow-123")
                .body("error", equalTo("SERVICE_B_UNAVAILABLE"));
        assertFailureLogged("slow-123", "reason=TIMEOUT");
    }

    private void assertFailureLogged(String id, String detail) {
        assertTrue(failureLogs.stream().anyMatch(message -> message.contains("correlationId=" + id + " ")
                && message.contains(detail)),
                () -> "Missing failure log for " + id + " (" + detail + "): " + failureLogs);
    }
}
