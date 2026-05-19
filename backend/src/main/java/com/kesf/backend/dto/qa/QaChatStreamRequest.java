package com.kesf.backend.dto.qa;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class QaChatStreamRequest {

    private String sessionId;

    private String sessionTitle;

    private String message;

    private QaModelConfigDTO modelConfig;

    private List<String> paperMd5List = new ArrayList<>();
}
