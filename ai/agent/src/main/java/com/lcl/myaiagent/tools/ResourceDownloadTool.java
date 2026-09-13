package com.lcl.myaiagent.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpUtil;
import com.lcl.myaiagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.File;

/**
 * 资源下载工具类
 * 提供从指定URL下载资源并保存到本地文件的功能，供AI助手调用
 */
public class ResourceDownloadTool {

    /**
     * 从指定URL下载资源并保存到本地文件
     * 使用Hutool工具库实现HTTP下载功能，自动创建目标目录
     *
     * @param url      要下载的资源的URL地址
     * @param fileName 保存下载资源的文件名，不包含路径信息
     * @return String 下载成功时返回文件完整路径；失败时返回错误信息
     */
    @Tool(description = "Download a resource from a given URL")
    public String downloadResource(@ToolParam(description = "URL of the resource to download") String url, @ToolParam(description = "Name of the file to save the downloaded resource") String fileName) {
        String fileDir = FileConstant.FILE_SAVE_DIR + "/download";
        String filePath = fileDir + "/" + fileName;
        try {
            // 确保目标目录存在，不存在则自动创建
            FileUtil.mkdir(fileDir);
            // 使用 Hutool 的 downloadFile 方法下载资源
            HttpUtil.downloadFile(url, new File(filePath));
            return "Resource downloaded successfully to: " + filePath;
        } catch (Exception e) {
            return "Error downloading resource: " + e.getMessage();
        }
    }
}
