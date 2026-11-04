package gitops.demo.web;

import gitops.demo.data.MessageRepository;
import gitops.demo.data.MessageRepository.StoredInfo;
import gitops.demo.dto.DescriptionRequest;
import gitops.demo.dto.InfoResponse;
import gitops.demo.dto.MessageRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
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
    public InfoResponse info() {
        if (faultPercent > 0 && ThreadLocalRandom.current().nextInt(100) < faultPercent) {
            throw new WebApplicationException("Deliberate canary failure", 503);
        }
        return response(messages.read());
    }

    @PUT
    @Path("/message")
    @Consumes(MediaType.APPLICATION_JSON)
    public InfoResponse updateMessage(MessageRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()
                || request.message().length() > 500) {
            throw new WebApplicationException("message must contain 1 to 500 characters", 400);
        }
        return response(messages.updateMessage(request.message()));
    }

    @PUT
    @Path("/description")
    @Consumes(MediaType.APPLICATION_JSON)
    public InfoResponse updateDescription(DescriptionRequest request) {
        if (request == null || request.description() == null || request.description().isBlank()
                || request.description().length() > 500) {
            throw new WebApplicationException("description must contain 1 to 500 characters", 400);
        }
        return response(messages.updateDescription(request.description()));
    }

    private InfoResponse response(StoredInfo info) {
        return new InfoResponse("B", info.message(), info.description());
    }
}
