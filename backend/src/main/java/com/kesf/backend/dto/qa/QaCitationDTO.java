package com.kesf.backend.dto.qa;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QaCitationDTO {

    private String citationId;

    private String paperMd5;

    private Integer chunkIndex;

    private String title;

    private String fileName;

    private String sectionPath;

    private Integer pageStart;

    private Integer pageEnd;

    private String rawTextPreview;

    private Double score;
}
