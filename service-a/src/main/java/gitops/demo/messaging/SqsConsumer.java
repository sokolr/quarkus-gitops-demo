package gitops.demo.messaging;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@ApplicationScoped
public class SqsConsumer {

    private static final Logger LOG = Logger.getLogger(SqsConsumer.class);

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "demo.sqs.enabled")
    boolean enabled;

    @ConfigProperty(name = "demo.sqs.processing-timeout")
    Duration processingTimeout;

    @ConfigProperty(name = "demo.aws.region")
    String region;

    @ConfigProperty(name = "demo.aws.sqs.queue-name")
    Optional<String> queueName;

    @ConfigProperty(name = "demo.aws.sqs.endpoint")
    Optional<String> endpoint;

    private SqsClient sqs;
    private String activeQueueName;
    private String queueUrl;

    @PostConstruct
    void start() {
        if (!enabled) {
            return;
        }
        if (processingTimeout.compareTo(Duration.ofSeconds(1)) < 0
                || processingTimeout.compareTo(Duration.ofSeconds(20)) > 0) {
            throw new IllegalStateException("SQS processing timeout must be between 1 and 20 seconds");
        }
        activeQueueName = queueName.filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "SQS_QUEUE_NAME is required when SQS consumption is enabled"));
        var builder = SqsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(configuration -> configuration
                        .apiCallTimeout(Duration.ofSeconds(5))
                        .apiCallAttemptTimeout(Duration.ofSeconds(4)));
        endpoint.filter(value -> !value.isBlank())
                .ifPresent(value -> builder.endpointOverride(URI.create(value)));
        sqs = builder.build();
    }

    @PreDestroy
    void stop() {
        if (sqs != null) {
            sqs.close();
        }
    }

    @Scheduled(every = "{demo.sqs.poll-interval}", concurrentExecution = SKIP)
    void poll() {
        if (!enabled) {
            return;
        }
        try {
            if (queueUrl == null) {
                queueUrl = sqs.getQueueUrl(GetQueueUrlRequest.builder()
                        .queueName(activeQueueName).build()).queueUrl();
            }
            var messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(1)
                    .waitTimeSeconds(2)
                    .visibilityTimeout(30)
                    .build()).messages();
            for (var message : messages) {
                long startedAt = System.nanoTime();
                try {
                    String eventId = DomainEventValidator.validate(message.body(), mapper);
                    if (Duration.ofNanos(System.nanoTime() - startedAt).compareTo(processingTimeout) >= 0) {
                        throw new IllegalStateException("SQS processing timeout exceeded");
                    }
                    sqs.deleteMessage(DeleteMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .receiptHandle(message.receiptHandle())
                            .build());
                    LOG.infof("Validated SQS event eventId=%s sqsMessageId=%s", eventId, message.messageId());
                } catch (Exception e) {
                    LOG.warnf(e, "SQS event failed validation or acknowledgement sqsMessageId=%s", message.messageId());
                }
            }
        } catch (Exception e) {
            LOG.warn("SQS polling failed; will retry", e);
        }
    }
}
