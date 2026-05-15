# Zotero + MinerU 示例文件导入步骤总结

## 目标

将 `mineru/ScholarEase` 目录下的 MinerU 示例解析产物导入本地 Zotero：

- `origin.pdf` 作为主 PDF 文件先导入 Zotero。
- `full.md` 和 `content_list_v2.json` 作为该 PDF 对应文献条目的附件。
- 不修改 ScholarEase 代码，只验证本地文件和 Zotero 导入链路。

## 本地准备

先根据接口文档和本地 MinerU 示例目录确认需要的文件。当前示例文件来自：

```text
mineru/
```

为了形成一个后续可复用的导入目录，创建了：

```text
mineru/ScholarEase/
```

其中放入：

```text
mineru/ScholarEase/origin.pdf
mineru/ScholarEase/full.md
mineru/ScholarEase/content_list_v2.json
mineru/ScholarEase/images/
```

说明：

- `origin.pdf` 来自原始 MinerU 示例 PDF：
  `mineru/9bbfd84b-06a0-475b-a87e-68cf7148f26a_origin.pdf`
- `full.md` 来自：
  `mineru/full.md`
- `content_list_v2.json` 来自：
  `mineru/9bbfd84b-06a0-475b-a87e-68cf7148f26a_content_list_v2.json`
- `images/` 一并复制，因为 `full.md` 和 `content_list_v2.json` 中有相对图片路径引用。

本地校验结果：

- `content_list_v2.json` 可以正常解析为 JSON。
- 解析产物中引用的图片均能在 `mineru/ScholarEase/images/` 下找到。
- 主文件和复制后的目标文件 MD5 一致。

## Zotero 环境确认

Zotero 已运行，本地 HTTP 服务可访问：

```text
http://127.0.0.1:23119
```

实际 Zotero 数据目录不是默认的 `C:\Users\kesf\Zotero`，而是：

```text
E:\zoteroData
```

对应的 Zotero 存储目录：

```text
E:\zoteroData\storage
```

参考的 Zotero 机制：

- Zotero stored files 会复制到 Zotero data directory。
- 将文件附加到已有普通文献条目时，文件会成为该条目的 child attachment。
- Zotero 本地 `/api/...` 读接口可用于检查结果；写入文件时使用 connector 的附件导入能力。

参考文档：

- https://www.zotero.org/support/attaching_files
- https://www.zotero.org/support/dev/client_coding/connector_http_server

## 第一次处理中的问题

最初误判了截图中已有的 Zotero 条目：

```text
KLI52KIG
Assessing the Added Value of Sentinel-1 PolSAR Data for Crop Classification
```

这个条目已经有一个 PDF 附件：

```text
5PHCLZUA
```

因此第一次只把 `full.md` 和 `content_list_v2.json` 挂到了 `KLI52KIG` 下，没有导入 `origin.pdf`。这与目标流程不一致。

随后检查 `mineru/ScholarEase/full.md` 后发现，MinerU 示例 PDF 对应的是另一篇论文：

```text
The response of flow duration curves to afforestation
```

所以正确流程应为：

1. 先导入 `origin.pdf`。
2. 让 Zotero 识别该 PDF 并生成父文献条目。
3. 再把 `full.md` 和 `content_list_v2.json` 挂到这个新条目下。

## 正确导入流程

### 1. 导入 PDF 主文件

将：

```text
E:\Javacode\ScholarEase\mineru\ScholarEase\origin.pdf
```

通过 Zotero connector 作为 standalone PDF 附件导入。

Zotero 返回：

```text
201 Created
{"canRecognize":true}
```

这表示 PDF 已导入，并且 Zotero 可以自动识别文献元数据。

识别完成后生成的新父条目为：

```text
PVAYYBNU
The response of flow duration curves to afforestation
```

PDF 主附件为：

```text
ZM85JPB3
```

Zotero 中的 PDF 存储路径：

```text
E:\zoteroData\storage\ZM85JPB3\Lane 等 - 2005 - The response of flow duration curves to afforestation.pdf
```

### 2. 导入 MinerU 解析附件

