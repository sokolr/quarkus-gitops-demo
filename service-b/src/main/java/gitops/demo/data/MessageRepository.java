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
                        "SELECT message, description FROM service_message WHERE id = 1");
                var rows = statement.executeQuery()) {
            return singleRow(rows);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read service info", e);
        }
    }

    public StoredInfo updateMessage(String message) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "UPDATE service_message SET message = ? WHERE id = 1 RETURNING message, description")) {
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
                        "UPDATE service_message SET description = ? WHERE id = 1 RETURNING message, description")) {
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
            throw new IllegalStateException("Service info row is missing");
        }
        return new StoredInfo(rows.getString(1), rows.getString(2));
    }

    public record StoredInfo(String message, String description) {
    }
}
