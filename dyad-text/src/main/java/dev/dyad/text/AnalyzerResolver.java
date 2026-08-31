package dev.dyad.text;

import dev.dyad.core.spi.Analyzer;

/**
 * Which analyzer a workspace uses.
 *
 * <p>Resolution is per workspace, not global. A deployment serving Korean and English tenants from
 * one database needs both at once, and {@code content_analyzed} plus a language-neutral index is
 * exactly what makes that possible.
 */
@FunctionalInterface
public interface AnalyzerResolver {

    Analyzer analyzerFor(String workspaceName);
}
