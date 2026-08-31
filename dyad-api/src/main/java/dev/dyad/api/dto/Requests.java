package dev.dyad.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/** Request bodies. Validation is declarative so a bad body is a 400 before any handler runs. */
public final class Requests {

    private Requests() {}

    public record CreateWorkspace(Map<String, Object> metadata, Map<String, Object> configuration) {}

    public record CreatePeer(Map<String, Object> metadata, Map<String, Object> configuration) {}

    public record CreateSession(Map<String, Object> metadata, Map<String, Object> configuration) {}

    public record UpdateConfiguration(@NotEmpty Map<String, Object> configuration) {}

    public record AddSessionPeers(@NotEmpty List<SessionPeerSpec> peers) {}

    public record SessionPeerSpec(@NotBlank String peer, Boolean observeMe, Boolean observeOthers) {}

    public record CreateMessages(
            @NotEmpty @Size(max = 100) List<NewMessage> messages) {}

    public record NewMessage(@NotBlank String peer, @NotBlank String content, Map<String, Object> metadata) {}

    public record RecallQuery(
            @NotBlank String query,
            @NotBlank String observer,
            @NotBlank String observed,
            Integer limit,
            Map<String, Object> filter,
            Double threshold,
            Boolean explain) {}

    public record SearchMessages(Map<String, Object> filter, Integer page, Integer size) {}

    /** Direct injection, for facts a caller already knows and does not want extracted. */
    public record CreateConclusion(
            @NotBlank String observer,
            @NotBlank String observed,
            String session,
            @NotBlank String content,
            List<String> entities,
            String expiresAt) {}

    public record ChatRequest(
            @NotBlank String question,
            @NotBlank String observer,
            @NotBlank String observed,
            String session,
            String reasoningLevel,
            List<ChatTurn> history,
            Map<String, Object> responseFormat) {}

    public record ChatTurn(@NotBlank String role, @NotBlank String content) {}

    public record ScheduleDream(@NotBlank String observer, @NotBlank String observed, String type) {}

    public record IssueToken(
            @NotBlank String scope,
            String workspace,
            String peer,
            String session,
            Boolean allowMemberRead,
            Long lifetimeSeconds) {}
}
