package com.lcl.myaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * 终端操作工具类
 * 提供执行系统命令的功能，供AI助手调用以执行Windows终端命令并获取输出结果
 */
public class TerminalOperationTool {

    /**
     * 执行终端命令
     * 通过ProcessBuilder在Windows环境下执行指定的命令，捕获标准输出和错误信息
     *
     * @param command 要在终端中执行的命令字符串
     * @return String 命令执行的标准输出内容；如果执行失败则返回错误信息和退出码
     */
    @Tool(description = "Execute a command in the terminal")
    public String executeTerminalCommand(@ToolParam(description = "Command to execute in the terminal") String command) {
        StringBuilder output = new StringBuilder();
        Process process = null;
        
        try {
            // 验证命令是否为空
            if (command == null || command.trim().isEmpty()) {
                return "Error: Command cannot be empty";
            }
            
            // 构建Windows命令执行器，使用cmd.exe /c执行命令
            ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/c", command);
            // 合并错误流和标准输出流，便于捕获错误信息
            builder.redirectErrorStream(true);
            // 设置工作目录为当前用户目录
            builder.directory(new java.io.File(System.getProperty("user.home")));
            
            // 启动进程并执行命令
            process = builder.start();
            
            // 读取命令的输出流（已合并错误流）
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "GBK"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            // 等待命令执行完成并检查退出码
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                output.append("\nCommand execution failed with exit code: ").append(exitCode);
            }
        } catch (IOException e) {
            output.append("Error executing command: ").append(e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("CreateProcess error=2")) {
                output.append("\nHint: The command or program was not found. Please check if the command exists and is in PATH.");
            }
        } catch (InterruptedException e) {
            output.append("Command execution was interrupted");
            Thread.currentThread().interrupt();
        } finally {
            // 确保进程资源被释放
            if (process != null) {
                process.destroy();
            }
        }
        return output.toString().trim();
    }
}
