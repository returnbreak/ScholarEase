package com.kesf.backend.dto.qa;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QaWebSocketResponse {

    private String type;

    private String content;

    private Object data;

    private String error;

    public static QaWebSocketResponse metadata(Object data) {
        return new QaWebSocketResponse("metadata", null, data, null);
    }

    public static QaWebSocketResponse token(String token) {
        return new QaWebSocketResponse("token", token, null, null);
    }

    public static QaWebSocketResponse completion(Object data) {
        return new QaWebSocketResponse("completion", null, data, null);
    }

    public static QaWebSocketResponse error(String message) {
        return new QaWebSocketResponse("error", null, null, message);
    }

    public static QaWebSocketResponse connection(Object data) {
        return new QaWebSocketResponse("connection", null, data, null);
    }
}
