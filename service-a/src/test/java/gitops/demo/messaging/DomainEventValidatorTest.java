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
}
