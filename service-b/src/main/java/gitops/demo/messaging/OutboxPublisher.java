package gitops.demo.messaging;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;

import javax.sql.DataSource;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

@ApplicationScoped
public class OutboxPublisher {

    private static final Logger LOG = Logger.getLogger(OutboxPublisher.class);

    @Inject
    DataSource dataSource;

    @ConfigProperty(name = "demo.outbox.enabled")
    boolean enabled;

    @ConfigProperty(name = "demo.aws.region")
    String region;

    @ConfigProperty(name = "demo.aws.sns.topic-arn")
    Optional<String> topicArn;

    @ConfigProperty(name = "demo.aws.sns.endpoint")
    Optional<String> endpoint;

    private SnsClient sns;
    private String activeTopicArn;

    @PostConstruct
    void start() {
        if (!enabled) {
            return;
        }
        activeTopicArn = topicArn.filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "SNS_TOPIC_ARN is required when outbox publishing is enabled"));
        var builder = SnsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(configuration -> configuration
                        .apiCallTimeout(Duration.ofSeconds(10))
                        .apiCallAttemptTimeout(Duration.ofSeconds(3)));
        endpoint.filter(value -> !value.isBlank())
                .ifPresent(value -> builder.endpointOverride(URI.create(value)));
        sns = builder.build();
    }

    @PreDestroy
    void stop() {
        if (sns != null) {
            sns.close();
        }
    }

    @Scheduled(every = "{demo.outbox.poll-interval}", concurrentExecution = SKIP)
    void publishPending() {
        if (!enabled) {
            return;
        }
        for (int published = 0; published < 20; published++) {
            if (!publishOne()) {
                return;
            }
        }
    }

    private boolean publishOne() {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            String eventId;
            String payload;
            try (var statement = connection.prepareStatement(
                    "SELECT event_id, payload::text FROM outbox_event "
                            + "WHERE published_at IS NULL ORDER BY created_at, event_id "
                            + "LIMIT 1 FOR UPDATE SKIP LOCKED");
                    var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    connection.rollback();
                    return false;
                }
                eventId = rows.getString(1);
                payload = rows.getString(2);
            }

            sns.publish(PublishRequest.builder().topicArn(activeTopicArn).message(payload).build());
            try (var statement = connection.prepareStatement(
                    "UPDATE outbox_event SET published_at = now() WHERE event_id = ?::uuid")) {
                statement.setString(1, eventId);
                statement.executeUpdate();
            }
            connection.commit();
            LOG.infof("Published outbox event eventId=%s", eventId);
            return true;
        } catch (Exception e) {
            LOG.warn("Outbox publish failed; event remains pending for retry", e);
            return false;
        }
    }
}
