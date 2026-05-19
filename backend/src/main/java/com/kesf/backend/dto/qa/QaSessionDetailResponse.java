package com.kesf.backend.dto.qa;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QaSessionDetailResponse {

    private String sessionId;

    private String title;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    private List<QaChatMessageDTO> messages;
}
