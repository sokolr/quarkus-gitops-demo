package gitops.demo.web;

import gitops.demo.data.MessageRepository;
import gitops.demo.data.MessageRepository.StoredInfo;
import gitops.demo.dto.CreateInfoRequest;
import gitops.demo.dto.DescriptionRequest;
import gitops.demo.dto.InfoResponse;
import gitops.demo.dto.MessageRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.concurrent.ThreadLocalRandom;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
@Path("/api/info")
@Produces(MediaType.APPLICATION_JSON)
public class RestController {

    @Inject
    MessageRepository messages;

    @ConfigProperty(name = "demo.fault.percent", defaultValue = "0")
    int faultPercent;

    @GET
    public Response info() {
        if (faultPercent > 0 && ThreadLocalRandom.current().nextInt(100) < faultPercent) {
            throw new WebApplicationException("Deliberate canary failure", 503);
        }
        StoredInfo info = messages.read();
        return responseOrNoContent(info);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(CreateInfoRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()
                || request.message().length() > 255
                || (request.description() != null
                        && (request.description().isBlank() || request.description().length() > 255))) {
            throw new WebApplicationException("message must contain 1 to 255 characters; "
                    + "description must be at most 255 characters", 400);
        }
        StoredInfo created = messages.create(request.message(), request.description());
        return Response.status(Response.Status.CREATED).entity(response(created)).build();
    }

    @PUT
    @Path("/message")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateMessage(MessageRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()
                || request.message().length() > 255) {
            throw new WebApplicationException("message must contain 1 to 255 characters", 400);
        }
        return responseOrNoContent(messages.updateMessage(request.message()));
    }

    @PUT
    @Path("/description")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateDescription(DescriptionRequest request) {
        if (request == null || request.description() == null || request.description().isBlank()
                || request.description().length() > 255) {
            throw new WebApplicationException("description must contain 1 to 255 characters", 400);
        }
        return responseOrNoContent(messages.updateDescription(request.description()));
    }

    private Response responseOrNoContent(StoredInfo info) {
        return info.message() == null
                ? Response.noContent().build()
                : Response.ok(response(info)).build();
    }

    private InfoResponse response(StoredInfo info) {
        return new InfoResponse("B", info.message(), info.description());
    }
}
