package gitops.demo.dto;

public record AggregateResponse(String service, InfoResponse downstream) {
}
