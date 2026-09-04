package at.aimon.memory.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import at.aimon.memory.core.MemoryException;
import at.aimon.memory.core.spi.EmbedPurpose;
import at.aimon.memory.core.spi.Embedder;
import at.aimon.memory.testkit.db.PostgresSupport;

/**
 * The embedder and the vector columns are configured independently, and nothing compared them.
 *
 * <p>Set the embedder to 3072 dimensions and it produced its vectors happily — it validates against
 * the property it was given, not against the database — and then every write failed on the insert.
 * The API reported that as a 409 "conflicts with existing data" and the worker as a work unit that
 * failed until it was quarantined. Neither message contained the word "dimensions".
 */
/**
 * Needs a database. Tagged so it runs in `integrationTest` rather than in `test`: the default tier has to
 * be runnable with no Docker daemon, and everything this class proves is a property of a real schema.
 */
@Tag("docker")
class EmbeddingDimensionCheckTest {

    private static Embedder producing(int dimensions) {
        return new Embedder() {
            @Override
            public float[] embed(String text, EmbedPurpose purpose) {
                return new float[dimensions];
            }

            @Override
            public List<float[]> embedBatch(List<String> texts, EmbedPurpose purpose) {
                return texts.stream().map(t -> new float[dimensions]).toList();
            }

            @Override
            public int dimensions() {
                return dimensions;
            }
        };
    }

    private static JdbcClient jdbc() {
        return JdbcClient.create(PostgresSupport.dataSource());
    }

    @Test
    void startupFailsWhenTheEmbedderIsWiderThanTheColumn() {
        assertThatThrownBy(() -> new EmbeddingDimensionCheck(jdbc(), producing(3072)).verify())
                .isInstanceOf(MemoryException.class)
                .satisfies(e -> assertThat(((MemoryException) e).code()).isEqualTo("embedding_dimension_mismatch"))
                // The message has to name both numbers, or it is no more useful than the 409 was.
                .hasMessageContaining("vector(1536)").hasMessageContaining("3072");
    }

    @Test
    void startupSucceedsWhenTheyAgree() {
        assertThatCode(() -> new EmbeddingDimensionCheck(jdbc(), producing(1536)).verify()).doesNotThrowAnyException();
    }
}
