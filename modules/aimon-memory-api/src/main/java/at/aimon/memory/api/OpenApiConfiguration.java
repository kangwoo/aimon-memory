package at.aimon.memory.api;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import at.aimon.memory.api.dto.Dtos;
import at.aimon.memory.api.security.MemoryPrincipal;
import at.aimon.memory.api.security.RoutePolicy;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

/**
 * The generated OpenAPI description of this service.
 *
 * <p>The document exists because the wire contract had three descriptions and no authority: the DTO
 * records, the curl examples in the README, and {@link RoutePolicy}, which knows who may call a route
 * but nothing about its shape. A consumer outside this repository — and there is one, in another
 * language before long — had only the Java source to read.
 *
 * <p>{@code docs/openapi.json} is the copy that matters. It is generated from this configuration and
 * committed, so a route that changes shape changes a reviewable file; {@code OpenApiSpecTest} fails
 * when the two drift. The live {@code /v3/api-docs} is a development convenience and is off unless
 * {@code AIMON_MEMORY_OPENAPI} says otherwise — see {@code application.yml} for why.
 */
@Configuration
public class OpenApiConfiguration {

    /** The security scheme's key, referenced by the global requirement below. */
    private static final String BEARER = "bearer";

    private static final String MALFORMED = """
            Malformed: a body that is not JSON, a field that fails validation, or a parameter of the \
            wrong type.""";

    private static final String UNAUTHENTICATED = "No bearer token, or one that does not verify.";

    private static final String TOO_NARROW = """
            The token verifies but is narrower than this route requires, or does not cover the workspace, \
            peer or session named in the path.""";

    private static final String ABSENT = """
            No such endpoint, or no such workspace, peer, session or conclusion. Also returned in place of \
            403 where telling the two apart would say whether another pair's row exists.""";

    private static final String INTERNAL = """
            The request could not be completed. The body says no more than that, deliberately.""";

    /** True of every route here. The ones that are not are in {@link #ROUTE_ERRORS}. */
    private static final Map<String, String> ERRORS = Map.of("400", MALFORMED, "401", UNAUTHENTICATED, "403",
            TOO_NARROW, "404", ABSENT, "500", INTERNAL);

    private static final String NO_PROVIDER = "No model provider is configured.";

    private static final String BAD_FILTER = """
            The filter parsed but was rejected: an unfilterable column, or an operand of the wrong type. \
            Refused rather than quietly dropped, which would answer 200 with rows nobody asked for.""";

    private static final String BAD_CONFIGURATION = """
            The configuration parsed but was rejected: an unrecognised key, or a value out of range. \
            Nothing is stored.""";

    private static final String DREAM_IN_FLIGHT = """
            A dream is already running for this pair. A partial unique index decides that, so a caller \
            racing the automatic scheduler finds out here rather than from a stale read.""";

    private static final String CONFLICT = """
            The request conflicts with existing data, or names a workspace, peer or session that does \
            not exist. The database refused it, and the reply says no more than that: the driver's \
            message quotes the constraint, the table and often the value.""";

    /** The non-GET routes that touch no table, and so can never answer 409. */
    private static final Set<String> READS_ONLY = Set.of("POST /v1/tokens", "POST /v1/workspaces/{workspace}/chat",
            "POST /v1/workspaces/{workspace}/chat/stream", "POST /v1/workspaces/{workspace}/recall",
            "POST /v1/workspaces/{workspace}/sessions/{session}/messages/search");

    /**
     * Status codes that belong to particular routes.
     *
     * <p>A table rather than {@code @ApiResponse} annotations on the methods, which is where these
     * started. Annotating a route with an error suppresses the success response springdoc infers from
     * the return type, so eight routes silently lost their 200 and the schema of what they return —
     * and the document still looked complete. Writing the exceptions here cannot do that.
     */
    private static final Map<String, Map<String, String>> ROUTE_ERRORS = Map.ofEntries(
            Map.entry("POST /v1/workspaces/{workspace}/chat", Map.of("503", NO_PROVIDER)),
            Map.entry("POST /v1/workspaces/{workspace}/chat/stream", Map.of("503", NO_PROVIDER)),
            Map.entry("POST /v1/workspaces/{workspace}/peer-card/refresh", Map.of("503", NO_PROVIDER)),
            Map.entry("POST /v1/workspaces/{workspace}/recall", Map.of("422", BAD_FILTER)),
            Map.entry("POST /v1/workspaces/{workspace}/sessions/{session}/messages/search", Map.of("422", BAD_FILTER)),
            // Every route that writes a configuration column, which is not only the two that replace one.
            // `getOrCreate` validates as well — HierarchyController says so where it explains the check
            // living in the repository, and the table had taken the narrower reading.
            Map.entry("PUT /v1/workspaces/{workspace}/configuration", Map.of("422", BAD_CONFIGURATION)),
            Map.entry("PUT /v1/workspaces/{workspace}/peers/{peer}/configuration", Map.of("422", BAD_CONFIGURATION)),
            Map.entry("POST /v1/workspaces/{workspace}", Map.of("422", BAD_CONFIGURATION)),
            Map.entry("POST /v1/workspaces/{workspace}/peers/{peer}", Map.of("422", BAD_CONFIGURATION)),
            Map.entry("POST /v1/workspaces/{workspace}/sessions/{session}", Map.of("422", BAD_CONFIGURATION)),
            Map.entry("POST /v1/workspaces/{workspace}/dreams", Map.of("409", DREAM_IN_FLIGHT)));

