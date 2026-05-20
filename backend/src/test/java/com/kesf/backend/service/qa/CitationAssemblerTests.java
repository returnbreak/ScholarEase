package com.kesf.backend.service.qa;

import com.kesf.backend.service.PaperRetrievalSearchService;
import com.kesf.backend.service.impl.PaperVectorDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationAssemblerTests {

    private final CitationAssembler citationAssembler = new CitationAssembler();

    @Test
    void toEvidencePromptUsesNumericReferenceWithLiteratureNameAndPages() {
        PaperVectorDocument document = new PaperVectorDocument();
        document.setPaperMd5("md5-001");
        document.setChunkIndex(3);
        document.setTitle("Spatio-temporal multi-level attention crop mapping");
        document.setFileName("12_2023_crop_mapping.pdf");
        document.setSectionPath("Methods / Attention module");
        document.setPageStart(4);
        document.setPageEnd(5);
        document.setRawText("The proposed method uses multi-level attention on SAR time series.");

        PaperRetrievalSearchService.PaperRetrievalHit hit = new PaperRetrievalSearchService.PaperRetrievalHit(
                document,
                0.82d,
                1,
                PaperRetrievalSearchService.RetrievalSource.HYBRID,
                1,
                2,
                0.05d
        );

        var citations = citationAssembler.toCitations(List.of(hit));
        String evidence = citationAssembler.toEvidencePrompt(citations);

        assertThat(citations).hasSize(1);
        assertThat(citations.get(0).getCitationId()).isEqualTo("1");
        assertThat(citations.get(0).getFileName()).isEqualTo("12_2023_crop_mapping.pdf");
        assertThat(evidence).contains("[1]");
        assertThat(evidence).contains("Reference: [1] Spatio-temporal multi-level attention crop mapping · 第 4-5 页");
        assertThat(evidence).contains("File: 12_2023_crop_mapping.pdf");
        assertThat(evidence).contains("Title: Spatio-temporal multi-level attention crop mapping");
    }

    @Test
    void toEvidencePromptListsReferencesOnSeparateLines() {
        PaperVectorDocument first = document("paper-1.pdf", "First paper", 1);
        PaperVectorDocument second = document("paper-2.pdf", "Second paper", 2);

        var citations = citationAssembler.toCitations(List.of(hit(first), hit(second)));
        String evidence = citationAssembler.toEvidencePrompt(citations);

        assertThat(evidence).contains("""
                检索证据引用候选（这是候选编号，不是最终回答编号；最终回答必须按实际引用顺序重新连续编号）：
                [1] First paper · 第 1 页
                [2] Second paper · 第 2 页
                """);
    }

    @Test
    void toEvidencePromptFallsBackToFileNameWhenTitleIsMissing() {
        PaperVectorDocument document = document("paper-without-title.pdf", "", 7);

        var citations = citationAssembler.toCitations(List.of(hit(document)));
        String evidence = citationAssembler.toEvidencePrompt(citations);

        assertThat(evidence).contains("[1] paper-without-title.pdf · 第 7 页");
    }

    private static PaperVectorDocument document(String fileName, String title, int page) {
        PaperVectorDocument document = new PaperVectorDocument();
        document.setPaperMd5(fileName + "-md5");
        document.setChunkIndex(page);
        document.setTitle(title);
        document.setFileName(fileName);
        document.setPageStart(page);
        document.setRawText("raw text");
        return document;
    }

    private static PaperRetrievalSearchService.PaperRetrievalHit hit(PaperVectorDocument document) {
        return new PaperRetrievalSearchService.PaperRetrievalHit(
                document,
                0.8d,
                1,
                PaperRetrievalSearchService.RetrievalSource.HYBRID,
                1,
                1,
                0.05d
        );
    }
}
