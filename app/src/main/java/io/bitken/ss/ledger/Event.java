package io.bitken.ss.ledger;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Event(
        @JsonProperty("event_type") EventType eventType,
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("task_id") String taskId,
        @JsonProperty("base_commit_sha") String baseCommitSha,
        @JsonProperty("payload") String payload,
        @JsonProperty("metadata") Map<String, String> metadata
) {
    public Event {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static Event system(EventType type, String baseCommitSha, String payload, Map<String, String> metadata) {
        return new Event(type, Instant.now(), null, baseCommitSha, payload, metadata);
    }

    public static Event forTask(EventType type, String taskId, String baseCommitSha,
                                String payload, Map<String, String> metadata) {
        return new Event(type, Instant.now(), taskId, baseCommitSha, payload, metadata);
    }
}
