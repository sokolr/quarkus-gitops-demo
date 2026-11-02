package gitops.demo.web;

import gitops.demo.dto.InfoResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@ApplicationScoped
@Path("/api/info")
@Produces(MediaType.APPLICATION_JSON)
public class RestController {

    @GET
    public InfoResponse info() {
        return new InfoResponse("B", "Hello from Service B!!!");
    }
}
