package com.kesf.backend.service.qa;

import com.kesf.backend.config.QaProperties;
import com.kesf.backend.dto.qa.QaModelConfigDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QaStreamingChatModelFactoryTests {

    @Test
    void resolveTimeoutSecondsDoesNotAllowRequestToLowerServerDefault() {
        QaProperties properties = new QaProperties();
        properties.getChat().setTimeoutSeconds(600);
        QaStreamingChatModelFactory factory = new QaStreamingChatModelFactory(properties);

        QaModelConfigDTO requestConfig = new QaModelConfigDTO();
        requestConfig.setTimeoutSeconds(60);

        assertThat(factory.resolveTimeoutSeconds(requestConfig)).isEqualTo(600);
    }

    @Test
    void resolveMaxTokensDoesNotAllowRequestToLowerServerDefault() {
        QaProperties properties = new QaProperties();
        properties.getChat().getModelMaxTokens().put("deepseek-v4-pro", 16384);
        QaStreamingChatModelFactory factory = new QaStreamingChatModelFactory(properties);

        QaModelConfigDTO requestConfig = new QaModelConfigDTO();
        requestConfig.setMaxTokens(8192);

        assertThat(factory.resolveMaxTokens(requestConfig)).isEqualTo(16384);
    }
}
