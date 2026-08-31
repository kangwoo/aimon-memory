package dev.dyad.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
        for (String module : List.of("core", "testkit", "text", "embed", "llm", "store", "recall",
                "memory", "worker", "api")) {
            Path output = root.resolve("dyad-" + module).resolve("build/classes/java/main");
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
        List<String> permitted = new ArrayList<>(List.of("dev.dyad." + module + ".."));
        for (String other : allowed) {
            permitted.add("dev.dyad." + other + "..");
        }
        List<String> forbidden = new ArrayList<>();
        for (String candidate : List.of("core", "testkit", "text", "embed", "llm", "store", "recall",
                "memory", "worker", "api")) {
            if (permitted.stream().noneMatch(p -> p.equals("dev.dyad." + candidate + ".."))) {
                forbidden.add("dev.dyad." + candidate + "..");
            }
        }
        return noClasses()
                .that()
                .resideInAPackage("dev.dyad." + module + "..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(forbidden.toArray(String[]::new))
                .as("dyad-" + module + " may only depend on " + String.join(", ", allowed));
    }

    /**
     * Guard against the failure mode that makes every other rule here vacuous: if the import found no
     * classes, every {@code noClasses()} rule passes trivially and the architecture is unchecked.
     */
    @Test
    void everyModuleWasActuallyImported() {
        org.assertj.core.api.Assertions.assertThat(classes.size())
                .as("compiled classes found; run ./gradlew classes first if this fails")
                .isGreaterThan(150);

        for (String module : List.of("core", "text", "embed", "llm", "store", "recall", "memory",
                "worker", "api", "testkit")) {
            org.assertj.core.api.Assertions.assertThat(
                            classes.stream()
                                    .anyMatch(c -> c.getPackageName().startsWith("dev.dyad." + module)))
                    .as("classes imported for dyad-%s", module)
                    .isTrue();
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
        mayOnlyDependOn("memory", "core", "store", "recall", "llm", "embed", "text").check(classes);
    }

    /** Two processes, one codebase. Neither entry point may reach into the other. */
    @Test
    void theTwoApplicationsAreIndependentOfEachOther() {
        mayOnlyDependOn("api", "core", "store", "recall", "memory", "llm", "embed", "text").check(classes);
        mayOnlyDependOn("worker", "core", "store", "recall", "memory", "llm", "embed", "text").check(classes);
    }

    /** Test scaffolding must never be reachable from a running system. */
    @Test
    void productionCodeNeverDependsOnTheTestkit() {
        noClasses()
                .that()
                .resideOutsideOfPackage("dev.dyad.testkit..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("dev.dyad.testkit..")
                .as("nothing outside dyad-testkit may depend on it")
                .check(classes);
    }

    /** SQL lives in one module, so a schema change has one place to look. */
    @Test
    void jdbcIsConfinedToThePersistenceModule() {
        noClasses()
                .that()
                .resideOutsideOfPackages("dev.dyad.store..", "dev.dyad.testkit..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("java.sql..", "javax.sql..", "org.postgresql..")
                .as("only dyad-store talks to JDBC")
                .check(classes);
    }
}
