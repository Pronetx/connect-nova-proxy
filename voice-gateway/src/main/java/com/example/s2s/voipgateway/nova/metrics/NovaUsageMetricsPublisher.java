package com.example.s2s.voipgateway.nova.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.cloudwatch.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Publishes Nova Sonic usage metrics to CloudWatch.
 */
public class NovaUsageMetricsPublisher {
    private static final Logger log = LoggerFactory.getLogger(NovaUsageMetricsPublisher.class);
    private static final String NAMESPACE = "NovaGateway/Usage";

    private final CloudWatchAsyncClient cloudWatchClient;
    private final boolean enabled;

    public NovaUsageMetricsPublisher() {
        // Check if metrics are enabled via environment variable
        this.enabled = !"false".equalsIgnoreCase(System.getenv().getOrDefault("ENABLE_USAGE_METRICS", "true"));

        if (enabled) {
            String region = System.getenv().getOrDefault("AWS_REGION", "us-west-2");
            this.cloudWatchClient = CloudWatchAsyncClient.builder()
                    .region(Region.of(region))
                    .build();
            log.info("Nova usage metrics publisher enabled for region: {}", region);
        } else {
            this.cloudWatchClient = null;
            log.info("Nova usage metrics publisher disabled");
        }
    }

    /**
     * Publishes usage event metrics to CloudWatch.
     * @param usageEvent The usage event JSON node
     */
    public void publishUsageMetrics(JsonNode usageEvent) {
        if (!enabled || cloudWatchClient == null) {
            return;
        }

        try {
            String sessionId = usageEvent.has("sessionId") ? usageEvent.get("sessionId").asText() : "unknown";
            String completionId = usageEvent.has("completionId") ? usageEvent.get("completionId").asText() : "unknown";

            JsonNode details = usageEvent.get("details");
            if (details == null) {
                return;
            }

            JsonNode total = details.get("total");
            if (total == null) {
                return;
            }

            // Extract token counts
            JsonNode input = total.get("input");
            JsonNode output = total.get("output");

            int totalInputTokens = usageEvent.has("totalInputTokens") ? usageEvent.get("totalInputTokens").asInt() : 0;
            int totalOutputTokens = usageEvent.has("totalOutputTokens") ? usageEvent.get("totalOutputTokens").asInt() : 0;
            int totalTokens = usageEvent.has("totalTokens") ? usageEvent.get("totalTokens").asInt() : 0;

            int inputSpeechTokens = input != null && input.has("speechTokens") ? input.get("speechTokens").asInt() : 0;
            int inputTextTokens = input != null && input.has("textTokens") ? input.get("textTokens").asInt() : 0;
            int outputSpeechTokens = output != null && output.has("speechTokens") ? output.get("speechTokens").asInt() : 0;
            int outputTextTokens = output != null && output.has("textTokens") ? output.get("textTokens").asInt() : 0;

            // Build metric data
            List<MetricDatum> metricData = new ArrayList<>();
            Instant timestamp = Instant.now();

            // Common dimensions
            List<Dimension> dimensions = List.of(
                    Dimension.builder().name("SessionId").value(sessionId).build(),
                    Dimension.builder().name("CompletionId").value(completionId).build()
            );

            // Total tokens
            metricData.add(MetricDatum.builder()
                    .metricName("TotalTokens")
                    .value((double) totalTokens)
                    .unit(StandardUnit.COUNT)
                    .timestamp(timestamp)
                    .dimensions(dimensions)
                    .build());

            // Input tokens (total)
            metricData.add(MetricDatum.builder()
                    .metricName("InputTokens")
                    .value((double) totalInputTokens)
                    .unit(StandardUnit.COUNT)
                    .timestamp(timestamp)
                    .dimensions(dimensions)
                    .build());

            // Output tokens (total)
            metricData.add(MetricDatum.builder()
                    .metricName("OutputTokens")
                    .value((double) totalOutputTokens)
                    .unit(StandardUnit.COUNT)
                    .timestamp(timestamp)
                    .dimensions(dimensions)
                    .build());

            // Speech vs Text breakdown - Input
            metricData.add(MetricDatum.builder()
                    .metricName("InputSpeechTokens")
                    .value((double) inputSpeechTokens)
                    .unit(StandardUnit.COUNT)
                    .timestamp(timestamp)
                    .dimensions(dimensions)
                    .build());

            metricData.add(MetricDatum.builder()
                    .metricName("InputTextTokens")
                    .value((double) inputTextTokens)
                    .unit(StandardUnit.COUNT)
                    .timestamp(timestamp)
                    .dimensions(dimensions)
                    .build());

            // Speech vs Text breakdown - Output
            metricData.add(MetricDatum.builder()
                    .metricName("OutputSpeechTokens")
                    .value((double) outputSpeechTokens)
                    .unit(StandardUnit.COUNT)
                    .timestamp(timestamp)
                    .dimensions(dimensions)
                    .build());

            metricData.add(MetricDatum.builder()
                    .metricName("OutputTextTokens")
                    .value((double) outputTextTokens)
                    .unit(StandardUnit.COUNT)
                    .timestamp(timestamp)
                    .dimensions(dimensions)
                    .build());

            // Also publish the same metrics WITHOUT dimensions for easy rollup/aggregation
            metricData.add(MetricDatum.builder()
                    .metricName("TotalTokens")
                    .value((double) totalTokens)
                    .unit(StandardUnit.COUNT)
                    .timestamp(timestamp)
                    .build());

            metricData.add(MetricDatum.builder()
                    .metricName("InputTokens")
                    .value((double) totalInputTokens)
                    .unit(StandardUnit.COUNT)
                    .timestamp(timestamp)
                    .build());

            metricData.add(MetricDatum.builder()
                    .metricName("OutputTokens")
                    .value((double) totalOutputTokens)
                    .unit(StandardUnit.COUNT)
                    .timestamp(timestamp)
                    .build());

            metricData.add(MetricDatum.builder()
                    .metricName("InputSpeechTokens")
                    .value((double) inputSpeechTokens)
                    .unit(StandardUnit.COUNT)
                    .timestamp(timestamp)
                    .build());

            metricData.add(MetricDatum.builder()
                    .metricName("InputTextTokens")
                    .value((double) inputTextTokens)
                    .unit(StandardUnit.COUNT)
                    .timestamp(timestamp)
                    .build());

            metricData.add(MetricDatum.builder()
                    .metricName("OutputSpeechTokens")
                    .value((double) outputSpeechTokens)
                    .unit(StandardUnit.COUNT)
                    .timestamp(timestamp)
                    .build());

            metricData.add(MetricDatum.builder()
                    .metricName("OutputTextTokens")
                    .value((double) outputTextTokens)
                    .unit(StandardUnit.COUNT)
                    .timestamp(timestamp)
                    .build());

            // Publish metrics asynchronously
            PutMetricDataRequest request = PutMetricDataRequest.builder()
                    .namespace(NAMESPACE)
                    .metricData(metricData)
                    .build();

            CompletableFuture<PutMetricDataResponse> future = cloudWatchClient.putMetricData(request);

            future.whenComplete((response, throwable) -> {
                if (throwable != null) {
                    log.error("Failed to publish usage metrics to CloudWatch", throwable);
                } else {
                    log.debug("Successfully published {} usage metrics for session {}", metricData.size(), sessionId);
                }
            });

        } catch (Exception e) {
            log.error("Error publishing usage metrics", e);
        }
    }

