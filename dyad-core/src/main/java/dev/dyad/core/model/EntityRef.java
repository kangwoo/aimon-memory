package dev.dyad.core.model;

/**
 * An entity node. Workspace-scoped, so "서울" is embedded and stored once no matter how many pairs
 * reference it — the pair scope lives on the link, not the node.
 */
public record EntityRef(
        String id, String workspaceName, String nameNorm, String nameDisplay, String kind) {}
