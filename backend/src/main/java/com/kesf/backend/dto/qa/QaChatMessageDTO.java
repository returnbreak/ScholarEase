package com.kesf.backend.dto.qa;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QaChatMessageDTO {

    private String id;

    private String role;

    private String content;

    private List<QaCitationDTO> citations;
}
