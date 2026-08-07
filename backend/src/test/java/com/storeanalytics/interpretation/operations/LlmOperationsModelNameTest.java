package com.storeanalytics.interpretation.operations;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmOperationsModelNameTest {

    @Test
    void exposesOnlyTheModelSegmentAndNotTheCustomerFolder() {
        assertThat(LlmOperationsQuery.modelName(
                "gpt://customer-folder/yandexgpt-5.1"
        )).isEqualTo("yandexgpt-5.1");
        assertThat(LlmOperationsQuery.modelName("")).isNull();
    }
}