将以下两个文件导入 Zotero：

```text
E:\Javacode\ScholarEase\mineru\ScholarEase\full.md
E:\Javacode\ScholarEase\mineru\ScholarEase\content_list_v2.json
```

导入后生成的附件 key：

```text
6SRUNRYN  -> full.md
XLUMYLU7  -> content_list_v2.json
```

Zotero 中的存储路径：

```text
E:\zoteroData\storage\6SRUNRYN\full.md
E:\zoteroData\storage\XLUMYLU7\content_list_v2.json
```

注意：

- `content_list_v2.json` 使用 `application/json` 作为上传 content type 时，Zotero connector 返回了 `500 Internal Server Error`。
- 后续改用可被 Zotero connector 正常按文件流保存的 content type 完成导入。
- 文件名仍为 `content_list_v2.json`，文件内容未改变，后续 MD5 校验一致。

### 3. 挂载附件到 PDF 对应父条目

将解析附件重新挂载到新生成的父文献条目：

```text
parent item: PVAYYBNU
attachments: 6SRUNRYN, XLUMYLU7
```

挂载结果：

```text
Re-parented 2/2 item(s) under PVAYYBNU
```

同时，之前误挂到 `KLI52KIG` 下的 MinerU 附件已经移动到 `PVAYYBNU` 下。

## 最终 Zotero 结构

最终 Zotero 条目：

```text
PVAYYBNU
The response of flow duration curves to afforestation
```

元数据识别结果包括：

```text
Title: The response of flow duration curves to afforestation
Authors: Patrick N.J. Lane, Alice E. Best, Klaus Hickel, Lu Zhang
Date: 8/2005
Publication: Journal of Hydrology
Volume: 310
Issue: 1-4
Pages: 253-265
DOI: 10.1016/j.jhydrol.2005.01.006
```

最终附件：

| 角色 | Zotero key | 文件名 | Zotero 存储路径 |
|---|---|---|---|
| PDF 主文件 | `ZM85JPB3` | `Lane 等 - 2005 - The response of flow duration curves to afforestation.pdf` | `E:\zoteroData\storage\ZM85JPB3\Lane 等 - 2005 - The response of flow duration curves to afforestation.pdf` |
| MinerU Markdown | `6SRUNRYN` | `full.md` | `E:\zoteroData\storage\6SRUNRYN\full.md` |
| MinerU JSON | `XLUMYLU7` | `content_list_v2.json` | `E:\zoteroData\storage\XLUMYLU7\content_list_v2.json` |

## 文件一致性验证

最终对源文件和 Zotero storage 文件做了 MD5 校验。

| 文件 | 源文件 MD5 | Zotero storage MD5 | 结果 |
|---|---|---|---|
| `origin.pdf` | `8A6DF569DB6FA9D5B6281C2CB2992025` | `8A6DF569DB6FA9D5B6281C2CB2992025` | 一致 |
| `full.md` | `872EB2B2DA17B5F4DA123CDF66FAC2E5` | `872EB2B2DA17B5F4DA123CDF66FAC2E5` | 一致 |
| `content_list_v2.json` | `5978ADA7620376DF008BE3372B368FBE` | `5978ADA7620376DF008BE3372B368FBE` | 一致 |

## 当前工作目录变化

代码未修改。

当前新增的工作目录文件主要是：

```text
mineru/ScholarEase/
zotero-mineru-import-summary.md
```

其中 `mineru/ScholarEase/` 是为本地 Zotero 导入测试整理出的示例目录。

## 结论

本地链路已经跑通：

```text
MinerU 示例文件整理
  -> 导入 origin.pdf 到 Zotero
  -> Zotero 自动识别 PDF 元数据并创建父文献条目
  -> 导入 full.md 和 content_list_v2.json
  -> 将解析产物作为附件挂到 PDF 对应父条目下
  -> 校验 Zotero storage 文件与本地源文件一致
```

正确的最终父条目是：

```text
PVAYYBNU
The response of flow duration curves to afforestation
```

不是最初截图中的 Sentinel-1 条目 `KLI52KIG`。
