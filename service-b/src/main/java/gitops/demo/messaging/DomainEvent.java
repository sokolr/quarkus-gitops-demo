package gitops.demo.messaging;

public record DomainEvent(String eventId, long aggregateId, String eventType, int schemaVersion,
        String message, String description) {
}
