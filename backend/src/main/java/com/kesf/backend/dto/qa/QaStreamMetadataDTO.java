package com.kesf.backend.dto.qa;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class QaStreamMetadataDTO {

    private String sessionId;

    private String messageId;

    private String qaTraceId;

    private String standaloneQuestionZh;

    private String queryEn;

    @Builder.Default
    private List<QaCitationDTO> citations = new ArrayList<>();
}
