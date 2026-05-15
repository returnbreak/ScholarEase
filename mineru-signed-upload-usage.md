# MinerU Signed Upload 使用步骤

本文记录 ScholarEase 当前验证通过的 MinerU 本地文件上传解析流程，供后续接入后端 `MinerUParseService` 使用。

## 1. 当前推荐方案

使用 MinerU 精准解析 API 的 signed upload 流程：

```text
后端接收前端 MultipartFile
  -> 后端校验 PDF / MD5 / 文件大小
  -> 调 MinerU 申请 signed upload URL
  -> 后端 PUT 上传 PDF 文件流到 MinerU signed URL
  -> MinerU 自动开始解析
  -> 后端轮询 batch 解析结果
  -> 成功后获取 full_zip_url
  -> 下载解析 ZIP，提取 Markdown / JSON
  -> 写 Zotero / MinIO / papers
```

这个方案不需要先把文件上传到 MinIO 临时区，也不需要暴露本地文件公网链接。

## 2. application.yml 配置

建议配置：

```yaml
mineru:
  enabled: true
  baseUrl: https://mineru.net
  token: ${MINERU_TOKEN:}
  modelVersion: vlm
  sourceFile:
    mode: mineru-signed-upload
  api:
    fileUrlsBatchPath: /api/v4/file-urls/batch
    batchResultsPath: /api/v4/extract-results/batch/{batchId}
  polling:
    interval: 5s
    maxAttempts: 60
```

注意：不要把 MinerU token 明文写进 Git。应通过环境变量提供：

```powershell
$env:MINERU_TOKEN = "your-token"
```

## 3. 调用步骤

### 3.1 申请上传 URL

请求：

```http
POST https://mineru.net/api/v4/file-urls/batch
Content-Type: application/json
Authorization: Bearer ${MINERU_TOKEN}
```

请求体：

```json
{
  "files": [
    {
      "name": "paper.pdf",
      "data_id": "trace-001"
    }
  ],
  "model_version": "vlm"
}
```

成功响应核心字段：

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "batch_id": "83add1c8-4845-4731-859b-9f47fccd971f",
    "file_urls": [
      "https://..."
    ]
  }
}
```

后端需要保存：

```text
batch_id
file_urls[0]
```

### 3.2 上传本地 PDF 到 signed URL

使用 MinerU 返回的 `file_urls[0]` 执行 HTTP PUT。

命令行验证：

```powershell
curl.exe -X PUT -T ".\paper.pdf" "https://signed-upload-url"
```

成功时 HTTP 状态码为：

```text
200
```

后端 Java 实现时应直接 PUT `MultipartFile` 的输入流或临时文件内容。不要参考 PowerShell `Invoke-WebRequest -InFile`，实测它对该 signed URL 可能抛本地空引用异常。

### 3.3 轮询解析结果

请求：

```http
GET https://mineru.net/api/v4/extract-results/batch/{batchId}
Authorization: Bearer ${MINERU_TOKEN}
```

成功响应核心结构：

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "batch_id": "83add1c8-4845-4731-859b-9f47fccd971f",
    "extract_result": [
      {
        "data_id": "trace-001",
        "file_name": "paper.pdf",
        "state": "done",
        "err_msg": "",
        "full_zip_url": "https://..."
      }
    ]
  }
}
```

轮询规则建议：

```text
interval = 5s
maxAttempts = 60
最长等待约 5 分钟
```

状态处理：

| state | 处理 |
|---|---|
| `running` | 继续轮询 |
| `done` | 读取 `full_zip_url`，下载 ZIP |
| `failed` | 记录错误，更新解析状态为失败 |

## 4. 已验证结果

验证文件：

```text
mineru/9bbfd84b-06a0-475b-a87e-68cf7148f26a_origin.pdf
```

验证结果：

```text
POST /api/v4/file-urls/batch -> code=0, msg=ok
PUT signed upload URL -> HTTP 200
GET /api/v4/extract-results/batch/{batchId} -> state 从 running 变为 done
结果字段包含 full_zip_url
```

同时已验证 Spring Boot 配置加载：

```powershell
.\mvnw.cmd "-Dtest=BackendApplicationTests" test
```

结果：

```text
Tests run: 1, Failures: 0, Errors: 0
```

## 5. 与上传接口的关系

前端上传接口仍然提交：

```text
traceId
file
fileName
paperMd5
fileSizeBytes
submissionTime
```

后端使用 `file` 完成：

```text
MD5 复核
重复文献检查
MinerU signed upload
后续 Zotero 文件上传
后续 MinIO 正式存储
```

不需要前端传本地文件路径或浏览器 `blob:` URL。浏览器无法提供稳定可访问的本地真实路径。

## 6. 后续后端实现建议

建议新增组件：

```text
MinerUProperties
MinerUParseService
MinerUClient
MinerUParseResult
```

建议职责：

| 组件 | 职责 |
|---|---|
| `MinerUProperties` | 绑定 `application.yml` 中的 `mineru` 配置 |
| `MinerUClient` | 封装 HTTP 调用：申请 URL、PUT 文件、轮询结果、下载 ZIP |
| `MinerUParseService` | 面向业务层提供 `parse(MultipartFile file, traceId, fileName)` |
| `MinerUParseResult` | 承载 `batchId`、`state`、`fullZipUrl`、Markdown、JSON 等结果 |

业务流程建议：

```text
DocumentServiceImpl.uploadDocument
  -> 校验文件
  -> 计算 MD5
  -> 查重
  -> 写 paper_upload_parse_progress = 1
  -> MinerUParseService.parse(...)
  -> 下载 full_zip_url
  -> 写 Zotero
  -> 写 MinIO 正式目录
  -> 写 papers
  -> 更新 paper_upload_parse_progress = 2
```

失败处理：

```text
MinerU 申请上传 URL 失败 -> parse_status = 3
PUT 上传失败 -> parse_status = 3
轮询超时 -> parse_status = 3
MinerU 返回 failed -> parse_status = 3
下载 full_zip_url 失败 -> parse_status = 3
```

## 7. Zotero / MinIO 关系

`MultipartFile` 不是问题。后端拿到 `MultipartFile` 后，可以把同一份文件字节用于：

```text
上传到 MinerU
上传到 Zotero 文件附件
上传到 MinIO 正式存储
```

MinIO 链接适合做 ScholarEase 自己的文件访问地址；Zotero 如果要真正保存 PDF 附件，建议走 Zotero 文件上传 API，而不是只添加一个 MinIO 网页链接。

## 8. 常见问题

### 8.1 token 未配置

表现：

```text
401 / 鉴权失败 / code 非 0
```

处理：

```text
确认 MINERU_TOKEN 环境变量存在
确认 application.yml 不要写空 token
确认 token 未过期
```

### 8.2 signed URL 上传失败

检查：

```text
是否使用 PUT
是否直接上传文件字节
上传时不要额外设置 Content-Type
文件大小是否超过 MinerU 限制
```

### 8.3 轮询一直 running

处理：

```text
按 polling.maxAttempts 超时
记录 batch_id
更新 parse_status = 3 或单独标记 TIMEOUT
允许后续人工排查 batch_id
```

### 8.4 full_zip_url 为空

处理：

```text
确认 state 是否为 done
如果 state=failed，读取 err_msg
如果 state=done 但无 full_zip_url，记录完整响应并按解析失败处理
```
