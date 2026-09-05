package at.aimon.memory.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/**
 * How far the persistence seam actually reaches, stated in a form that fails.
 *
 * <p>{@code core.spi} declared {@code ConclusionStore}, {@code EntityStore} and {@code EventLog}
 * while every caller in the build injected the concrete repository instead, so the interfaces were
 * implemented, never consumed, and steadily drifting from the classes they described. The claim they
 * underwrote — that another backend could be substituted by implementing them — was false, and
 * nothing said so.
 *
 * <p>This is what says so. The three are now the real seam and {@link #theSealedStoresAreReachedOnlyThroughTheirSpi()}
 * keeps them that way. The nine repositories below are not, and
 * {@link #everyOtherRepositoryIsStillReachedConcretely()} names them one by one rather than leaving
 * the gap to be rediscovered: sealing one is a line deleted from that list, and reaching for a tenth
 * fails the build.
 *
 * <p>So the honest form of the claim, today: <b>conclusions, entities and the audit log can be
 * backed by another implementation; the other nine tables cannot.</b> Whether they should be is a
 * decision rather than an omission — several of them, {@code QueueRepository} above all, are
 * Postgres semantics rather than storage. Its claim is an insert against a partial unique index
 * (ADR 0006's "클레임은 락이 아니라 insert"), and an interface over that would be a second
 * description of one implementation, which is the state this test exists to have ended.
 */
class SpiSurfaceTest {

    /**
     * The repositories whose callers outside {@code aimon-memory-store} have been moved onto an SPI.
     * Adding one here is the whole of what "sealing" means.
     */
    private static final List<String> SEALED = List.of("ConclusionRepository", "EntityRepository",
            "EventLogRepository");

    /**
     * Still injected by concrete type above the store module. Not a todo list — an inventory, so the
     * cost of the remaining work is a number rather than a feeling.
     */
    private static final List<String> NOT_SEALED = List.of("CollectionRepository", "DreamRepository",
            "MessageRepository", "PeerCardRepository", "PeerRepository", "QueueRepository", "SessionPeerRepository",
            "SessionRepository", "WorkspaceRepository");

    private static final List<String> ABOVE_THE_STORE = List.of("at.aimon.memory.recall..", "at.aimon.memory.engine..",
            "at.aimon.memory.api..", "at.aimon.memory.worker..");

    private static JavaClasses classes;

    @BeforeAll
    static void importEveryModule() {
        Path root = repositoryRoot();
        List<Path> outputs = new ArrayList<>();
        for (String module : List.of("core", "text", "embed", "llm", "store", "recall", "engine", "worker", "api")) {
            Path output = root.resolve("modules").resolve("aimon-memory-" + module).resolve("build/classes/java/main");
            if (Files.isDirectory(output)) {
                outputs.add(output);
            }
        }
        classes = new ClassFileImporter().importPaths(outputs.toArray(Path[]::new));
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null && !Files.exists(cursor.resolve("settings.gradle.kts"))) {
            cursor = cursor.getParent();
        }
        return cursor == null ? Path.of("").toAbsolutePath() : cursor;
    }

    /** The same guard {@code ModuleDependencyTest} has: a rule over nothing passes and proves nothing. */
    @Test
    void theModulesWereActuallyImported() {
        Assertions.assertThat(classes.size()).as("compiled classes found; run ./gradlew classes first if this fails")
                .isGreaterThan(150);
    }

    @Test
    void theSealedStoresAreReachedOnlyThroughTheirSpi() {
        for (String repository : SEALED) {
            noClasses().that().resideInAnyPackage(ABOVE_THE_STORE.toArray(String[]::new)).should().dependOnClassesThat()
                    .haveFullyQualifiedName("at.aimon.memory.store.repo." + repository)
                    .as(repository + " is reached through its core.spi interface, never by concrete type")
                    .because("an SPI that is implemented but not consumed is a second description of one class, "
                            + "and the substitutability it claims is not real")
                    .check(classes);
        }
    }

    /**
     * Fails when a repository on the unsealed list stops being used above the store — which means it
     * has been sealed, or has become dead, and this inventory is now wrong.
     *
     * <p>Written in the direction that goes stale loudly. A list of known gaps that nobody has to
     * update is a list that stops matching the tree, which is the failure mode the SPIs themselves
     * had.
     */
    @Test
    void everyOtherRepositoryIsStillReachedConcretely() {
        List<String> noLongerConcrete = new ArrayList<>();
        for (String repository : NOT_SEALED) {
            String name = "at.aimon.memory.store.repo." + repository;
            boolean reached = classes.stream()
                    .filter(c -> ABOVE_THE_STORE.stream()
                            .anyMatch(p -> c.getPackageName().startsWith(p.substring(0, p.length() - 2))))
                    .anyMatch(c -> c.getDirectDependenciesFromSelf().stream()
                            .anyMatch(d -> d.getTargetClass().getFullName().equals(name)));
            if (!reached) {
                noLongerConcrete.add(repository);
            }
        }
        Assertions.assertThat(noLongerConcrete)
                .as("no longer reached by concrete type above the store — seal them in core.spi and move them "
                        + "from NOT_SEALED to SEALED, or delete them if they have become dead")
                .isEmpty();
    }
}
