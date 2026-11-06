package gitops.demo.data;

import java.sql.SQLException;
import java.sql.ResultSet;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class MessageRepository {

    @Inject
    DataSource dataSource;

    public StoredInfo read() {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "SELECT message, description FROM service_message ORDER BY id DESC LIMIT 1");
                var rows = statement.executeQuery()) {
            return singleRow(rows);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read service info", e);
        }
    }

    public StoredInfo create(String message, String description) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "INSERT INTO service_message (message, description) VALUES (?, ?) "
                                + "RETURNING message, description")) {
            statement.setString(1, message);
            statement.setString(2, description);
            try (var rows = statement.executeQuery()) {
                return singleRow(rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create service info", e);
        }
    }

    public StoredInfo updateMessage(String message) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "UPDATE service_message SET message = ? "
                                + "WHERE id = (SELECT max(id) FROM service_message) RETURNING message, description")) {
            statement.setString(1, message);
            try (var rows = statement.executeQuery()) {
                return singleRow(rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not update service message", e);
        }
    }

    public StoredInfo updateDescription(String description) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "UPDATE service_message SET description = ? "
                                + "WHERE id = (SELECT max(id) FROM service_message) RETURNING message, description")) {
            statement.setString(1, description);
            try (var rows = statement.executeQuery()) {
                return singleRow(rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not update service description", e);
        }
    }

    private StoredInfo singleRow(ResultSet rows) throws SQLException {
        if (!rows.next()) {
            return new StoredInfo(null, null);
        }
        return new StoredInfo(rows.getString(1), rows.getString(2));
    }

    public record StoredInfo(String message, String description) {
    }
}
