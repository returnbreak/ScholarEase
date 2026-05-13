package com.kesf.backend.dto;

import lombok.Data;

@Data
public class DocumentQueryDTO {

    private String keyword;

    private Integer year;

    private String venue;

    private Integer page = 1;

    private Integer pageSize = 20;
}
