package dev.dyad.core.config;

/**
 * Thresholds for the three-stage dedup.
 *
 * @param cosineDistanceMax stage 3 only considers candidates at or below this distance
 * @param uniqueTokenWeight information score is {@code |tokens| + weight × |distinct tokens|}; the
 *     weight is what makes a longer but repetitive rephrasing lose to a shorter specific one
 */
public record DedupSettings(double cosineDistanceMax, int uniqueTokenWeight) {

    public static final DedupSettings DEFAULT = new DedupSettings(0.05, 10);
}
