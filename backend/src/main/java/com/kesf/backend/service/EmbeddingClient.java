package com.kesf.backend.service;

import java.util.List;

/**
 * 文本向量化（Embedding）客户端接口。
 * <p>
 * 定义将文本列表转换为浮点向量列表的契约。不同的实现可以对接不同的 Embedding 服务
 * （如阿里云 DashScope、OpenAI、本地模型等），调用方无需关心底层 API 细节。
 * </p>
 */
public interface EmbeddingClient {

    /**
     * 将文本列表向量化。
     *
     * @param texts 待向量化的文本列表，顺序需与返回的向量列表一一对应
     * @return 与输入文本等长的浮点向量列表，每个向量为 float[]
     */
    List<float[]> embed(List<String> texts);
}
