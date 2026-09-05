package at.aimon.memory.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.memory.api.dto.Requests;
import at.aimon.memory.api.security.MemoryPrincipal;
import at.aimon.memory.api.security.PairScope;
import at.aimon.memory.core.MemoryException;
import at.aimon.memory.core.spi.LlmClient;
import at.aimon.memory.engine.dialectic.DialecticService;
import at.aimon.memory.engine.dialectic.ToolRegistry;

/**
 * The SSE worker's handling of the provider stream, driven directly.
 *
 * <p>{@code answerStreaming} hands back a stream that is, on its fallback branch, an open HTTP
 * exchange with a model provider — {@code BodySubscribers.ofLines} requires the caller to read it to
 * exhaustion or close it, or the exchange is never released. Nothing here asserts on the emitter,
 * because an uninitialised {@link SseEmitter} buffers its sends and never fires its callbacks; the
 * paths that need those live in {@code ChatStreamAbortTest}, which drives a real one through MockMvc.
 *
 * <p>What this file can say deterministically is the part that matters most: whichever way the worker
 * leaves the loop, the stream is closed. Every wait below is on a latch the stream itself counts down,
 * so nothing here sleeps.
 */
class ChatStreamTest {

    private static final String WORKSPACE = "ws";

    /** Long enough that a loaded machine cannot fail it, short enough that a hang is a failure. */
    private static final int WAIT_SECONDS = 10;

    /** Records whether the stream it wraps was closed, and lets a test wait for that to happen. */
    private static final class TrackedStream {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final CountDownLatch closeSeen = new CountDownLatch(1);
        private final AtomicInteger pulled = new AtomicInteger();
        private final Stream<String> stream;

        TrackedStream(Stream<String> source) {
            this.stream = source.peek(chunk -> pulled.incrementAndGet()).onClose(() -> {
                closed.set(true);
                closeSeen.countDown();
            });
        }

        void awaitClose() throws InterruptedException {
            assertThat(closeSeen.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("the worker closed the provider stream")
                    .isTrue();
        }
    }

    private static ChatController controllerReturning(Stream<String> stream) {
        DialecticService stub = new DialecticService((LlmClient) null, (ToolRegistry) null) {
            @Override
            public Stream<String> answerStreaming(Question question) {
                return stream;
            }
        };
        return new ChatController(stub, new ObjectMapper(), new PairScope());
    }

    private static Requests.ChatRequest request() {
        return new Requests.ChatRequest("what does alice do", "alice", "bob", null, null, List.of(), null);
    }

    private static SseEmitter start(ChatController controller) {
        return controller.stream(WORKSPACE, MemoryPrincipal.admin(), request());
    }

    /**
     * The ordinary path: every chunk is read and the stream is closed on the way out.
     *
     * <p>Exhausting it would already release the exchange, so this is the case that was never broken.
     * It is here because it is the one the other tests are measured against — a close that only
     * happens when nothing goes wrong is exactly the bug the try-with-resources was added for.
     */
    @Test
    void aStreamReadToTheEndIsClosed() throws Exception {
        TrackedStream tracked = new TrackedStream(Stream.of("alpha", "beta", "gamma"));

        start(controllerReturning(tracked.stream));
        tracked.awaitClose();

        assertThat(tracked.closed).isTrue();
        assertThat(tracked.pulled).hasValue(3);
    }

    /**
     * A provider that fails mid-stream still gets its exchange released.
     *
     * <p>This is the shape of an Anthropic or OpenAI {@code error} event arriving after the headers
     * were accepted: the backend raises {@code llm_stream_error} from inside the pipeline, so the
     * exception comes out of {@code forEach} rather than from the call that opened the stream. Without
     * the try-with-resources the stream is abandoned mid-body with lines unread — the connection is
     * never returned, and the only trace is a warning about a failed chat.
     */
    @Test
    void aStreamThatThrowsPartWayThroughIsStillClosed() throws Exception {
        TrackedStream tracked = new TrackedStream(Stream.of("alpha", "boom", "never").peek(chunk -> {
            if ("boom".equals(chunk)) {
                // The code an LlmException carries; raised as its supertype because the api module does
                // not depend on the llm module and does not need to in order to say this.
                throw new MemoryException("llm_stream_error", "anthropic ended the stream with overloaded_error");
            }
        }));

        start(controllerReturning(tracked.stream));
        tracked.awaitClose();

        assertThat(tracked.closed).isTrue();
        // One: "alpha" got through, "boom" threw before reaching the counter, and "never" was never
        // pulled at all. The last of those is the point — the stream was abandoned with elements still
        // in it, which is the case where an unclosed exchange stays open.
        assertThat(tracked.pulled).hasValue(1);
    }

    /**
     * An empty answer closes the stream too.
     *
     * <p>Reachable whenever the provider returns nothing at all, and the shortest path through the
     * loop — the predicate is never evaluated, so a close that hung off the loop rather than off the
     * resource would be skipped entirely.
     */
    @Test
    void anEmptyStreamIsClosed() throws Exception {
        TrackedStream tracked = new TrackedStream(Stream.of());

        start(controllerReturning(tracked.stream));
        tracked.awaitClose();

        assertThat(tracked.pulled).hasValue(0);
    }

    /**
     * The other branch of {@code answerStreaming} is safe under the same form.
     *
     * <p>When the tool loop already produced an answer the method hands back a {@code Stream.of(text)},
     * which holds nothing and has no close handler. One try-with-resources has to cover both branches,
     * and closing this one must be a no-op rather than an error — this is the branch nearly every real
     * request takes.
     */
    @Test
    void closingTheNonProviderBranchIsHarmless() throws Exception {
        CountDownLatch consumed = new CountDownLatch(1);
        Stream<String> answered = Stream.of("the tool loop already answered").onClose(consumed::countDown);

        start(controllerReturning(answered));

        assertThat(consumed.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("a Stream.of answer is closed like any other")
                .isTrue();
    }

    /**
     * A failure from {@code answerStreaming} itself is caught rather than killing the worker thread.
     *
     * <p>There is no resource to release here — the call never returned one — so the only thing to
     * establish is that the exception is handled where the other failures are, and does not escape
     * onto a virtual thread whose uncaught-exception handler nothing reads.
     */
    @Test
    void aFailureOpeningTheStreamIsHandled() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        DialecticService failing = new DialecticService((LlmClient) null, (ToolRegistry) null) {
            @Override
            public Stream<String> answerStreaming(Question question) {
                attempted.countDown();
                throw new MemoryException("llm_exhausted", "all 2 attempts failed");
            }
        };

        SseEmitter emitter = new ChatController(failing, new ObjectMapper(), new PairScope()).stream(WORKSPACE,
                MemoryPrincipal.admin(), request());

        assertThat(attempted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(emitter).isNotNull();
    }
}
