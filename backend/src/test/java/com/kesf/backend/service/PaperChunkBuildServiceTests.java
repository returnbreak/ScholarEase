package com.kesf.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kesf.backend.kafka.PaperVectorIndexTask;
import com.kesf.backend.service.impl.MinerUBlockTextExtractor;
import com.kesf.backend.service.impl.PaperChunk;
import com.kesf.backend.service.impl.PaperChunkBuildService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PaperChunkBuildService} 的单元测试。
 * <p>
 * 验证从 MinerU content_list_v2.json 中正确提取各类内容块，
 * 包括段落中的行内公式、图片/表格/公式的元数据提取，以及上下文增强文本的生成。
 * </p>
 */
class PaperChunkBuildServiceTests {

    private final PaperChunkBuildService service = new PaperChunkBuildService(
            new ObjectMapper(),
            new MinerUBlockTextExtractor()
    );

    /**
     * 验证完整的内容块提取流程，覆盖 5 种块类型：
     * <ul>
     *   <li>title → 标题类型 + 上下文</li>
     *   <li>paragraph → 段落类型 + 行内公式 $...$ 包裹</li>
     *   <li>image → figure 类型 + 标题/路径/脚注</li>
     *   <li>table → table 类型 + 标题/HTML</li>
     *   <li>equation_interline → equation 类型 + LaTeX/图片路径</li>
     * </ul>
     * 同时验证 page_header 类型被正确跳过（5个有效块，不包含页眉）。
     */
    @Test
    void buildChunksExtractsSearchableTextAndPaperContextFromMinerUBlocks() {
        PaperVectorIndexTask task = task();
        byte[] contentList = """
                [
                  [
                    {"type":"page_header","content":{"paragraph_content":[{"type":"text","content":"Journal header"}]}},
                    {"type":"title","content":{"level":1,"title_content":[{"type":"text","content":"Abstract"}]},"bbox":[1,2,3,4]},
                    {"type":"paragraph","content":{"paragraph_content":[
                      {"type":"text","content":"We compare "},
                      {"type":"equation_inline","content":"Q_%"},
                      {"type":"text","content":" across catchments."}
                    ]},"bbox":[5,6,7,8]},
                    {"type":"image","content":{
                      "image_source":{"path":"images/fig1.jpg"},
                      "image_caption":[{"type":"text","content":"Fig. 1. Flow duration curves."}],
                      "image_footnote":[{"type":"text","content":"Daily flow, 1989-2000."}]
                    },"bbox":[9,10,11,12]}
                  ],
                  [
                    {"type":"table","content":{
                      "table_caption":[{"type":"text","content":"Table 1. Catchment summary."}],
                      "html":"<table><tr><td>Pine Creek</td></tr></table>"
                    },"bbox":[13,14,15,16]},
                    {"type":"equation_interline","content":{
                      "math_content":"Q = f(P) + g(T)",
                      "image_source":{"path":"images/eq1.jpg"}
                    },"bbox":[17,18,19,20]}
                  ]
                ]
                """.getBytes(StandardCharsets.UTF_8);

        List<PaperChunk> chunks = service.buildChunks(task, contentList);

        // 共 5 个有效块（page_header 被跳过）
        assertThat(chunks).hasSize(5);
        assertThat(chunks).extracting(PaperChunk::chunkType)
                .containsExactly("title", "paragraph", "figure", "table", "equation");

        // 段落：行内公式被 $...$ 包裹
        assertThat(chunks.get(1).rawText()).contains("We compare $Q_%$ across catchments.");
        // 上下文增强文本包含论文元数据
        assertThat(chunks.get(1).contextText())
                .contains("Paper: The response of flow duration curves to afforestation")
                .contains("Authors: Patrick N.J. Lane, Alice E. Best")
                .contains("Section: Abstract")
                .contains("Page: 1")
                .contains("Type: paragraph");
        assertThat(chunks.get(1).bbox()).containsExactly(5, 6, 7, 8);

        // 图片：标题 + 路径 + 脚注
        assertThat(chunks.get(2).rawText())
                .contains("Figure: Fig. 1. Flow duration curves.")
                .contains("Image Path: images/fig1.jpg")
                .contains("Footnote: Daily flow, 1989-2000.");

        // 表格：标题 + HTML
        assertThat(chunks.get(3).rawText())
                .contains("Table: Table 1. Catchment summary.")
                .contains("<table><tr><td>Pine Creek</td></tr></table>");

        // 行间公式：LaTeX + 图片路径
        assertThat(chunks.get(4).rawText()).contains("Equation: Q = f(P) + g(T)");
    }

    /** 构造测试用的 PaperVectorIndexTask */
    private static PaperVectorIndexTask task() {
        PaperVectorIndexTask task = new PaperVectorIndexTask();
        task.setTraceId("trace-001");
        task.setPaperMd5("md5-001");
        task.setFileName("paper.pdf");
        task.setContentListObjectKey("uploads/trace-001/mineru/content_list_v2.json");
        task.setTitle("The response of flow duration curves to afforestation");
        task.setAuthors(List.of("Patrick N.J. Lane", "Alice E. Best"));
        task.setKeywords(List.of("hydrology"));
        task.setLanguage("en");
        task.setYear(2005);
        task.setVenue("Journal of Hydrology");
        task.setDoi("10.1016/j.jhydrol.2005.01.006");
        task.setModelVersion("BAAI/bge-m3");
        return task;
    }
}
