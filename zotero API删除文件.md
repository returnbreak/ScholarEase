为了方便你在 Java 项目中快速集成和使用，我将上述通过 Zotero API 删除特定文献下 PDF 附件的逻辑整理成了详细的步骤，并为你编写了完整的 Java 代码实现（基于目前最常用的 HTTP 客户端库 `OkHttp` 和 JSON 解析库 `Gson`）。

我已经将这些内容生成为了一个结构清晰、可以直接导入使用的 Markdown (.md) 文件。

### 核心步骤说明

1. **获取父条目最新版本**：通过 GET 请求获取目标主文献条目的详情，获取其 `Last-Modified-Version`（或 `version`），用于并发冲突校验。
2. **获取子条目列表**：通过请求 `/items/<parentKey>/children` 接口，拿到该文献下的所有子条目。
3. **筛选 PDF 附件**：遍历子条目，寻找 `itemType == "attachment"` 且 `contentType == "application/pdf"` 的条目，记录下它的 `key`。
4. **执行删除**：向 `/items/<attachmentKey>` 发送 `DELETE` 请求，必须在请求头中携带 `If-Unmodified-Since-Version`。

下面是为您生成的 Markdown 文件：

Python

```
import os

# 定义 Markdown 内容
md_content = """# Zotero API - 使用 Java 删除指定文献的 PDF 附件指南

在 Zotero 中，PDF 文件是以**子附件条目 (Attachment Item)** 的形式挂载在**父条目 (主文献)** 下方的。要删除特定的 PDF 附件而不影响主文献，需要遵循一套完整的请求流。

本指南将详细介绍实现的步骤，并提供基于 **Java (OkHttp + Gson)** 的完整代码示例。

---

## 🛠️ 核心开发步骤

1. **获取父条目信息与版本号** 向 `GET /users/<userID>/items/<parentKey>` 发送请求，获取主文献的最新数据。从响应头或响应体中提取当前的 `version`（用于后续删除时的防冲突校验）。

2. **获取当前文献的所有子条目** 向 `GET /users/<userID>/items/<parentKey>/children` 发送请求。该接口会返回该文献关联的所有笔记、快照和附件。

3. **筛选出 PDF 附件条目** 遍历返回的子条目列表，通过以下两个字段精准匹配：
   - `data.itemType` 等于 `"attachment"`
   - `data.contentType` 等于 `"application/pdf"`  
   匹配成功后，记录该子条目的 `key`（即 `attachmentKey`）。

4. **发送删除请求 (DELETE)** 向 `DELETE /users/<userID>/items/<attachmentKey>` 发送请求。
   > ⚠️ **关键点**：必须在请求头 (Headers) 中加入 `If-Unmodified-Since-Version: <步骤1中获取的版本号>`，否则 Zotero API 会拒绝请求。

---

## ☕ Java 实现代码

### 1. 依赖配置 (Maven `pom.xml`)
在你的 Java 项目中，引入 OkHttp（用于发送网络请求）和 Gson（用于解析 JSON 数据）：

```xml
<dependencies>
    <dependency>
        <groupId>com.squareup.okhttp3</groupId>
        <artifactId>okhttp</artifactId>
        <version>4.12.0</version>
    </dependency>
    <dependency>
        <groupId>com.google.code.gson</groupId>
        <artifactId>gson</artifactId>
        <version>2.10.1</version>
    </dependency>
</dependencies>
```

### 2. 完整 Java 源代码

你可以直接复制以下代码并填入你的 `API_KEY`、`USER_ID` 和 `PARENT_ITEM_KEY`。

Java

```
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

public class ZoteroAttachmentDeleter {

    // ================= 配置参数 =================
    private static final String BASE_URL = "[https://api.zotero.org](https://api.zotero.org)";
    private static final String USER_ID = "你的_ZOTERO_USER_ID";
    private static final String API_KEY = "你的_ZOTERO_API_KEY";
    private static final String API_VERSION = "3";
    
    // 你想要操作的主文献（父条目）的 Key
    private static final String PARENT_ITEM_KEY = "ABC123XY"; 
    // ============================================

    private static final OkHttpClient client = new OkHttpClient();

