package gitops.demo.web;

import gitops.demo.client.ServiceBClient;
import gitops.demo.dto.AggregateResponse;
import gitops.demo.dto.ErrorResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.rest.client.inject.RestClient;

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
        try {
            return Response.ok(new AggregateResponse("A", serviceB.info(correlationId.getId())))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
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
