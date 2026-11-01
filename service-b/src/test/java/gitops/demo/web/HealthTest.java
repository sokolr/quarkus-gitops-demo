package gitops.demo.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
class HealthTest {

    @Test
    void reportsLiveness() {
        given()
                .when().get("/q/health/live")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("status", equalTo("UP"));
    }

    @Test
    void reportsReadiness() {
        given()
                .when().get("/q/health/ready")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("status", equalTo("UP"));
    }
}
