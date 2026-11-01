package gitops.demo.web;

import java.util.UUID;

import org.jboss.logging.Logger;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

@Provider
public class CorrelationIdFilter implements ContainerRequestFilter, ContainerResponseFilter {

    public static final String HEADER_NAME = "X-Correlation-ID";
    private static final String REQUEST_PROPERTY = CorrelationIdFilter.class.getName() + ".id";
    private static final Logger LOG = Logger.getLogger(CorrelationIdFilter.class);

    @Override
    public void filter(ContainerRequestContext request) {
        String incomingId = request.getHeaderString(HEADER_NAME);
        String correlationId = incomingId == null || incomingId.isBlank()
                ? UUID.randomUUID().toString()
                : incomingId;

        // Providers may serve concurrent requests; keep their IDs on the requests themselves.
        request.setProperty(REQUEST_PROPERTY, correlationId);
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        String correlationId = (String) request.getProperty(REQUEST_PROPERTY);
        if (correlationId == null) {
            return;
        }

        response.getHeaders().putSingle(HEADER_NAME, correlationId);
        LOG.info("Request completed correlationId=" + correlationId
                + " method=" + request.getMethod()
                + " path=" + request.getUriInfo().getPath()
                + " status=" + response.getStatus());
    }
}
