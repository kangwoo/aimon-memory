package dev.dyad.core.spi;

/**
 * Why something is being embedded.
 *
 * <p>Providers that distinguish query from document vectors need this; the ones that do not ignore
 * it. Passing it through costs nothing and avoids a migration if the provider changes.
 */
public enum EmbedPurpose {
    DOCUMENT,
    QUERY,
    ENTITY
}
