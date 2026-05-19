package com.kesf.backend.dto.qa;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@AllArgsConstructor
public class QaSessionResponse {

    private String sessionId;

    private OffsetDateTime createdAt;
}
