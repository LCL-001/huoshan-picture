package com.lcl.yunpicturebackend.controller;

import cn.hutool.core.util.StrUtil;
import com.lcl.yunpicturebackend.annotation.AuthCheck;
import com.lcl.yunpicturebackend.common.BaseResponse;
import com.lcl.yunpicturebackend.common.ResultUtils;
import com.lcl.yunpicturebackend.config.CosClientConfig;
import com.lcl.yunpicturebackend.constant.UserConstant;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.exception.ThrowUtils;
import com.lcl.yunpicturebackend.manager.CosManager;
import com.lcl.yunpicturebackend.service.IUserService;
import com.lcl.yunpicturebackend.utils.PictureFileCheckUtils;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectInputStream;
import com.qcloud.cos.utils.IOUtils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Api(tags = "文件相关接口")
@RestController
@RequestMapping("/file")
@Slf4j
public class FileController {
    @Resource
    private CosManager cosManager;

    @Resource
    private IUserService userService;

    @Resource
    private CosClientConfig cosClientConfig;

    /**
     * 文件上传（通用接口，登录即可使用）
     */
    @ApiOperation("文件上传")
    @PostMapping("/upload")
    public BaseResponse<String> uploadFile(
            @RequestPart("file") MultipartFile multipartFile,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        // 判断用户是否拥有权限
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);

        String originalFilename = multipartFile.getOriginalFilename();
        if (StrUtil.isBlank(originalFilename) || originalFilename.lastIndexOf(".") < 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件名不合法");
        }
        // 扩展名白名单：该接口面向公网，仅允许图片格式，防止把 COS 当任意文件床
        String suffix = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
        final List<String> ALLOW_SUFFIX_LIST = Arrays.asList(".jpg", ".jpeg", ".png", ".webp");
        ThrowUtils.throwIf(!ALLOW_SUFFIX_LIST.contains(suffix), ErrorCode.PARAMS_ERROR, "文件格式仅支持 jpg/jpeg/png/webp");
        // 魔数校验：文件内容必须为真实图片，防止伪造扩展名
        byte[] header;
        try (InputStream in = multipartFile.getInputStream()) {
            header = PictureFileCheckUtils.readHeader(in, PictureFileCheckUtils.HEADER_LENGTH);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件读取失败");
        }
        ThrowUtils.throwIf(!PictureFileCheckUtils.isSupportedImage(header),
                ErrorCode.PARAMS_ERROR, "文件内容不是支持的图片格式");
        String key = String.format("/avatar/%s%s", UUID.randomUUID(), suffix);
        File file = null;
        try {
            file = File.createTempFile(key, null);
            multipartFile.transferTo(file);
            cosManager.putObject(key, file);
            return ResultUtils.success(cosClientConfig.getHost() + key);
        } catch (Exception e) {
            log.error("file upload error, filepath = " + key, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上传失败");
        } finally {
            if (file != null) {
                file.delete();
            }
        }
    }


    /**
     * 测试文件上传
     *
     * @param multipartFile
     * @return
     */
    @ApiOperation("测试文件上传")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @PostMapping("/test/upload")
    public BaseResponse<String> testUploadFile(@RequestPart("file") MultipartFile multipartFile) {
        // 文件目录
        String filename = multipartFile.getOriginalFilename();
        String filepath = String.format("/test/%s", filename);
        File file = null;
        try {
            // 上传文件
            file = File.createTempFile(filepath, null);
            multipartFile.transferTo(file);
            cosManager.putObject(filepath, file);
            // 返回可访问地址
            return ResultUtils.success(filepath);
        } catch (Exception e) {
            log.error("file upload error, filepath = " + filepath, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上传失败");
        } finally {
            if (file != null) {
                // 删除临时文件
                boolean delete = file.delete();
                if (!delete) {
                    log.error("file delete error, filepath = {}", filepath);
                }
            }
        }
    }

    /**
     * 测试文件下载
     *
     * @param filepath 文件路径
     * @param response 响应对象
     */
    @ApiOperation("测试文件下载")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @GetMapping("/test/download/")
    public void testDownloadFile(String filepath, HttpServletResponse response) throws IOException {
        COSObjectInputStream cosObjectInput = null;
        try {
            COSObject cosObject = cosManager.getObject(filepath);
            cosObjectInput = cosObject.getObjectContent();
            // 处理下载到的流
            byte[] bytes = IOUtils.toByteArray(cosObjectInput);
            // 设置响应头（剔除 CR/LF，防止响应头注入）
            String safeFilepath = filepath.replaceAll("[\\r\\n]", "");
            response.setContentType("application/octet-stream;charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=" + safeFilepath);
            // 写入响应
            response.getOutputStream().write(bytes);
            response.getOutputStream().flush();
        } catch (Exception e) {
            log.error("file download error, filepath = " + filepath, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "下载失败");
        } finally {
            if (cosObjectInput != null) {
                cosObjectInput.close();
            }
        }
    }

}
