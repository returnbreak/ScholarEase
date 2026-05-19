package com.kesf.backend.controller;

import com.kesf.backend.dto.ApiResponseDTO;
import com.kesf.backend.dto.qa.QaSessionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/qa")
public class QaController {

    @PostMapping("/sessions")
    public ApiResponseDTO<QaSessionResponse> createSession() {
        return ApiResponseDTO.success(new QaSessionResponse(
                UUID.randomUUID().toString(),
                OffsetDateTime.now()
        ));
    }
}
