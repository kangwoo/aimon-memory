package at.aimon.memory.client;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * How to reach one AIMON Memory deployment, and the two things about it this adapter cannot infer.
 *
 * <h2>The token is a supplier, not a string</h2>
 *
 * <p>
 * Tokens expire — 12 hours by default, 30 days at the outside, and there is no revocation list — so an adapter that
 * captured one at construction would work for a day and then fail every call in a process that has been up for a
 * week. The supplier is called per request; an implementation that caches until shortly before expiry is the
 * expected shape, and a constant supplier is fine for a short-lived process.
 *
 * <p>
 * The token also decides what the adapter may do. A workspace-scoped token can read and write any pair in its
 * workspace, which is what a server-side agent runtime wants. A peer-scoped token confines every call to that peer's
 * own observer position, and a query naming a different observer will come back 403 rather than silently narrowed.
 *
 * <h2>The agent peer</h2>
 *
 * <p>
 * {@link at.aimon.core.memory.MemoryIngestRequest} carries one observer and a list of messages with roles;
 * AIMON Memory stores a speaker per message, because a pair is directed and "who said this" is what decides whose
 * memory it lands in. The mapping needs a name for the assistant side of the conversation, and nothing in the request
 * supplies one — so it is configured here, once, for the deployment. Its default is {@code assistant}.
 */
public final class RemoteMemoryOptions {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * The dialectic runs an agentic loop with tool calls, so it is bounded by model latency rather than by a
     * database. A single timeout for every tier would either cut chat off mid-loop or leave a hung snapshot read
     * holding a request thread for minutes.
     */
    private static final Duration DEFAULT_CHAT_TIMEOUT = Duration.ofMinutes(5);

    private final URI baseUri;
    private final Supplier<String> token;
    private final Duration timeout;
    private final Duration chatTimeout;
    private final String agentPeer;
    private final String backendId;

    private RemoteMemoryOptions(Builder builder) {
        this.baseUri = Objects.requireNonNull(builder.baseUri, "baseUri cannot be null");
        this.token = Objects.requireNonNull(builder.token, "token cannot be null");
        this.timeout = builder.timeout;
        this.chatTimeout = builder.chatTimeout;
        this.agentPeer = builder.agentPeer;
        this.backendId = builder.backendId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public URI getBaseUri() {
        return baseUri;
    }

    public Supplier<String> getToken() {
        return token;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public Duration getChatTimeout() {
        return chatTimeout;
    }

    public String getAgentPeer() {
        return agentPeer;
    }

    public String getBackendId() {
        return backendId;
    }

    /** Builder for {@link RemoteMemoryOptions}. */
    public static final class Builder {

        private URI baseUri;
        private Supplier<String> token;
        private Duration timeout = DEFAULT_TIMEOUT;
        private Duration chatTimeout = DEFAULT_CHAT_TIMEOUT;
        private String agentPeer = "assistant";
        private String backendId = "aimon-memory";

        private Builder() {
        }

        /**
         * Sets the service root, such as {@code https://memory.internal:8080}.
         *
         * @param baseUri the root the {@code /v1/...} paths hang off; a trailing slash is accepted
         * @return this builder
         */
        public Builder baseUri(URI baseUri) {
            this.baseUri = Objects.requireNonNull(baseUri, "baseUri cannot be null");
            return this;
        }

        /**
         * Sets the service root from a string.
         *
         * @param baseUri the root the {@code /v1/...} paths hang off
         * @return this builder
         */
        public Builder baseUri(String baseUri) {
            return baseUri(URI.create(Objects.requireNonNull(baseUri, "baseUri cannot be null")));
        }

        /**
         * Sets the source of the bearer token, called once per request.
         *
         * @param token supplies a currently valid JWT; never called concurrently with its own result cached here
         * @return this builder
         */
        public Builder token(Supplier<String> token) {
            this.token = Objects.requireNonNull(token, "token cannot be null");
            return this;
        }

        /**
         * Sets a fixed bearer token.
         *
         * <p>
         * For a process shorter-lived than the token. Anything longer wants {@link #token(Supplier)} — see the class
         * comment on why a captured token stops working.
         *
         * @param token a currently valid JWT
         * @return this builder
         */
        public Builder token(String token) {
            Objects.requireNonNull(token, "token cannot be null");
            return token(() -> token);
        }

        /**
         * Sets the per-request timeout for every tier except chat.
         *
         * @param timeout how long a snapshot, search, observe or ingest call may take
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout cannot be null");
            return this;
        }

        /**
         * Sets the per-request timeout for the dialectic.
         *
         * @param chatTimeout how long an agentic loop may take before the call is abandoned
         * @return this builder
         */
        public Builder chatTimeout(Duration chatTimeout) {
            this.chatTimeout = Objects.requireNonNull(chatTimeout, "chatTimeout cannot be null");
            return this;
        }

        /**
         * Sets the peer name assistant-role messages are stored under.
         *
         * @param agentPeer the speaker recorded for {@code Role.ASSISTANT} messages during ingest
         * @return this builder
         */
        public Builder agentPeer(String agentPeer) {
            this.agentPeer = requireText(agentPeer, "agentPeer");
            return this;
        }

        /**
         * Sets the id this backend is named by in logs and degradation messages.
         *
         * @param backendId a short stable id; defaults to {@code aimon-memory}
         * @return this builder
         */
        public Builder backendId(String backendId) {
            this.backendId = requireText(backendId, "backendId");
            return this;
        }

        public RemoteMemoryOptions build() {
            return new RemoteMemoryOptions(this);
        }

        private static String requireText(String value, String field) {
            Objects.requireNonNull(value, field + " cannot be null");
            if (value.isBlank()) {
                throw new IllegalArgumentException(field + " cannot be blank");
            }
            return value;
        }
    }
}
