package dev.dyad.memory.prompt;

/**
 * Every system prompt, written for this project.
 *
 * <p>Prompts are the one part of a memory system that is unambiguously expressive work rather than
 * architecture, so these are authored from the specification and not adapted from anything. That is a
 * licensing position as much as an engineering one.
 *
 * <p>{@link #VERSION} is bumped whenever any prompt below changes. Two things key off it: the LLM
 * fixtures stop matching, which is the intended alarm, and the entity re-index job knows that
 * extraction behaviour may have shifted under it. Entity quality is downstream of the extraction
 * prompt — that is the cost of getting entities for free — so the version has to be tracked rather
 * than assumed stable.
 */
public final class Prompts {

    public static final String VERSION = "2026-08-31.2";

    private Prompts() {}

    /**
     * Extraction. The observer/observed framing is what makes a conclusion belong to a pair: the same
     * conversation produces different memories depending on who is doing the remembering.
     */
    public static String deriver(String observer, String observed) {
        // The parentheses are load-bearing: `.formatted` binds tighter than `+`, so without them it
        // applied to the second literal alone. Three %s went out unsubstituted and the fourth got the
        // observer's name, which made every cross-peer extraction run on an instruction that
        // contradicted itself. The self branch was correct, so single-peer tests never saw it.
        String perspective =
                observer.equals(observed)
                        ? "You are building %s's own memory of themselves.".formatted(observer)
                        : ("You are building %s's memory of %s. Record only what %s could reasonably"
                                        + " conclude about %s from this conversation.")
                                .formatted(observer, observed, observer, observed);
        return """
               You extract durable facts from conversation.

               %s

               Write one conclusion per distinct fact about %s. A conclusion is worth storing only if
               it would still be useful weeks from now.

               Rules:
               - State each conclusion as a complete sentence that stands on its own. "Works at a bank"
                 is useless without a subject; "%s works at a bank" is not.
               - Write in the language the speakers used.
               - Record preferences, commitments, relationships, constraints and stable attributes.
               - Do not record small talk, transient state, or anything the speaker is asking about
                 rather than asserting.
               - Do not infer beyond what was said. If someone mentions a flight to Osaka, they went to
                 Osaka; they are not "a frequent traveller".
               - If a message contradicts something said earlier in the same conversation, record the
                 later version only.
               - If nothing durable was said, return an empty list. An empty list is a correct answer.

               For each conclusion, list the named entities it mentions: people, organisations, places,
               products, topics and identifiers. Use the surface form from the text. Omit generic nouns
               and anything that is not a name.
               """
                .formatted(perspective, observed, observed);
    }

    /** Short rolling summary. Bounded hard, because it is prepended to other prompts. */
    public static final String SUMMARY_SHORT =
            """
            Summarise this conversation in at most six sentences.

            Keep: what was decided, what was asked for, what is still open, and who is involved.
            Drop: pleasantries, restatements, and anything already captured in an earlier summary
            you were given.

            If an earlier summary is provided, produce a summary of the whole conversation so far —
            not a summary of the new messages alone. The result replaces the earlier summary entirely.
            Write in the language of the conversation.
            """;

    /** Long summary, allowed to keep detail the short one drops. */
    public static final String SUMMARY_LONG =
            """
            Write a detailed summary of this conversation, at most twenty sentences.

            Preserve specifics that a short summary would lose: names, numbers, dates, decisions and
            their stated reasons, and disagreements that were not resolved.

            If an earlier summary is provided, produce a summary covering everything so far. The
            result replaces the earlier summary entirely. Write in the language of the conversation.
            """;

    /**
     * Dialectic. Written around the failure modes an agentic memory query actually has: answering an
     * enumeration from one search, reporting superseded facts as current, and inventing a bridge
     * between two conclusions that only look related.
     */
    public static String dialectic(String observer, String observed) {
        return """
               You answer questions about what is known, using the tools provided.

               You are answering as %s, about %s. You know nothing except what the tools return. You
               have no memory of your own and no general knowledge about these people.

               How to work:

               - For an enumeration ("what are all the …", "how many …"), one search is not enough.
                 Search, then search again with the terms the first results suggest, until a search
                 stops adding anything new. Say how you searched.
               - For anything that can change — a job, a location, a preference, a plan — check whether
                 a later conclusion supersedes an earlier one. Search for the updated form, not only
                 the original. Reinforcement count and date are in the results; use them.
               - When two results conflict, say so and give both with their dates. Do not silently pick
                 one.
               - When you need to know why something is believed, follow its reasoning chain or its
                 entity provenance rather than guessing.
               - Never state as fact anything no tool returned. If the answer is not there, say it is
                 not known. That is a useful answer and a fabricated one is not.

               Answer in the language of the question. Be direct: no preamble, no restating the
               question, no offers of further help.
               """
                .formatted(observer, observed);
    }

    /** Deduction: only what follows necessarily. The whole value is in refusing the near-misses. */
    public static final String DREAM_DEDUCTION =
            """
            You are given facts that are already known about one person.

            Derive only conclusions that follow necessarily. If someone lives in Busan and works in
            Busan, they do not commute between cities. That is a deduction. If someone likes coffee and
            works near a cafe, they go to that cafe — that is a guess, and it does not belong here.

            For each conclusion, list the exact ids of the facts it follows from. A conclusion whose
            premises you cannot name is not a deduction.

            Do not restate a given fact. Do not produce anything that is merely plausible. Returning
            nothing is the right answer more often than not.
            """;

    /** Induction: patterns, with an honest confidence attached. */
    public static final String DREAM_INDUCTION =
            """
            You are given facts that are already known about one person.

            Identify patterns across several of them: a habit, a preference, a tendency, a recurring
            constraint. A pattern needs at least three supporting facts.

            For each, give a confidence between 0 and 1 — how strongly the facts support it, not how
            plausible it sounds. Three weak signals is 0.4, not 0.9.

            List the ids of the facts each pattern rests on. Do not produce patterns from a single
            fact, and do not restate what you were given.
            """;

    /** Contradiction: find them, never resolve them. */
    public static final String DREAM_CONTRADICTION =
            """
            You are given facts that are already known about one person.

            Find pairs that cannot both be true at the same time about the same thing.

            Not a contradiction: facts that changed over time (a past job and a current one), facts
            about different subjects, or facts that are merely in tension.

            For each contradiction, state what the conflict is and list the ids of both facts. Do not
            decide which is correct — recording the conflict is the job; resolving it is not.
            """;

    /** Peer card: a fixed-shape, wholly-replaced profile. */
    public static final String PEER_CARD =
            """
            Write a profile from the facts you are given.

            Every line must start with exactly one of these prefixes:
              IDENTITY:      who they are
              ATTRIBUTE:     a stable property
              RELATIONSHIP:  a connection to someone or something
              INSTRUCTION:   how they have asked to be treated

            At most forty lines. One fact per line, shortest wording that stays accurate. Order by how
            much it would change an answer if it were missing.

            Include nothing that is not in the given facts. Omit anything transient. This replaces the
            previous profile in full, so anything worth keeping must be written again.
            """;
}
