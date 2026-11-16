package gitops.demo.messaging;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class DomainEventValidator {

    private static final Set<String> EVENT_TYPES = Set.of(
            "InfoCreated", "MessageChanged", "DescriptionChanged");

    private DomainEventValidator() {
    }

    static String validate(String body, ObjectMapper mapper) throws IOException {
        JsonNode event = mapper.readTree(body);
        if (event == null || !event.isObject()) {
            throw new IllegalArgumentException("Event must be a JSON object");
        }
        String eventId = requiredText(event, "eventId");
        UUID.fromString(eventId);
        JsonNode aggregateId = event.path("aggregateId");
        if (!aggregateId.isIntegralNumber() || aggregateId.asLong() <= 0) {
            throw new IllegalArgumentException("Invalid aggregateId");
        }
        if (!EVENT_TYPES.contains(requiredText(event, "eventType"))) {
            throw new IllegalArgumentException("Unknown eventType");
        }
        JsonNode version = event.path("schemaVersion");
        if (!version.isIntegralNumber() || version.asInt() != 1) {
            throw new IllegalArgumentException("Unsupported schemaVersion");
        }
        String message = requiredText(event, "message");
        if (message.isBlank() || message.length() > 255) {
            throw new IllegalArgumentException("Invalid message");
        }
        JsonNode description = event.path("description");
        if (!description.isMissingNode() && !description.isNull()) {
            if (!description.isTextual() || description.asText().isBlank()
                    || description.asText().length() > 255) {
                throw new IllegalArgumentException("Invalid description");
            }
        }
        JsonNode eventVersion = event.path("eventVersion");
        if (!eventVersion.isMissingNode()) {
            if (!eventVersion.isIntegralNumber() || eventVersion.asInt() != 2) {
                throw new IllegalArgumentException("Unsupported eventVersion");
            }
            String category = requiredText(event, "category");
            if (category.length() > 32) {
                throw new IllegalArgumentException("Invalid category");
            }
        }
        return eventId;
    }

    private static String requiredText(JsonNode event, String field) {
        JsonNode value = event.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing or invalid " + field);
        }
        return value.asText();
    }
}
