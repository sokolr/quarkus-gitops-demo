package gitops.demo.client;

import gitops.demo.dto.CreateInfoRequest;
import gitops.demo.dto.DescriptionRequest;
import gitops.demo.dto.InfoResponse;
import gitops.demo.dto.MessageRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
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

    @PUT
    @Path("/api/info/message")
    @Consumes(MediaType.APPLICATION_JSON)
    InfoResponse updateMessage(@HeaderParam("X-Correlation-ID") String correlationId, MessageRequest request);

    @PUT
    @Path("/api/info/description")
    @Consumes(MediaType.APPLICATION_JSON)
    InfoResponse updateDescription(@HeaderParam("X-Correlation-ID") String correlationId, DescriptionRequest request);
}