    /**
     * Every route the two tables above name, for the test that checks each one exists.
     *
     * <p>Both lookups fail silently by construction — {@code getOrDefault} and {@code contains} have no
     * other option — so a typo, or a path that moves later, degrades the document and breaks nothing.
     * The dreams entry is the one that stings: a single character out and its 409 quietly becomes the
     * generic conflict sentence, which is the bug this file was just corrected for. {@code
     * OpenApiSpecTest} compares bytes, and a document that is wrong in the same way every time matches
     * itself forever.
     */
    static Set<String> routesNamedByTables() {
        return Stream.concat(ROUTE_ERRORS.keySet().stream(), READS_ONLY.stream()).collect(Collectors.toSet());
    }

    static {
        // The principal is produced by WebConfiguration's resolver from the verified token; it is never
        // bound from the request. Left to its own devices springdoc reads it as a model attribute and
        // advertises its fields — scope, workspace, peer, session — as query parameters on nearly every
        // route, which is a published invitation to send an authorisation the server does not read.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(MemoryPrincipal.class);
    }

    @Bean
    public OpenAPI aimonMemoryOpenApi() {
        return new OpenAPI().info(new Info().title("aimon-memory")
                // The API's version, not the artifact's. Every path here is /v1, and that is what a
                // consumer pins to; stamping the build version instead would rewrite the committed
                // document on every release and bury real contract changes in the churn.
                .version("v1").description("""
                        Pair-scoped memory. Every fact belongs to a directed (observer, observed) \
                        pair: what bob remembers about alice is not what alice remembers about \
                        herself, and no route crosses that line.

                        Three tiers of read: `context` assembles a session's summary and recent \
                        messages within a token budget, `recall` ranks conclusions by six fused \
                        signals, and `chat` runs an agentic loop over both when the answer needs \
                        more than one lookup.""")
                .license(new License().name("Apache-2.0").url("https://www.apache.org/licenses/LICENSE-2.0")))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("""
                                        A token from `POST /v1/tokens`, in four nesting scopes: admin, \
                                        workspace, peer, session. Each operation below names the narrowest \
                                        one that may call it.""")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER))
                // Relative, and stated rather than inferred. Left to itself springdoc records the URL the
                // request that generated the document arrived on, which for the test that writes the file
                // is `http://localhost` — and a generated client would carry that as its default host.
                .servers(List.of(new Server().url("/").description("Relative to wherever this service is deployed.")));
    }

    /**
     * The failures every route can produce, and the shape they arrive in.
     *
     * <p>A description that documents only success is half a description: the first thing a client
     * writes after the happy path is the branch for everything else, and until now the only way to
     * learn that branch was to read {@link ApiExceptionHandler}. These five are the ones true of every
     * route. The status codes that belong to particular routes — a rejected filter, a dream already in
     * flight, an unconfigured provider — are annotated where they happen, and pick up their body here.
     */
    @Bean
    public OpenApiCustomizer standardErrors() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            ModelConverters.getInstance().readAll(Dtos.ErrorResponse.class)
                    .forEach(openApi.getComponents()::addSchemas);

            openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                ApiResponses responses = operation.getResponses();
                String route = method.name() + " " + path;
                // Route-specific first. A route with a better sentence for a shared code keeps it —
                // which is how the dreams route says "already running" where the rest say "conflicts".
                ROUTE_ERRORS.getOrDefault(route, Map.of()).forEach((code, description) -> responses
                        .computeIfAbsent(code, unused -> new ApiResponse().description(description)));
                ERRORS.forEach((code, description) -> responses.computeIfAbsent(code,
                        unused -> new ApiResponse().description(description)));
                if (writes(method, route)) {
                    responses.computeIfAbsent("409", unused -> new ApiResponse().description(CONFLICT));
                }
                // Every error body is the same two fields, so it is described once here rather than in
                // each of the places above that name a status code.
                responses.forEach((code, response) -> {
                    if (!code.startsWith("2") && response.getContent() == null) {
                        response.setContent(errorBody());
                    }
                });
                jsonSuccess(responses);
                streamsEvents(path, responses);
            }));

            // Resolved from the streaming handler's return type, and referenced by nothing once that
            // response is corrected. A schema left in the document is a schema someone will code against.
            if (openApi.getComponents().getSchemas() != null) {
                openApi.getComponents().getSchemas().remove("SseEmitter");
            }
        };
    }

    /**
     * The streaming route sends events, not the object its handler returns.
     *
     * <p>Its declared return type is {@code SseEmitter}, so the generated document offered a caller a
     * JSON schema for Spring's emitter class — a timeout field and nothing that ever crosses the
     * socket. What crosses it is {@code delta} events carrying text, then one {@code done}.
     */
    private static void streamsEvents(String path, ApiResponses responses) {
        if (!path.endsWith("/chat/stream")) {
            return;
        }
        // StringSchema, not `new Schema<>().type("string")`. This document is OpenAPI 3.1, where
        // swagger-core serialises the `types` set and ignores the 3.0 `type` field — so the setter that
        // reads correctly emitted `"schema": {}`, and the response this method exists to correct was
        // replaced by one that says nothing at all.
        responses.addApiResponse("200", new ApiResponse()
                .description("A stream of `delta` events carrying answer text, then a single `done` event.")
                .content(new Content().addMediaType("text/event-stream", new MediaType().schema(new StringSchema()))));
    }

    /**
     * The success bodies are JSON, and the document should say so.
     *
     * <p>None of the handlers declare {@code produces}, so springdoc records the media type it can
     * prove — {@code &#42;/&#42;}. True, and useless to a generator: some emit a client that will not parse
     * the response it is handed. Every one of these is a Jackson-serialised record.
     */
    private static void jsonSuccess(ApiResponses responses) {
        ApiResponse ok = responses.get("200");
        if (ok == null || ok.getContent() == null) {
            return;
        }
        MediaType any = ok.getContent().remove("*/*");
        if (any != null) {
            ok.getContent().addMediaType("application/json", any);
        }
    }

    /**
     * Whether a route can reach the database as a writer, and so can answer 409.
     *
     * <p>{@link ApiExceptionHandler} turns a {@code DataIntegrityViolationException} into a conflict,
     * and the schema's foreign keys are dense enough that naming a workspace, peer or session that does
     * not exist reaches one. That was documented on the dreams route alone, whose 409 means something
     * else entirely — so a client reading only the document concluded "a dream is already running" from
     * a typo in a workspace name.
     *
     * <p>Method plus an exception list, rather than an inventory of the thirteen routes that write: a
     * read-only POST added later collects a 409 it will never send, which is the harmless direction to
     * be wrong in.
     */
    private static boolean writes(PathItem.HttpMethod method, String route) {
        return method != PathItem.HttpMethod.GET && !READS_ONLY.contains(route);
    }

    private static Content errorBody() {
        return new Content().addMediaType("application/json",
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/ErrorResponse")));
    }

    /**
     * Stamps each operation with the scope its route requires, read from the route table itself.
     *
     * <p>Written here rather than into thirty-three annotations because the answer already exists in
     * one place, and a second copy of an authorisation rule is a copy that will eventually disagree
     * with the one being enforced. A route missing from the table gets nothing said about it — that is
     * already a build failure in {@code RoutePolicyCoverageTest}, and repeating the complaint here
     * would only make the same mistake fail twice.
     */
    @Bean
    public OpenApiCustomizer routeScopes(RoutePolicy policy) {
        return openApi -> openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, op) -> {
            RoutePolicy.Rule rule = policy.ruleFor(method.name(), path);
            if (rule == null) {
                return;
            }
            StringBuilder note = new StringBuilder("**Requires a `").append(rule.minimumScope().wire())
                    .append("` token or wider.**");
            if (rule.memberRead()) {
                note.append(" A session-scoped token may also call this one, but only if it carries")
                        .append(" `allowMemberRead`.");
            }
            op.setDescription(op.getDescription() == null ? note.toString() : op.getDescription() + "\n\n" + note);
        }));
    }
}
