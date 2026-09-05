package at.aimon.memory.api.dto;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * A string that holds at least one character {@link String#isBlank()} does not call whitespace.
 *
 * <p>This exists because {@code @NotBlank} and the filter in {@code EntityPipeline.isUsable} disagree
 * about what blank means, and the gap between them was visible from outside. {@code @NotBlank} is
 * specified in terms of {@link String#trim()}, which strips only characters at or below {@code U+0020};
 * the filter uses {@link String#isBlank()}, which is {@link Character#isWhitespace(char)}. So a name of
 * {@code U+2000} — EN QUAD, whitespace to one and not to the other — was accepted with a 200 and then
 * silently dropped before it could become a node. Measured, before this annotation existed:
 *
 * <pre>
 * entities: [U+2000]  ->  POST 200, and no entity; provenance for it answers 404
 * </pre>
 *
 * <p>That is the one thing {@code CreateConclusion.entities} refuses in order to avoid: an entity going
 * missing with nothing in the response to say so. A rule the caller cannot see the edge of is not much
 * better than no rule, so the two now answer the same question with the same method.
 *
 * <p>Paired with {@code @NotBlank} rather than replacing it. {@code @NotBlank} rejects {@code null}, and
 * it is what springdoc reads to publish {@code minLength: 1} — the same mapping every other string field
 * in {@code Requests} relies on. This one carries no schema of its own on purpose: the exact predicate
 * is Java's, and writing it out as an OpenAPI {@code pattern} would publish an ECMA-262 regex that does
 * not mean the same thing to the generators that would consume it.
 *
 * <p>Names that are not whitespace by either definition are left alone, including ones that identify
 * nothing — {@code U+00A0} (NBSP) and {@code "."} still become nodes. That is a different question
 * from this one and no damage has been measured for it.
 */
@Documented
@Target(ElementType.TYPE_USE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = UsableName.Validator.class)
public @interface UsableName {

    String message() default "must contain at least one character that is not whitespace";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Null passes, as constraints other than {@code @NotNull} are required to. */
    class Validator implements ConstraintValidator<UsableName, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || !value.isBlank();
        }
    }
}
