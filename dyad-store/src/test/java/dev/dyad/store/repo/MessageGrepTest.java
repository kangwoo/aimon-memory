package dev.dyad.store.repo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dyad.store.StoreTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The substring tool is documented to the model as literal text search, and the model composes the
 * argument. Moving it onto ILIKE for the sake of the trigram index means wildcards now have meaning
 * to the database, so they have to stop having meaning before the query is built.
 */
class MessageGrepTest extends StoreTestBase {

    @BeforeEach
    void seedMessages() {
        seedSession("s1");
        peers.getOrCreate(WORKSPACE, "alice", Map.of(), Map.of());
        long start = sessions.nextSequence(WORKSPACE, "s1", 5);
        messages.insertBatch(
                WORKSPACE, "s1", start,
                List.of(
                        new MessageRepository.NewMessage("alice", "I work at a Bank in Seoul", 6, Map.of()),
                        new MessageRepository.NewMessage("alice", "the discount was 50% off", 6, Map.of()),
                        new MessageRepository.NewMessage("alice", "my file is named report_final", 6, Map.of()),
                        new MessageRepository.NewMessage("alice", "완전히 다른 이야기입니다", 6, Map.of()),
                        new MessageRepository.NewMessage("alice", "a path like C:\\temp\\notes", 6, Map.of())));
    }

    @Test
    void findsAPhraseRegardlessOfCase() {
        assertThat(messages.grep(WORKSPACE, "s1", "bank in seoul", 10))
                .singleElement()
                .satisfies(m -> assertThat(m.content()).contains("Bank in Seoul"));
    }

    @Test
    void findsKoreanSubstrings() {
        assertThat(messages.grep(WORKSPACE, "s1", "다른 이야기", 10)).hasSize(1);
    }

    /** A percent sign in the phrase must match a percent sign, not everything. */
    @Test
    void wildcardsInTheNeedleAreLiteral() {
        assertThat(messages.grep(WORKSPACE, "s1", "50% off", 10)).hasSize(1);
        assertThat(messages.grep(WORKSPACE, "s1", "%", 10))
                .as("a bare percent must match only the message containing one")
                .hasSize(1);
        assertThat(messages.grep(WORKSPACE, "s1", "report_final", 10)).hasSize(1);
        assertThat(messages.grep(WORKSPACE, "s1", "report_", 10)).hasSize(1);
        // The underscore is literal, so this matches nothing rather than every four-letter run.
        assertThat(messages.grep(WORKSPACE, "s1", "repo_t", 10)).isEmpty();
    }

    @Test
    void backslashesInTheNeedleAreLiteral() {
        assertThat(messages.grep(WORKSPACE, "s1", "C:\\temp", 10)).hasSize(1);
    }

    @Test
    void anAbsentPhraseFindsNothing() {
        assertThat(messages.grep(WORKSPACE, "s1", "zeppelin", 10)).isEmpty();
        assertThat(messages.grep(WORKSPACE, null, "zeppelin", 10)).isEmpty();
    }

    @Test
    void escapingIsAppliedToEveryWildcardCharacter() {
        assertThat(MessageRepository.escapeForLike("100%_x\\y"))
                .isEqualTo("100\\%\\_x\\\\y");
        assertThat(MessageRepository.escapeForLike(null)).isEmpty();
    }
}
