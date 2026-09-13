package com.lcl.myaiagent.tools;

import cn.hutool.core.io.FileUtil;
import com.lcl.myaiagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 文件操作工具类
 * 提供基于Spring AI Tool注解的文件读写功能，供AI助手调用
 * 支持UTF-8编码的文件内容读取和写入操作
 */
public class FileOperationTool {

    /**
     * 文件保存目录路径
     * 由基础目录和子目录"file"组成，用于存储所有通过工具操作的文件
     */
    private final String FILE_DIR = FileConstant.FILE_SAVE_DIR + "/file";


    /**
     * 读取指定文件的内容
     * 从配置的文件目录中读取指定名称的文件，返回 UTF-8 编码的文本内容
     *
     * @param fileName 要读取的文件名称，不包含路径信息
     * @return String 文件的文本内容，如果读取失败则返回错误信息
     */
    @Tool(description = "Read content from a file")
    public String readFile(@ToolParam(description = "Name of the file to read") String fileName) {
        String filePath = FILE_DIR + "/" + fileName;
        try {
            return FileUtil.readUtf8String(filePath);
        } catch (Exception e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    /**
     * 将内容写入指定文件
     * 在配置的文件目录中创建或覆盖指定名称的文件，写入 UTF-8 编码的文本内容
     * 如果目标目录不存在，会自动创建目录结构
     *
     * @param fileName 要写入的文件名称，不包含路径信息
     * @param content  要写入文件的文本内容
     * @return String 操作结果信息，成功时返回文件完整路径，失败时返回错误信息
     */
    @Tool(description = "Write content to a file")
    public String writeFile(
            @ToolParam(description = "Name of the file to write") String fileName,
            @ToolParam(description = "Content to write to the file") String content) {
        String filePath = FILE_DIR + "/" + fileName;
        try {
            // 确保目标目录存在，不存在则自动创建
            FileUtil.mkdir(FILE_DIR);
            FileUtil.writeUtf8String(content, filePath);
            return "File written successfully to: " + filePath;
        } catch (Exception e) {
            return "Error writing to file: " + e.getMessage();
        }
    }
}
