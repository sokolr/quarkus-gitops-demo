package gitops.demo.web;

import java.util.UUID;

import org.jboss.logging.Logger;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

@Provider
public class CorrelationIdFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final String HEADER_NAME = "X-Correlation-ID";
    private static final Logger LOG = Logger.getLogger(CorrelationIdFilter.class);

    @Inject
    CorrelationIdContext correlationId;

    @Override
    public void filter(ContainerRequestContext request) {
        String incomingId = request.getHeaderString(HEADER_NAME);
        correlationId.setId(incomingId == null || incomingId.isBlank()
                ? UUID.randomUUID().toString()
                : incomingId);
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        String id = correlationId.getId();
        if (id == null) {
            return;
        }
        response.getHeaders().putSingle(HEADER_NAME, id);
        LOG.info("Request completed correlationId=" + id
                + " method=" + request.getMethod()
                + " path=" + request.getUriInfo().getPath()
                + " status=" + response.getStatus());
    }
}