    public static void main(String[] args) {
        try {
            System.out.println("开始执行 Zotero PDF 删除流程...");
            
            // 步骤 1: 获取父条目的最新版本号
            long currentVersion = getParentItemVersion(PARENT_ITEM_KEY);
            System.out.println("成功获取父条目版本号: " + currentVersion);

            // 步骤 2 & 3: 获取子条目并寻找 PDF 附件的 Key
            String attachmentKey = findPdfAttachmentKey(PARENT_ITEM_KEY);

            if (attachmentKey != null) {
                System.out.println("找到目标 PDF 附件 Key: " + attachmentKey + "，准备执行删除...");
                // 步骤 4: 发送删除请求
                deleteItem(attachmentKey, currentVersion);
            } else {
                System.out.println("❌ 未在当前文献下找到 PDF 附件。");
            }

        } catch (Exception e) {
            System.err.println("❌ 发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 步骤 1: 获取父条目的 Version
     */
    private static long getParentItemVersion(String itemKey) throws IOException {
        String url = String.format("%s/users/%s/items/%s", BASE_URL, USER_ID, itemKey);

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Zotero-API-Version", API_VERSION)
                .addHeader("Zotero-API-Key", API_KEY)
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("无法获取父条目信息: " + response.code() + " " + response.message());
            }
            String responseBody = response.body().string();
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            
            // 提取版本号
            return json.get("version").getAsLong();
        }
    }

    /**
     * 步骤 2 & 3: 获取子条目列表并筛选出第一个 PDF 附件的 Key
     */
    private static String findPdfAttachmentKey(String parentKey) throws IOException {
        String url = String.format("%s/users/%s/items/%s/children", BASE_URL, USER_ID, parentKey);

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Zotero-API-Version", API_VERSION)
                .addHeader("Zotero-API-Key", API_KEY)
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("无法获取子条目列表: " + response.code());
            }

            String responseBody = response.body().string();
            JsonArray childrenArray = JsonParser.parseString(responseBody).getAsJsonArray();

            // 遍历所有子条目
            for (JsonElement element : childrenArray) {
                JsonObject item = element.getAsJsonObject();
                JsonObject data = item.getAsJsonObject("data");

                String itemType = data.has("itemType") ? data.get("itemType").getAsString() : "";
                String contentType = data.has("contentType") ? data.get("contentType").getAsString() : "";

                // 精准匹配：条目类型为附件，且内容类型为 PDF
                if ("attachment".equals(itemType) && "application/pdf".equals(contentType)) {
                    return data.get("key").getAsString(); // 返回附件的唯一 Key
                }
            }
        }
        return null; // 未找到则返回 null
    }

    /**
     * 步骤 4: 带有版本校验的删除操作
     */
    private static void deleteItem(String itemKey, long version) throws IOException {
        String url = String.format("%s/users/%s/items/%s", BASE_URL, USER_ID, itemKey);

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Zotero-API-Version", API_VERSION)
                .addHeader("Zotero-API-Key", API_KEY)
                .addHeader("If-Unmodified-Since-Version", String.valueOf(version)) // 必须携带版本校验
                .delete() // 使用 DELETE 方法
                .build();

        try (Response response = client.newCall(request).execute()) {
            // Zotero API 成功删除时通常返回 204 No Content
            if (response.code() == 204 || response.isSuccessful()) {
                System.out.println("✅ PDF 附件已成功移入 Zotero 回收站！");
            } else {
                System.err.println("❌ 删除失败，API 返回状态码: " + response.code());
                System.err.println("错误详情: " + response.body().string());
            }
        }
    }
}
```

------

## 💡 开发者注意事项

1. **关于垃圾箱 (Trash)** 该 Java 代码调用的 `DELETE` 请求并不会立刻彻底物理抹除文件，而是将其移入 Zotero 账户的 **回收站 (Trash)** 中。如果想完全释放云端空间，可以在 Zotero 客户端手动右键清空回收站。

2. **群组库 (Group Library) 的适配** 本代码针对的是“个人文献库 (`user`)”。如果你需要操作的是“群组文献库”，请做如下修改：

   - 将 URL 中的 `.../users/...` 修改为 `.../groups/...`
   - 将 `USER_ID` 参数替换为群组的 `GroupID`。

3. **并发冲突控制 (`412 Precondition Failed`)** 如果在调用 `deleteItem` 时遇到 `412` 错误，说明在你获取版本号到执行删除的短暂时间内，该文献在其他设备上被同步修改了。此时需要重新执行一遍程序以获取最新的 `version`。

   """

# 将内容写入到本地 Markdown 文件

file_name = "Zotero_Delete_Attachment_Guide.md"

with open(file_name, "w", encoding="utf-8") as f:

f.write(md_content)

print(f"File {file_name} generated successfully.")

""" Cody bytes to write file """