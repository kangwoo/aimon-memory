package at.aimon.memory.core.model;

import java.time.Instant;
import java.util.List;

import at.aimon.memory.core.MemoryException;
import at.aimon.memory.core.key.PairKey;

/**
 * A conclusion prepared for storage: text already normalised, analysed, hashed and embedded.
 *
 * <p>Dedup runs inside {@code upsert}, so the draft has to arrive with everything the three stages
 * compare on. Building a draft is the caller's job; deciding what happens to it is the store's.
 */
public record ConclusionDraft(PairKey pair, String sessionName, String content, String contentNorm,
        String contentAnalyzed, String contentHash, ConclusionLevel level, Double confidence, List<String> sourceIds,
        List<Long> messageIds, List<String> entityNames, float[] embedding, Instant expiresAt, Actor actor,
        String promptVersion) {

    public ConclusionDraft {
        if (pair == null) {
            throw new MemoryException("bad_draft", "pair is required");
        }
        if (content == null || content.isBlank()) {
            throw new MemoryException("bad_draft", "content is required");
        }
        if (level == ConclusionLevel.EXPLICIT && (sessionName == null || sessionName.isBlank())) {
            throw new MemoryException("bad_draft", "explicit conclusions require a session");
        }
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
        messageIds = messageIds == null ? List.of() : List.copyOf(messageIds);
        entityNames = entityNames == null ? List.of() : List.copyOf(entityNames);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable accumulator; the record itself stays immutable. */
    public static final class Builder {
        private PairKey pair;
        private String sessionName;
        private String content;
        private String contentNorm;
        private String contentAnalyzed;
        private String contentHash;
        private ConclusionLevel level = ConclusionLevel.EXPLICIT;
        private Double confidence;
        private List<String> sourceIds = List.of();
        private List<Long> messageIds = List.of();
        private List<String> entityNames = List.of();
        private float[] embedding;
        private Instant expiresAt;
        private Actor actor = Actor.DERIVER;
        private String promptVersion;

        public Builder pair(PairKey v) {
            this.pair = v;
            return this;
        }

        public Builder sessionName(String v) {
            this.sessionName = v;
            return this;
        }

        public Builder content(String v) {
            this.content = v;
            return this;
        }

        public Builder contentNorm(String v) {
            this.contentNorm = v;
            return this;
        }

        public Builder contentAnalyzed(String v) {
            this.contentAnalyzed = v;
            return this;
        }

        public Builder contentHash(String v) {
            this.contentHash = v;
            return this;
        }

        public Builder level(ConclusionLevel v) {
            this.level = v;
            return this;
        }

        public Builder confidence(Double v) {
            this.confidence = v;
            return this;
        }

        public Builder sourceIds(List<String> v) {
            this.sourceIds = v;
            return this;
        }

        public Builder messageIds(List<Long> v) {
            this.messageIds = v;
            return this;
        }

        public Builder entityNames(List<String> v) {
            this.entityNames = v;
            return this;
        }

        public Builder embedding(float[] v) {
            this.embedding = v;
            return this;
        }

        public Builder expiresAt(Instant v) {
            this.expiresAt = v;
            return this;
        }

        public Builder actor(Actor v) {
            this.actor = v;
            return this;
        }

        /**
         * Which prompt produced this.
         *
         * <p>Recorded because entity quality is downstream of the extraction prompt — that is the
         * price of getting entities for free from a call that was already happening. When the prompt
         * changes, this is what identifies the conclusions extracted under the old one.
         */
        public Builder promptVersion(String v) {
            this.promptVersion = v;
            return this;
        }

        public ConclusionDraft build() {
            return new ConclusionDraft(pair, sessionName, content, contentNorm, contentAnalyzed, contentHash, level,
                    confidence, sourceIds, messageIds, entityNames, embedding, expiresAt, actor, promptVersion);
        }
    }
}
