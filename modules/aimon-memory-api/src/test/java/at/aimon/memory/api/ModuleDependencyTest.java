package at.aimon.memory.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Module dependencies only ever point downwards.
 *
 * <p>The layering is what lets three tracks build against each other's stubs instead of each other's
 * code, and it degrades the moment one convenient upward import is added — at which point the next
 * one is easy to justify. This is the only place the rule is written down in a form that fails.
 *
 * <p>Classes are read from the compiled output of every module rather than from this module's
 * classpath, so the check covers the whole system and not just what the API happens to depend on.
 */
class ModuleDependencyTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importEveryModule() {
        Path root = repositoryRoot();
        List<Path> outputs = new ArrayList<>();
        for (String module : List.of("core", "testkit", "text", "embed", "llm", "store", "recall", "engine", "worker",
                "api")) {
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

    private static ArchRule mayOnlyDependOn(String module, String... allowed) {
        List<String> permitted = new ArrayList<>(List.of("at.aimon.memory." + module + ".."));
        for (String other : allowed) {
            permitted.add("at.aimon.memory." + other + "..");
        }
        List<String> forbidden = new ArrayList<>();
        for (String candidate : List.of("core", "testkit", "text", "embed", "llm", "store", "recall", "engine",
                "worker", "api")) {
            if (permitted.stream().noneMatch(p -> p.equals("at.aimon.memory." + candidate + ".."))) {
                forbidden.add("at.aimon.memory." + candidate + "..");
            }
        }
        return noClasses().that().resideInAPackage("at.aimon.memory." + module + "..").should().dependOnClassesThat()
                .resideInAnyPackage(forbidden.toArray(String[]::new))
                .as("aimon-memory-" + module + " may only depend on " + String.join(", ", allowed));
    }

    /**
     * Guard against the failure mode that makes every other rule here vacuous: if the import found no
     * classes, every {@code noClasses()} rule passes trivially and the architecture is unchecked.
     */
    @Test
    void everyModuleWasActuallyImported() {
        org.assertj.core.api.Assertions.assertThat(classes.size())
                .as("compiled classes found; run ./gradlew classes first if this fails").isGreaterThan(150);

        for (String module : List.of("core", "text", "embed", "llm", "store", "recall", "engine", "worker", "api",
                "testkit")) {
            org.assertj.core.api.Assertions
                    .assertThat(
                            classes.stream().anyMatch(c -> c.getPackageName().startsWith("at.aimon.memory." + module)))
                    .as("classes imported for aimon-memory-%s", module).isTrue();
        }
    }

    /** The contract module is the root of the graph. One dependency here and the graph has a cycle. */
    @Test
    void coreDependsOnNothing() {
        mayOnlyDependOn("core").check(classes);
    }

    @Test
    void leafUtilityModulesStayLeaves() {
        mayOnlyDependOn("text", "core").check(classes);
        mayOnlyDependOn("llm", "core").check(classes);
        mayOnlyDependOn("testkit", "core", "text").check(classes);
        // embed uses the tokenizer so that truncation and the batch gate agree on what a token is.
        mayOnlyDependOn("embed", "core", "text").check(classes);
    }

    @Test
    void persistenceSitsAboveTextAndBelowEverythingElse() {
        mayOnlyDependOn("store", "core", "text").check(classes);
    }

    @Test
    void recallDoesNotReachTheModelLayer() {
        // Tier 1 calling an LLM would defeat the entire point of it being Tier 1.
        mayOnlyDependOn("recall", "core", "store", "text", "embed").check(classes);
    }

    /** Tier 2 exposes Tier 1 as a tool, which is why memory is allowed to see recall. */
    @Test
    void memorySeesRecallButNotTheRunnableApplications() {
        mayOnlyDependOn("engine", "core", "store", "recall", "llm", "embed", "text").check(classes);
    }

    /** Two processes, one codebase. Neither entry point may reach into the other. */
    @Test
    void theTwoApplicationsAreIndependentOfEachOther() {
        mayOnlyDependOn("api", "core", "store", "recall", "engine", "llm", "embed", "text").check(classes);
        mayOnlyDependOn("worker", "core", "store", "recall", "engine", "llm", "embed", "text").check(classes);
    }

    /** Test scaffolding must never be reachable from a running system. */
    @Test
    void productionCodeNeverDependsOnTheTestkit() {
        noClasses().that().resideOutsideOfPackage("at.aimon.memory.testkit..").should().dependOnClassesThat()
                .resideInAPackage("at.aimon.memory.testkit..")
                .as("nothing outside aimon-memory-testkit may depend on it").check(classes);
    }

    /** SQL lives in one module, so a schema change has one place to look. */
    @Test
    void jdbcIsConfinedToThePersistenceModule() {
        noClasses().that().resideOutsideOfPackages("at.aimon.memory.store..", "at.aimon.memory.testkit..").should()
                .dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..", "org.postgresql..")
                .as("only aimon-memory-store talks to JDBC").check(classes);
    }
}
