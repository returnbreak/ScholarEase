package com.kesf.backend.dto.qa;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class QaChatStreamRequest {

    private String sessionId;

    private String message;

    private List<String> paperMd5List = new ArrayList<>();
}
