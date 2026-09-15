package com.lcl.myaiagent.tools;

import cn.hutool.core.io.FileUtil;
import com.lcl.myaiagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件操作工具类
 * 提供基于Spring AI Tool注解的文件读写功能，供AI助手调用
 * 支持UTF-8编码的文件内容读取和写入操作
 */
public class FileOperationTool {

    /** 越界或空文件名的拒答前缀：调用方（模型）据此知道是参数非法而不是文件不存在 */
    static final String ILLEGAL_NAME_PREFIX = "Error: illegal file name (empty, or outside the tool directory): ";

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
        Path filePath = resolveInsideDir(fileName);
        if (filePath == null) {
            return ILLEGAL_NAME_PREFIX + fileName;
        }
        try {
            return FileUtil.readUtf8String(filePath.toString());
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
        Path filePath = resolveInsideDir(fileName);
        if (filePath == null) {
            return ILLEGAL_NAME_PREFIX + fileName;
        }
        try {
            // 确保目标目录存在，不存在则自动创建
            FileUtil.mkdir(filePath.getParent().toString());
            FileUtil.writeUtf8String(content, filePath.toString());
            return "File written successfully to: " + filePath;
        } catch (Exception e) {
            return "Error writing to file: " + e.getMessage();
        }
    }

    /**
     * 把文件名解析成 FILE_DIR 之内的绝对路径；越界（{@code ../} 穿越、绝对路径、空名）返回 null。
     * <p>
     * 为什么必须有这道校验：{@code fileName} 由模型给出，而模型的输入（用户提示词）由调用方控制，
     * 未加校验时 {@code ../../x} 会逃出 tmp/file 目录、形成**任意文件读写**（2026-09-15 复核 D1）。
     * 归一化后做前缀比较可同时覆盖 {@code ../} 与绝对路径两类（Windows 盘符与 root-relative 亦同）。
     * </p>
     */
    private Path resolveInsideDir(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        Path base = Paths.get(FILE_DIR).toAbsolutePath().normalize();
        Path target = base.resolve(fileName).normalize();
        return target.startsWith(base) ? target : null;
    }
}
