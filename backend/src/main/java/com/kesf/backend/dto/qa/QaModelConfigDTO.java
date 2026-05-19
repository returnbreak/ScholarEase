package com.kesf.backend.dto.qa;

import lombok.Data;

@Data
public class QaModelConfigDTO {

    private String baseUrl;

    private String apiKey;

    private String modelName;

    private Double temperature;

    private Double topP;

    private Integer maxTokens;

    private Integer timeoutSeconds;
}
