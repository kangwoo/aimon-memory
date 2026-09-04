package at.aimon.memory.engine.dialectic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.memory.core.spi.llm.ToolCall;
import at.aimon.memory.text.TokenCounter;

/**
 * What the streaming fallback hands the model when the loop ran out of iterations.
 *
 * <p>The instruction that frames the dump — answer from these alone, and say so if they do not settle
 * it — used to be appended and then truncated away with everything else. It went missing in exactly
 * the case it is written for: the fallback fires when the loop hit its limit with tools still
 * returning, which is when the rendered output most reliably exceeds the budget. What reached the
 * model then was a bare dump of tool output with no framing at all, against a system prompt that says
 * it knows nothing except what the tools return.
 */
class FindingsRenderingTest {

    private static List<ToolCall> findings(int count, int charsEach) {
        List<ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            calls.add(new ToolCall("search_conclusions", "{\"query\":\"q" + i + "\"}",
                    "finding " + i + " " + "가나다라마바사 ".repeat(charsEach), false));
        }
        return calls;
    }

    @Test
    void theFramingSurvivesATruncatedBody() {
        String rendered = DialecticService.renderFindings(findings(200, 200));

        assertThat(TokenCounter.count(rendered)).as("the whole message still fits the budget it was given")
                .isLessThanOrEqualTo(DialecticService.MAX_FINDINGS_TOKENS);
        assertThat(rendered).startsWith("What the tools returned so far:");
        assertThat(rendered).as("the model has to be told the search was cut short, especially when it was")
                .contains("cut short at its iteration limit").contains("Answer from these results alone");
    }

    /** What falls off the end is the oldest search, not the one made knowing what the others returned. */
    @Test
    void truncationDropsTheOldestFindingsFirst() {
        List<ToolCall> calls = findings(200, 200);
        String rendered = DialecticService.renderFindings(calls);

        assertThat(rendered).contains("\"query\":\"q199\"");
        assertThat(rendered).doesNotContain("\"query\":\"q0\"}");
    }

    @Test
    void aShortSetIsRenderedWhole() {
        String rendered = DialecticService.renderFindings(findings(2, 1));

        assertThat(rendered).contains("\"query\":\"q0\"").contains("\"query\":\"q1\"");
        assertThat(rendered).contains("Answer from these results alone");
    }

    /** A failed call is information, and it has to survive rendering as such. */
    @Test
    void failedCallsAreLabelled() {
        String rendered = DialecticService
                .renderFindings(List.of(new ToolCall("grep_messages", "{}", "timed out", true)));

        assertThat(rendered).contains("failed: timed out");
    }
}
