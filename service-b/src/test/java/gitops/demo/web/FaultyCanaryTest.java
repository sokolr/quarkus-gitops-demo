package gitops.demo.web;

import static io.restassured.RestAssured.given;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FaultyCanaryTest.FaultProfile.class)
class FaultyCanaryTest {

    public static class FaultProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("demo.fault.percent", "100");
        }
    }

    @Test
    void onlyApiGetFailsWhileReadinessRemainsUp() {
        given().get("/api/info").then().statusCode(503);
        given().get("/q/health/ready").then().statusCode(200);
    }
}
