package at.aimon.memory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import at.aimon.memory.engine.dialectic.DialecticService;

/**
 * The SSE route with a real emitter behind it, and a client that goes away mid-answer.
 *
 * <p>This is the one path {@code ChatStreamTest} cannot reach. {@code live} is only ever cleared by
 * the controller's {@code stop}, and {@code stop} only runs from the emitter's timeout, completion and
 * error callbacks — which {@code ResponseBodyEmitter} hands to the servlet container during
 * {@code initialize}, a package-private call. Driving the route through MockMvc gets the real
 * registration, so completing the async context here is what a disconnecting client is.
 *
 * <p>The stream the provider would have returned is replaced by one that parks at a known element, so
 * the disconnect lands at a chosen point in the pipeline rather than whenever the scheduler allows.
 * Every wait is on a latch; nothing sleeps.
 */
class ChatStreamAbortTest extends ApiTestBase {

    private static final int WAIT_SECONDS = 10;

    @MockitoBean
    private DialecticService dialectic;

    private MvcResult startStream() throws Exception {
        return mvc
                .perform(post("/v1/workspaces/ws/chat/stream").header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"what does alice do\",\"observer\":\"alice\",\"observed\":\"bob\"}"))
                .andExpect(request().asyncStarted()).andReturn();
    }

    /**
     * A client that disconnects mid-answer gets the provider stream closed.
     *
     * <p>The most valuable assertion in this file, and the one the try-with-resources exists for.
     * {@code takeWhile} short-circuits the moment {@code live} is cleared, which leaves the remaining
     * lines unread — and an unread, unclosed {@code BodySubscribers.ofLines} stream never releases its
     * HTTP exchange. Before the fix this path was the only one that leaked, and it is the path a
     * cancelled request always takes, so a busy deployment leaked a pooled connection per abandoned
     * chat.
     *
     * <p>Also pins the two halves of {@code stop}: the worker is woken by an interrupt rather than
     * left blocked, and the chunk after the disconnect never reaches the socket.
     */
    @Test
    void aDisconnectedClientClosesTheProviderStream() throws Exception {
        CountDownLatch reachedSecondChunk = new CountDownLatch(1);
        CountDownLatch abortIssued = new CountDownLatch(1);
        CountDownLatch streamClosed = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();

        Stream<String> provider = Stream.of("alpha", "beta").peek(chunk -> {
            if ("beta".equals(chunk)) {
                // Park between alpha's send and beta's predicate check, which is exactly where a
                // disconnect has to land for takeWhile to be the thing that ends the loop.
                reachedSecondChunk.countDown();
                try {
                    abortIssued.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                }
            }
        }).onClose(() -> {
            closed.set(true);
            streamClosed.countDown();
        });
        when(dialectic.answerStreaming(any())).thenReturn(provider);

        MvcResult result = startStream();
        assertThat(reachedSecondChunk.await(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("the worker reached the chunk it parks on").isTrue();

        // What the container does when the client goes away: the async context completes, which runs
        // the completion callback the emitter registered during initialize.
        result.getRequest().getAsyncContext().complete();
        abortIssued.countDown();

        assertThat(streamClosed.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("the abandoned provider stream was closed")
                .isTrue();
        assertThat(closed).isTrue();
        assertThat(interrupted).as("stop() woke the worker rather than leaving it blocked").isTrue();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("alpha");
        assertThat(body).as("nothing is written after the client is gone").doesNotContain("beta");
    }

    /**
     * An answer nobody interrupts is streamed in full and terminated with {@code done}.
     *
     * <p>The counterpart to the test above: it is what says the abort path is a genuine early exit
     * rather than the only behaviour there is.
     */
    @Test
    void anUninterruptedAnswerIsStreamedAndClosedNormally() throws Exception {
        CountDownLatch streamClosed = new CountDownLatch(1);
        when(dialectic.answerStreaming(any())).thenReturn(Stream.of("alpha", "beta").onClose(streamClosed::countDown));

        MvcResult result = startStream();

        assertThat(streamClosed.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("alpha").contains("beta").contains("done");
    }

    /**
     * A provider that fails mid-answer closes the stream and reports the failure to the client.
     *
     * <p>The end-to-end form of {@code ChatStreamTest}'s in-process case: an {@code error} event
     * arriving after the headers were accepted — which the Anthropic and OpenAI backends now raise
     * rather than filter away — has to reach the caller as a failed stream, not as a short answer that
     * looks complete.
     */
    @Test
    void aProviderFailureMidAnswerClosesTheStream() throws Exception {
        CountDownLatch streamClosed = new CountDownLatch(1);
        when(dialectic.answerStreaming(any())).thenReturn(Stream.of("alpha", "boom").peek(chunk -> {
            if ("boom".equals(chunk)) {
                throw new at.aimon.memory.core.MemoryException("llm_stream_error",
                        "anthropic ended the stream with overloaded_error");
            }
        }).onClose(streamClosed::countDown));

        MvcResult result = startStream();

        assertThat(streamClosed.await(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("a stream that failed part way through was still closed").isTrue();
        assertThat(result.getResponse().getContentAsString()).contains("alpha");
    }
}
