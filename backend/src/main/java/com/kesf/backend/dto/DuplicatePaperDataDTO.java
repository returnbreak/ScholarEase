package com.kesf.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DuplicatePaperDataDTO {

    /**
     * 重复命中的原因。
     *
     * 当前上传阶段只做 PDF 内容 MD5 查重，因此固定使用 PDF_MD5_MATCHED；
     * 后续如果增加 DOI、标题相似度等规则，可以在这里扩展新的枚举字符串。
     */
    private String duplicateReason;

    /**
     * 已存在的文献摘要。
     *
     * 前端收到 DUPLICATE_PAPER 后可以展示 existingPaper.title/fileName，
     * 帮助用户确认是哪一篇文献已经在库中。
     */
    private PaperSummaryDTO existingPaper;
}
