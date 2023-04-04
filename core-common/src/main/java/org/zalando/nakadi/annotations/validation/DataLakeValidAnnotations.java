package org.zalando.nakadi.annotations.validation;

import javax.validation.Constraint;
import javax.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({
        ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE,
        ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {DataLakeAnnotationValidator.class})
public @interface DataLakeValidAnnotations {
    String message() default "{org.zalando.nakadi.annotations.validation.DataLakeValidAnnotations.message}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
