package com.kesf.backend.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class PaperDetailDTO extends PaperSummaryDTO {

    /**
     * papers.language，论文语言。
     */
    private String language;

}
