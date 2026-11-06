package gitops.demo.client;

import gitops.demo.dto.CreateInfoRequest;
import gitops.demo.dto.InfoResponse;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "service-b")
public interface ServiceBClient {

    @GET
    @Path("/api/info")
    InfoResponse info(@HeaderParam("X-Correlation-ID") String correlationId);

    @POST
    @Path("/api/info")
    @Consumes(MediaType.APPLICATION_JSON)
    InfoResponse create(@HeaderParam("X-Correlation-ID") String correlationId, CreateInfoRequest request);
}
