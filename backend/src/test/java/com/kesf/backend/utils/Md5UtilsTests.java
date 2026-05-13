package com.kesf.backend.utils;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Md5UtilsTests {

    /**
     * 测试 MD5 计算方法对已知字符串能够生成标准的小写十六进制字符串。
     * 该测试主要验证基础的哈希计算逻辑是否正确，例如空字符串、简单字母等是否与业界标准 MD5 值相符。
     */
    @Test
    void md5HexReturnsStandardLowercaseHexForKnownValues() throws Exception {
        // 验证：空字符串的 MD5 结果必须与标准值完全一致，且为小写十六进制
        assertKnownMd5("", "d41d8cd98f00b204e9800998ecf8427e");
        // 验证："abc" 字符串的 MD5 结果
        assertKnownMd5("abc", "900150983cd24fb0d6963f7d28e17f72");
        // 验证："hello" 字符串的 MD5 结果
        assertKnownMd5("hello", "5d41402abc4b2a76b9719d911017c592");
    }

    /**
     * 测试确保 MD5 计算是基于文件的原始字节流而非文件的元数据（如文件名等）。
     * 此测试旨在验证相同的字节流输入必定会产生相同的 MD5 摘要，防止任何外部因素干扰计算结果。
     */
    @Test
    void md5HexUsesRawBytesInsteadOfFileNameOrMetadata() throws Exception {
        // 构造一段模拟 PDF 文件格式的字节数组（包含 PDF 的文件头和文件尾标识），
        // 确保我们的算法提取的是“字节内容”。
        byte[] pdfLikeBytes = "%PDF-1.7\nScholarEase\n%%EOF".getBytes(StandardCharsets.UTF_8);

        // 使用两个相互独立的字节数组输入流分别读取同一份模拟的字节数据。
        // 这模拟了前后两次针对相同文件内容的读取过程。
        String firstDigest = Md5Utils.md5Hex(new ByteArrayInputStream(pdfLikeBytes));
        String secondDigest = Md5Utils.md5Hex(new ByteArrayInputStream(pdfLikeBytes));

        // 打印出测试的信息，方便控制台观察对比
        printMd5("pdfLikeBytes", firstDigest, firstDigest);
        // 断言：两次分别基于相同原始字节计算出的摘要结果必须完全一致
        assertThat(firstDigest).isEqualTo(secondDigest);
        // 断言：MD5 的小写十六进制字符串长度必须恰好为 32 个字符
        assertThat(firstDigest).hasSize(32);
    }

    /**
     * 读取本地的物理 PDF 文件，计算其 MD5 值并将其输出到控制台。
     * 主要用于前后端联调测试：前端可以通过打印出来的计算结果，验证其在浏览器中计算的 MD5 是否和后端一致。
     */
    @Test
    void printLocalPdfMd5ForFrontendComparison() throws Exception {
        // 解析待测试的本地 PDF 文件路径：
        // 1. 可以通过在运行测试时附加 JVM 参数如：-Dmd5.file="E:\path\to\paper.pdf" 来指定任意的本地 PDF 文件。
        // 2. 如果没有指定上述参数，则默认回退去读取项目目录外层 mineru 文件夹下已经存在的示例 PDF 文件。
        Path pdfPath = resolveLocalPdfPath();
        
        // 断言：确保待测试的 PDF 文件真实存在。如果不存在则报错提示如何指定路径。
        assertThat(Files.exists(pdfPath))
                .as("PDF 文件不存在，可通过 -Dmd5.file 指定本地 PDF 路径")
                .isTrue();

        String digest;
        // 使用 try-with-resources 语法开启文件输入流，读取完之后自动关闭，防止文件句柄泄漏
        try (InputStream inputStream = Files.newInputStream(pdfPath)) {
            // 调用核心工具方法，基于文件的输入流来分块计算它的 MD5 十六进制摘要
            digest = Md5Utils.md5Hex(inputStream);
        }

        // 格式化输出该 PDF 的绝对路径、字节大小以及最终计算出的 MD5 值，供前端开发人员比对
        System.out.printf(
                "LOCAL_PDF_MD5 path=%s sizeBytes=%d backendMd5=%s%n",
                pdfPath.toAbsolutePath(),
                Files.size(pdfPath),
                digest
        );
        // 断言验证：针对真实文件算出的 MD5 十六进制字符串长度必须为 32
        assertThat(digest).hasSize(32);
    }

    /**
     * 辅助方法：验证指定字符串生成的 MD5 摘要与期望的标准摘要是否一致。
     *
     * @param value          待哈希的明文字符串
     * @param expectedDigest 预期的 MD5 标准小写十六进制摘要
     */
    private static void assertKnownMd5(String value, String expectedDigest) throws Exception {
        // 实际计算出的 MD5 摘要结果
        String actualDigest = md5Hex(value);

        // 控制台打印出待验证的值、实际摘要及预期摘要
        printMd5(value, actualDigest, expectedDigest);
        // 核心断言：两者相等
        assertThat(actualDigest).isEqualTo(expectedDigest);
    }

    /**
     * 辅助方法：格式化并在控制台上打印 MD5 比较的相关信息，便于调试和日志记录。
     *
     * @param inputLabel     输入信息的标签（例如字符串原文或变量名）
     * @param actualDigest   后端代码实际计算得出的 MD5
     * @param expectedDigest 我们预期的 MD5 值
     */
    private static void printMd5(String inputLabel, String actualDigest, String expectedDigest) {
        System.out.printf("MD5_COMPARE input=%s backendMd5=%s expectedMd5=%s%n", inputLabel, actualDigest, expectedDigest);
    }

    /**
     * 辅助方法：专门针对字符串的 MD5 摘要计算方法。
     * 
     * @param value 输入字符串
     * @return 该字符串在 UTF-8 编码下的 MD5 摘要（小写十六进制形式）
     */
    private static String md5Hex(String value) throws Exception {
        // 先将给定的输入字符串按照 UTF-8 字符集提取为字节数组，
        // 然后包装成 ByteArrayInputStream，再传递给需要被测试的方法 Md5Utils.md5Hex 进行计算。
        return Md5Utils.md5Hex(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 解析本地 PDF 测试文件的具体路径。
     * 通过读取系统属性 'md5.file' 进行覆盖配置，如果用户未指定，则默认提供一个存在于项目测试数据目录中的预设文件。
     *
     * @return 解析得出的本地 PDF 文件的 Path 对象
     */
    private static Path resolveLocalPdfPath() {
        // 尝试从系统的环境变量中获取属性值
        String configuredPath = System.getProperty("md5.file");

        // 如果用户通过 -Dmd5.file 配置了不为空的路径，则优先使用该指定路径
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath);
        }

        // 未配置的情况下，返回项目默认测试文件的相对路径
        return Path.of("..", "mineru", "9bbfd84b-06a0-475b-a87e-68cf7148f26a_origin.pdf");
    }
}
