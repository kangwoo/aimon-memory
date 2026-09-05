package at.aimon.memory.api;

/**
 * Caps on the numbers a caller gets to choose.
 *
 * <p>Every paging and limit parameter on the HTTP surface arrives from the network. Left uncapped,
 * {@code ?size=10000000} is an out-of-memory condition available to anyone with a token, and
 * {@code recall(limit)} is worse than linear because the ranker oversamples each signal path by a
 * multiple of it.
 *
 * <p>The tool layer already clamped the arguments a <em>model</em> composes. That the network path
 * did not is the trust relationship backwards.
 *
 * <p>Clamped rather than rejected. A client asking for more than the maximum has not made an error
 * worth failing on — it wants as much as it can get, and pagination already tells it whether more
 * remains. A filter that cannot be honoured is a different matter and is still a 422.
 */
public final class Bounds {

    public static final int MAX_PAGE_SIZE = 200;
    public static final int MAX_RECALL_LIMIT = 100;
    public static final int MAX_HISTORY = 500;
    public static final int MAX_PAGE = 10_000;

    /**
     * Ceiling on {@code ?tokens} for Tier 0 context.
     *
     * <p>This parameter is a budget, and the budget is the only thing bounding the response —
     * {@code ContextService} keeps messages until it is spent. So an unbounded budget is an unbounded
     * response, and this was the one number on the surface that did not pass through this class.
     * {@code MAX_WINDOW} caps the rows read at five thousand, but nothing caps a message's length, so
     * {@code ?tokens=2147483647} returned all five thousand in full while every other paging route
     * here stops at two hundred.
     *
     * <p>128k is the largest context window a model could actually be handed. Past it the parameter
     * has stopped being a budget and is only a lever on response size, which is what the cap is for.
     */
    public static final int MAX_CONTEXT_TOKENS = 128_000;

    private Bounds() {
    }

    public static int page(int requested) {
        return Math.max(0, Math.min(MAX_PAGE, requested));
    }

    public static int size(int requested) {
        return requested <= 0 ? 50 : Math.min(MAX_PAGE_SIZE, requested);
    }

    public static int recallLimit(Integer requested) {
        if (requested == null || requested <= 0) {
            return 10;
        }
        return Math.min(MAX_RECALL_LIMIT, requested);
    }

    public static int history(int requested) {
        return requested <= 0 ? 100 : Math.min(MAX_HISTORY, requested);
    }

    /**
     * A zero or negative budget falls back to the default rather than being clamped to zero: a
     * negative one made {@code ContextService} compute a negative summary budget and then return
     * exactly one message, which reads as an empty session rather than as a rejected parameter.
     */
    public static int contextTokens(int requested) {
        return requested <= 0 ? 4_000 : Math.min(MAX_CONTEXT_TOKENS, requested);
    }
}
