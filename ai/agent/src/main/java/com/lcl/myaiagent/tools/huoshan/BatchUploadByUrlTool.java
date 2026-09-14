package com.lcl.myaiagent.tools.huoshan;

import com.lcl.myaiagent.tools.huoshan.dto.BatchUploadResult;
import com.lcl.myaiagent.tools.huoshan.dto.PictureItem;
import com.lcl.myaiagent.tools.huoshan.dto.UploadResultItem;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Set;

/**
 * 按 URL 批量入库（T7 档 3，**写操作**）：图库 {@code POST /picture/upload/url} 是单张端点，
 * 本工具按粗粒度原则（设计文档 L60/L64）在内部循环逐条调用，模型一步完成一批。
 * <p>
 * 逐条上报成败：成功给新图片 id、失败给图库业务码与原因（图片已存在 / 空间额度不足 / 下载失败…），
 * 部分失败**不算整次失败**，也不重试——Pexels 免费档限流与额度类失败重试无益（设计文档 L99）。
 * </p>
 * <p>
 * 唯一的提前退出：命中登录态/空间登录态失效（40100 / 40102）说明后面每一条都会同样失败，
 * 与其白烧 N 次调用，不如停在这里并把"剩余未尝试"如实告诉模型。
 * </p>
 */
public class BatchUploadByUrlTool {

    /** 单次上限：每条都含"图库下载 + COS 上传"，一次太多会把一步拖成长请求（代理侧 read-timeout 6 分钟） */
    static final int MAX_URLS_PER_CALL = 20;

    /** 登录态失效类错误码：命中即停止后续条目（这些码后面的条目必然同样失败） */
    private static final Set<Integer> FATAL_AUTH_CODES = Set.of(40100, 40102);

    private final HuoshanApiClient client;

    public BatchUploadByUrlTool(HuoshanApiClient client) {
        this.client = client;
    }

    @Tool(description = """
            按图片 URL 批量入库到一个空间（写操作，占用该空间额度）。
            图库会自己下载这些 URL：只接受 http/https、单张不超过 2M、格式需为 JPEG/PNG/WEBP，
            所以 URL 必须是能直接访问到的图片地址（例如搜图工具返回的地址）。
            只有用户明确要求"把这些图放进某空间"时才调用；没说清放进哪个空间就先问。
            返回 JSON：spaceId / requested / succeeded / failed / skipped / note / items[]；
            items[] 每条含 fileUrl、ok，成功给 pictureId 与 name，失败给 code 与 message。
            确定性失败（图片已存在、额度不足）不会重试；登录态失效会提前停止并说明剩余条数。
            """)
    public String batchUploadByUrl(
            @ToolParam(description = "目标空间 id（字符串，原样复制 listSpaces 返回的 items[].id）") String spaceId,
            @ToolParam(description = "图片 URL 列表（http/https 的图片直链）") List<String> fileUrls) {
        return HuoshanToolSupport.render(() -> upload(spaceId, fileUrls));
    }

    private BatchUploadResult upload(String spaceId, List<String> fileUrls) {
        if (spaceId == null || spaceId.isBlank()) {
            throw new HuoshanApiException(HuoshanApiClient.PARAMS_ERROR_CODE,
                    "缺少 spaceId：先调 listSpaces 取目标空间 id");
        }
        List<String> urls = HuoshanToolSupport.cleanList(fileUrls);
        if (urls.isEmpty()) {
            throw new HuoshanApiException(HuoshanApiClient.PARAMS_ERROR_CODE,
                    "缺少 fileUrls：至少给一个图片 URL");
        }
        if (urls.size() > MAX_URLS_PER_CALL) {
            throw new HuoshanApiException(HuoshanApiClient.PARAMS_ERROR_CODE,
                    "一次最多入库 " + MAX_URLS_PER_CALL + " 张，本次给了 " + urls.size() + " 张，请分批调用");
        }

        BatchUploadResult result = new BatchUploadResult();
        result.setSpaceId(spaceId);
        result.setRequested(urls.size());
        for (int i = 0; i < urls.size(); i++) {
            String fileUrl = urls.get(i);
            try {
                result.getItems().add(succeeded(fileUrl, client.uploadByUrl(spaceId, fileUrl)));
            } catch (HuoshanApiException e) {
                result.getItems().add(failed(fileUrl, e.getCode(), e.getMessage()));
                if (FATAL_AUTH_CODES.contains(e.getCode())) {
                    int skipped = urls.size() - i - 1;
                    result.setNote("图库返回 " + e.getCode() + "（" + e.getMessage() + "），"
                            + "后续条目同样会失败，已提前停止；剩余 " + skipped + " 条未尝试，请用户重新登录后再继续。");
                    break;
                }
            }
        }
        int ok = (int) result.getItems().stream().filter(UploadResultItem::isOk).count();
        result.setSucceeded(ok);
        result.setFailed(result.getItems().size() - ok);
        result.setSkipped(urls.size() - result.getItems().size());
        return result;
    }

    private static UploadResultItem succeeded(String fileUrl, PictureItem uploaded) {
        UploadResultItem item = new UploadResultItem();
        item.setFileUrl(fileUrl);
        item.setOk(true);
        if (uploaded != null) {
            item.setPictureId(uploaded.getId());
            item.setName(uploaded.getName());
        }
        return item;
    }

    private static UploadResultItem failed(String fileUrl, int code, String message) {
        UploadResultItem item = new UploadResultItem();
        item.setFileUrl(fileUrl);
        item.setOk(false);
        item.setCode(code);
        item.setMessage(message);
        return item;
    }
}
