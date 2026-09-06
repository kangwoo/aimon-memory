package at.aimon.memory.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * A stored value that will not parse stays out of the exception message.
 *
 * <p>{@code StoreException} is a {@code MemoryException}, and {@code ApiExceptionHandler} copies a
 * {@code MemoryException}'s message into the response body verbatim. So the message is the response:
 * {@code "cannot read jsonb object: " + json} meant a row that would not parse was answered by
 * handing the row back — and the columns read this way are the free-form ones, including
 * {@code internal_metadata}, which no response DTO ever carries.
 *
 * <p>No database here on purpose. The rule is a property of this class, not of the schema, and the
 * end-to-end proof that it reaches the wire is {@code ErrorBodyLeakTest} in the API module.
 */
class JsonbTest {

    /** Distinctive enough that a substring assertion cannot pass by accident. */
    private static final String STORED = "clearance-omega-7f3a";

    @Test
    void aColumnThatIsNotAnObjectDoesNotQuoteItsContentBack() {
        assertThatThrownBy(() -> Jsonb.toMap("[\"" + STORED + "\"]")).isInstanceOf(StoreException.class)
                .hasMessageNotContaining(STORED).hasMessage("a stored value could not be read; see the server log");
    }

    @Test
    void aColumnThatIsNotAStringArrayDoesNotQuoteItsContentBack() {
        assertThatThrownBy(() -> Jsonb.toStringList("{\"note\":\"" + STORED + "\"}")).isInstanceOf(StoreException.class)
                .hasMessageNotContaining(STORED).hasMessage("a stored value could not be read; see the server log");
    }

    /**
     * The object and array readers answer identically, so the body cannot be used to probe which
     * column failed or what shape it holds.
     */
    @Test
    void bothReadersAnswerWithTheSameMessage() {
        String fromObject = catchMessage(() -> Jsonb.toMap("[1,2,3]"));
        String fromArray = catchMessage(() -> Jsonb.toStringList("{\"a\":1}"));

        assertThat(fromObject).isEqualTo(fromArray);
    }

    /**
     * The parser's own complaint quotes the source it choked on, so it is the half that must not be
     * flattened into the message — but it is also what an operator needs, so it has to survive as the
     * cause for the handler's ERROR log to pick up.
     */
    @Test
    void theParserComplaintSurvivesAsTheCause() {
        StoreException thrown = (StoreException) org.assertj.core.api.Assertions
                .catchThrowable(() -> Jsonb.toMap("[\"" + STORED + "\"]"));

        assertThat(thrown).isNotNull();
        assertThat(thrown.code()).isEqualTo("store_failed");
        assertThat(thrown.getCause()).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    /** A blank or absent column is the empty value, not a failure — unchanged, and pinned here. */
    @Test
    void anAbsentColumnIsStillEmptyRatherThanAnError() {
        assertThat(Jsonb.toMap(null)).isEmpty();
        assertThat(Jsonb.toMap("  ")).isEmpty();
        assertThat(Jsonb.toStringList(null)).isEmpty();
    }

    private static String catchMessage(Runnable action) {
        return org.assertj.core.api.Assertions.catchThrowable(action::run).getMessage();
    }
}
