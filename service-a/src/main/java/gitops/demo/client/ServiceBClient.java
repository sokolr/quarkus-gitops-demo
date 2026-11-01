package gitops.demo.client;

import gitops.demo.dto.InfoResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "service-b")
public interface ServiceBClient {

    @GET
    @Path("/api/info")
    InfoResponse info(@HeaderParam("X-Correlation-ID") String correlationId);
}
