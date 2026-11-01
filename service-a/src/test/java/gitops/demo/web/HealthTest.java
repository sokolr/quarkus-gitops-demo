package gitops.demo.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
class HealthTest {

    @Test
    void reportsLivenessAndReadiness() {
        assertNull(WireMockResource.server, "Health checks must not start the Service B test server");
        for (String path : new String[]{"/q/health/live", "/q/health/ready"}) {
            given().when().get(path)
                    .then().statusCode(200)
                    .contentType(ContentType.JSON)
                    .body("status", equalTo("UP"));
        }
    }
}
