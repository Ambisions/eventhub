package com.eventhub.application.flash;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionIdGeneratorTest {

    @Test
    void generatesDistinctCanonicalLowercaseUuidValues() {
        AdmissionIdGenerator generator = new AdmissionIdGenerator();

        String first = generator.generate();
        String second = generator.generate();

        assertThat(first).hasSize(36).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(UUID.fromString(first).toString()).isEqualTo(first);
        assertThat(second).isNotEqualTo(first);
    }
}