    /**
     * Publishes tool usage metrics to CloudWatch.
     * @param toolName The name of the tool being invoked
     * @param toolUseId The unique tool use ID
     * @param sessionId The session ID
     * @param success Whether the tool invocation was successful
     */
    public void publishToolUsageMetrics(String toolName, String toolUseId, String sessionId, boolean success) {
        if (!enabled || cloudWatchClient == null) {
            return;
        }

        try {
            List<MetricDatum> metricData = new ArrayList<>();
            Instant timestamp = Instant.now();

            // Dimensions for detailed tracking
            List<Dimension> dimensionsWithTool = List.of(
                    Dimension.builder().name("ToolName").value(toolName).build(),
                    Dimension.builder().name("SessionId").value(sessionId).build()
            );

            List<Dimension> dimensionsToolOnly = List.of(
                    Dimension.builder().name("ToolName").value(toolName).build()
            );

            // Tool invocation count with session and tool name
            metricData.add(MetricDatum.builder()
                    .metricName("ToolInvocations")
                    .value(1.0)
                    .unit(StandardUnit.COUNT)
                    .timestamp(timestamp)
                    .dimensions(dimensionsWithTool)
                    .build());

            // Tool invocation count by tool name only (for rollup)
            metricData.add(MetricDatum.builder()
                    .metricName("ToolInvocations")
                    .value(1.0)
                    .unit(StandardUnit.COUNT)
                    .timestamp(timestamp)
                    .dimensions(dimensionsToolOnly)
                    .build());

            // Overall tool invocation count (no dimensions)
            metricData.add(MetricDatum.builder()
                    .metricName("ToolInvocations")
                    .value(1.0)
                    .unit(StandardUnit.COUNT)
                    .timestamp(timestamp)
                    .build());

            // Success/failure metrics
            if (success) {
                metricData.add(MetricDatum.builder()
                        .metricName("ToolSuccess")
                        .value(1.0)
                        .unit(StandardUnit.COUNT)
                        .timestamp(timestamp)
                        .dimensions(dimensionsToolOnly)
                        .build());

                metricData.add(MetricDatum.builder()
                        .metricName("ToolSuccess")
                        .value(1.0)
                        .unit(StandardUnit.COUNT)
                        .timestamp(timestamp)
                        .build());
            } else {
                metricData.add(MetricDatum.builder()
                        .metricName("ToolFailure")
                        .value(1.0)
                        .unit(StandardUnit.COUNT)
                        .timestamp(timestamp)
                        .dimensions(dimensionsToolOnly)
                        .build());

                metricData.add(MetricDatum.builder()
                        .metricName("ToolFailure")
                        .value(1.0)
                        .unit(StandardUnit.COUNT)
                        .timestamp(timestamp)
                        .build());
            }

            // Publish metrics asynchronously
            PutMetricDataRequest request = PutMetricDataRequest.builder()
                    .namespace(NAMESPACE)
                    .metricData(metricData)
                    .build();

            CompletableFuture<PutMetricDataResponse> future = cloudWatchClient.putMetricData(request);

            future.whenComplete((response, throwable) -> {
                if (throwable != null) {
                    log.error("Failed to publish tool usage metrics to CloudWatch", throwable);
                } else {
                    log.debug("Successfully published tool usage metrics for tool {} in session {}", toolName, sessionId);
                }
            });

        } catch (Exception e) {
            log.error("Error publishing tool usage metrics", e);
        }
    }

    /**
     * Closes the CloudWatch client.
     */
    public void close() {
        if (cloudWatchClient != null) {
            cloudWatchClient.close();
        }
    }
}
