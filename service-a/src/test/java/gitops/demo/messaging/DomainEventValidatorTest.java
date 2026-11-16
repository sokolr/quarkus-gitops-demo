package gitops.demo.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class DomainEventValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final String eventId = UUID.randomUUID().toString();

    @Test
    void acceptsRawV1EventAndExtraFields() throws Exception {
        String body = """
                {"eventId":"%s","aggregateId":7,"eventType":"InfoCreated",
                 "schemaVersion":1,"message":"hello","description":null,"futureField":"ignored"}
                """.formatted(eventId);
        assertEquals(eventId, DomainEventValidator.validate(body, mapper));
    }

    @Test
    void rejectsEnvelopeAndPoison() {
        assertThrows(IllegalArgumentException.class,
                () -> DomainEventValidator.validate("{\"Message\":\"{}\"}", mapper));
        assertThrows(Exception.class, () -> DomainEventValidator.validate("not-json", mapper));
    }

    @Test
    void rejectsUnsupportedVersion() {
        String body = """
                {"eventId":"%s","aggregateId":7,"eventType":"InfoCreated",
                 "schemaVersion":2,"message":"hello"}
                """.formatted(eventId);
        assertThrows(IllegalArgumentException.class,
                () -> DomainEventValidator.validate(body, mapper));
    }

    @Test
    void acceptsV2AdditiveFieldsAndLegacyV1() throws Exception {
        String v2 = """
                {"eventId":"%s","aggregateId":7,"eventType":"InfoCreated",
                 "schemaVersion":1,"eventVersion":2,"message":"hello","category":"news"}
                """.formatted(eventId);
        assertEquals(eventId, DomainEventValidator.validate(v2, mapper));
        String v1 = v2.replace("\"eventVersion\":2,", "")
                .replace(",\"category\":\"news\"", "");
        assertEquals(eventId, DomainEventValidator.validate(v1, mapper));
    }

    @Test
    void rejectsIncompleteV2Event() {
        String missingCategory = """
                {"eventId":"%s","aggregateId":7,"eventType":"InfoCreated",
                 "schemaVersion":1,"eventVersion":2,"message":"hello"}
                """.formatted(eventId);
        assertThrows(IllegalArgumentException.class,
                () -> DomainEventValidator.validate(missingCategory, mapper));
    }
}
