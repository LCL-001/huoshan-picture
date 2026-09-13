package com.lcl.myaiagent.controller;

import cn.hutool.core.io.FileUtil;
import com.lcl.myaiagent.constant.FileConstant;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

@RestController
@RequestMapping("/files")
public class FileController {

    private static final File PDF_DIR = new File(FileConstant.FILE_SAVE_DIR, "pdf");

    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam String name) throws IOException {
        // 防御路径穿越：用 Path API 提取纯文件名，拒绝绕过
        String safeName = Path.of(name).getFileName().toString();
        if (safeName.contains("..") || safeName.contains("/") || safeName.contains("\\")) {
            return ResponseEntity.badRequest().build();
        }
        File file = new File(PDF_DIR, safeName);
        if (!file.getCanonicalPath().startsWith(PDF_DIR.getCanonicalPath())) {
            return ResponseEntity.badRequest().build();
        }
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(file);
        String encodedName = URLEncoder.encode(safeName, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedName)
                .body(resource);
    }
}
