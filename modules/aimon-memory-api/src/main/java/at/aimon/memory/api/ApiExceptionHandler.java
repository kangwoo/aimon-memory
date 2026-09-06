package at.aimon.memory.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import at.aimon.memory.api.dto.Dtos;
import at.aimon.memory.api.security.ForbiddenException;
import at.aimon.memory.api.security.UnauthorizedException;
import at.aimon.memory.core.ConflictException;
import at.aimon.memory.core.MemoryException;
import at.aimon.memory.core.NotFoundException;
import at.aimon.memory.core.config.ConfigurationException;
import at.aimon.memory.core.filter.FilterException;

/**
 * Maps failures to status codes.
 *
 * <p>A rejected filter is 422, not 400 and not an empty result set. The request parsed fine; the
 * predicate is the problem. Returning 200 with no rows — the tempting alternative — is how a caller
 * concludes there is no data when in fact their query was thrown away.
 */
/*
 * Every handler below reads `publicMessage()`, never `getMessage()`. For all but one exception the
 * two are the same string, because a message is written for whoever made the request. The exception
 * is `FixtureMissException`, whose message is a diagnostic for a developer reading a failed
 * `./gradlew test` and inlines the assembled prompt and an absolute server path — see
 * `MemoryException.publicMessage`, which is where the split lives, because this class is not the only
 * place a message is copied towards a caller. The ERROR line in `memory` still logs the whole thing.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(FilterException.class)
    public ResponseEntity<Dtos.ErrorResponse> filter(FilterException e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, e.code(), e.publicMessage());
    }

    /**
     * A configuration the system will not store.
     *
     * <p>422 for the same reason a rejected filter is: the request parsed, the values are the
     * problem, and accepting them would mean answering 200 to a change that has no effect.
     */
    @ExceptionHandler(ConfigurationException.class)
    public ResponseEntity<Dtos.ErrorResponse> configuration(ConfigurationException e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, e.code(), e.publicMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Dtos.ErrorResponse> unauthorized(UnauthorizedException e) {
        return body(HttpStatus.UNAUTHORIZED, e.code(), e.publicMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Dtos.ErrorResponse> forbidden(ForbiddenException e) {
        return body(HttpStatus.FORBIDDEN, e.code(), e.publicMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Dtos.ErrorResponse> notFound(NotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.code(), e.publicMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Dtos.ErrorResponse> conflict(ConflictException e) {
        return body(HttpStatus.CONFLICT, e.code(), e.publicMessage());
    }

    /**
     * Spring's own request-handling failures.
     *
     * <p>Without these they fall through to the catch-all and every one of them — an unknown URL, a
     * wrong verb, a truncated body, a non-numeric page number — is reported as a 500 and logged with
     * a stack trace at ERROR. That makes client mistakes indistinguishable from server faults in both
     * the response and the error-rate metric, which is where an outage is supposed to show up.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Dtos.ErrorResponse> notFoundRoute(Exception e) {
        return body(HttpStatus.NOT_FOUND, "not_found", "no such endpoint");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Dtos.ErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Dtos.ErrorResponse> unreadableBody(HttpMessageNotReadableException e) {
        // The parser's own message can quote the offending bytes back at the caller; the position is
        // useful, the payload echo is not.
        return body(HttpStatus.BAD_REQUEST, "bad_request", "request body is not valid JSON");
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<Dtos.ErrorResponse> badParameters(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "bad_request", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Dtos.ErrorResponse> validation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage()).findFirst()
                .orElse("request body is invalid");
        return body(HttpStatus.BAD_REQUEST, "bad_request", detail);
    }

    /**
     * A constraint the database refused.
     *
     * <p>These were reaching the catch-all and reporting 500, which told a caller their request had
     * broken the server when in fact it had named something that does not exist or already does.
     *
     * <p>The driver's message is not passed through: it quotes the constraint, the table and often
     * the offending value, which is more of the schema than an API should hand back.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Dtos.ErrorResponse> constraint(DataIntegrityViolationException e) {
        log.warn("constraint violation: {}", e.getMostSpecificCause().getMessage());
        return body(HttpStatus.CONFLICT, "constraint_violation",
                "the request conflicts with existing data, or names something that does not exist");
    }

    @ExceptionHandler(MemoryException.class)
    public ResponseEntity<Dtos.ErrorResponse> memory(MemoryException e) {
        HttpStatus status = switch (e.code()) {
            case "bad_key", "bad_level", "bad_actor", "bad_event", "bad_scope", "bad_reasoning_level",
                    "bad_response_format", "batch_too_large", "bad_sync_state", "bad_draft", "bad_lifetime",
                    "bad_expiry" ->
                HttpStatus.BAD_REQUEST;
            case "llm_not_configured", "missing_config" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "fixture_miss" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        if (status.is5xxServerError()) {
            log.error("request failed [{}]: {}", e.code(), e.getMessage(), e);
        }
        return body(status, e.code(), e.publicMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Dtos.ErrorResponse> unexpected(Exception e) {
        log.error("unhandled exception", e);
        // Deliberately vague: an internal message can name a table, a column or a file path.
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "the request could not be completed");
    }

    private static ResponseEntity<Dtos.ErrorResponse> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new Dtos.ErrorResponse(code, message));
    }
}
