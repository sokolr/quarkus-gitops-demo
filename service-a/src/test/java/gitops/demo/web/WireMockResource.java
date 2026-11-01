package gitops.demo.web;

import java.util.Map;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public class WireMockResource implements QuarkusTestResourceLifecycleManager {

    static WireMockServer server;

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(0);
        server.start();
        return Map.of("quarkus.rest-client.service-b.url", server.baseUrl());
    }

    @Override
    public void stop() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
        server = null;
    }
}
