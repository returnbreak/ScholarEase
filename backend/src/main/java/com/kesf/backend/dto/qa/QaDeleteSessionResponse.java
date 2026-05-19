package com.kesf.backend.dto.qa;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class QaDeleteSessionResponse {

    private String sessionId;

    private boolean deleted;
}
