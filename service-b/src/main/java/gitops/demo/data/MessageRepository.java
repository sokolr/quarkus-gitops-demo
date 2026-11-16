package gitops.demo.data;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import gitops.demo.messaging.DomainEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class MessageRepository {

    @Inject
    DataSource dataSource;

    @Inject
    ObjectMapper objectMapper;

    public StoredInfo read() {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "SELECT id, message, description FROM service_message ORDER BY id DESC LIMIT 1");
                var rows = statement.executeQuery()) {
            return singleRow(rows);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read service info", e);
        }
    }

    public StoredInfo create(String message, String description) {
        return write("InfoCreated", connection -> {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO service_message (message, description) VALUES (?, ?) "
                            + "RETURNING id, message, description")) {
                statement.setString(1, message);
                statement.setString(2, description);
                try (var rows = statement.executeQuery()) {
                    return singleRow(rows);
                }
            }
        });
    }

    public StoredInfo updateMessage(String message) {
        return write("MessageChanged", connection -> {
            try (var statement = connection.prepareStatement(
                    "UPDATE service_message SET message = ? "
                            + "WHERE id = (SELECT max(id) FROM service_message) "
                            + "RETURNING id, message, description")) {
                statement.setString(1, message);
                try (var rows = statement.executeQuery()) {
                    return singleRow(rows);
                }
            }
        });
    }

    public StoredInfo updateDescription(String description) {
        return write("DescriptionChanged", connection -> {
            try (var statement = connection.prepareStatement(
                    "UPDATE service_message SET description = ? "
                            + "WHERE id = (SELECT max(id) FROM service_message) "
                            + "RETURNING id, message, description")) {
                statement.setString(1, description);
                try (var rows = statement.executeQuery()) {
                    return singleRow(rows);
                }
            }
        });
    }

    private StoredInfo write(String eventType, SqlWrite operation) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                StoredInfo result = operation.execute(connection);
                if (result.id() != 0) {
                    UUID eventId = UUID.randomUUID();
                    var event = new DomainEvent(eventId.toString(), result.id(), eventType, 1,
                            result.message(), result.description());
                    try (var statement = connection.prepareStatement(
                            "INSERT INTO outbox_event (event_id, aggregate_id, payload) VALUES (?, ?, ?::jsonb)")) {
                        statement.setObject(1, eventId);
                        statement.setLong(2, result.id());
                        statement.setString(3, objectMapper.writeValueAsString(event));
                        statement.executeUpdate();
                    }
                }
                connection.commit();
                return result;
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw new IllegalStateException("Could not write service info and outbox event", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not open service info transaction", e);
        }
    }

    private StoredInfo singleRow(ResultSet rows) throws SQLException {
        if (!rows.next()) {
            return new StoredInfo(0, null, null);
        }
        return new StoredInfo(rows.getLong(1), rows.getString(2), rows.getString(3));
    }

    @FunctionalInterface
    private interface SqlWrite {
        StoredInfo execute(Connection connection) throws SQLException;
    }

    public record StoredInfo(long id, String message, String description) {
    }
}
