package org.zalando.nakadi.annotations.validation;

import org.junit.Before;
import org.junit.Test;

import javax.validation.ConstraintViolation;
import javax.validation.Valid;
import javax.validation.Validation;
import javax.validation.Validator;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DataLakeAnnotationsTest {
    public static class TestClass {
        @Valid
        @DataLakeValidAnnotations
        private Map<
                @Valid @AnnotationKey String,
                @Valid @AnnotationValue String> annotations;

        public TestClass(final Map<String, String> annotations) {
            this.annotations = annotations;
        }
    }

    private Validator validator;

    @Before
    public void prepareValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    public void whenRetentionPeriodThenRetentionReasonRequired() {
        final var annotations = Map.of(
                DataLakeAnnotationValidator.RetentionPeriodAnnotation, "1 day"
        );
        final Set<ConstraintViolation<TestClass>> result = validator.validate(new TestClass(annotations));
        assertFalse("Missing retention reason is treated valid", result.isEmpty());
    }

    @Test
    public void whenRetentionPeriodFormatIsWrongThenFail() {
        final var annotations = Map.of(
                DataLakeAnnotationValidator.RetentionPeriodAnnotation, "1 airplane",
                DataLakeAnnotationValidator.RetentionReasonAnnotation, "I need my data"
        );
        final Set<ConstraintViolation<TestClass>> result = validator.validate(new TestClass(annotations));
        assertFalse("Missing retention reason is treated valid", result.isEmpty());
    }

    @Test
    public void whenRetentionPeriodAndReasonThenOk() {
        final var annotations = Map.of(
                DataLakeAnnotationValidator.RetentionPeriodAnnotation, "1 day",
                DataLakeAnnotationValidator.RetentionReasonAnnotation, "I need my data"
        );
        final Set<ConstraintViolation<TestClass>> result = validator.validate(new TestClass(annotations));
        assertTrue("Retention period and reason exist correctly", result.isEmpty());
    }
}
