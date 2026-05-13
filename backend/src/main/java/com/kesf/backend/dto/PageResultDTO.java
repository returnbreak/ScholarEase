package com.kesf.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResultDTO<T> {

    private List<T> items;

    private Integer page;

    private Integer pageSize;

    private Long total;

    private Boolean hasNext;
}
