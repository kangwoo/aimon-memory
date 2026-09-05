package at.aimon.memory.store;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Text analysis settings, held here because this is the lowest module that can hold them.
 *
 * <p>The natural home is {@code aimon-memory-text}, which owns {@code AnalyzerRegistry} — but that
 * module has no Spring on its classpath and is better for it, so a {@code @ConfigurationProperties}
 * cannot live there. This module is the next one up and the one whose bean needs the registry:
 * {@link WorkspaceSettingsService} resolves a workspace's analyzer through it.
 *
 * <p>Moved from {@code MemoryProperties.Text} in aimon-memory-engine. The prefix is unchanged, so no
 * deployment's {@code application.yml} moves with it.
 *
 * @param koreanUserDictionary path to a Nori user dictionary; the fix for split proper nouns
 */
@ConfigurationProperties(prefix = "aimon.memory.text")
public record TextProperties(String koreanUserDictionary) {
}
