package gitops.demo.web;

import gitops.demo.client.ServiceBClient;
import gitops.demo.dto.AggregateResponse;
import gitops.demo.dto.CreateInfoRequest;
import gitops.demo.dto.DescriptionRequest;
import gitops.demo.dto.ErrorResponse;
import gitops.demo.dto.InfoResponse;
import gitops.demo.dto.MessageRequest;
import jakarta.ws.rs.Consumes;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import java.util.function.Supplier;

@Path("/api/aggregate")
public class RestController {

    private static final Logger LOG = Logger.getLogger(RestController.class);

    @Inject
    @RestClient
    ServiceBClient serviceB;

    @Inject
    CorrelationIdContext correlationId;

    @GET
    public Response aggregate() {
        return callServiceB(() -> aggregateResponse(serviceB.info(correlationId.getId())));
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(CreateInfoRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()
                || request.message().length() > 255
                || (request.description() != null
                        && (request.description().isBlank() || request.description().length() > 255))) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        return callServiceB(() -> Response.status(Response.Status.CREATED)
                .entity(new AggregateResponse("A", serviceB.create(correlationId.getId(), request)))
                .type(MediaType.APPLICATION_JSON).build());
    }

    @PUT
    @Path("/message")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateMessage(MessageRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()
                || request.message().length() > 255) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        return callServiceB(() -> aggregateResponse(serviceB.updateMessage(correlationId.getId(), request)));
    }

    @PUT
    @Path("/description")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateDescription(DescriptionRequest request) {
        if (request == null || request.description() == null || request.description().isBlank()
                || request.description().length() > 255) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        return callServiceB(() -> aggregateResponse(serviceB.updateDescription(correlationId.getId(), request)));
    }

    private Response aggregateResponse(InfoResponse downstream) {
        return downstream == null
                ? Response.noContent().build()
                : Response.ok(new AggregateResponse("A", downstream))
                        .type(MediaType.APPLICATION_JSON).build();
    }

    private Response callServiceB(Supplier<Response> call) {
        try {
            return call.get();
        } catch (WebApplicationException e) {
            LOG.warn("Service B call failed correlationId=" + correlationId.getId()
                    + " reason=HTTP_ERROR upstreamStatus=" + e.getResponse().getStatus());
            return unavailableResponse();
        } catch (ProcessingException e) {
            Throwable cause = e;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            String reason;
            if (cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException
                    || cause.getClass().getSimpleName().contains("Timeout")) {
                reason = "TIMEOUT";
            } else if (cause instanceof java.net.SocketException) {
                reason = "CONNECTION_ERROR";
            } else {
                reason = "CLIENT_ERROR";
            }
            LOG.warn("Service B call failed correlationId=" + correlationId.getId()
                    + " reason=" + reason + " causeType=" + cause.getClass().getSimpleName());
            return unavailableResponse();
        }
    }

    private Response unavailableResponse() {
        return Response.status(Response.Status.BAD_GATEWAY)
                .entity(new ErrorResponse("SERVICE_B_UNAVAILABLE"))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
