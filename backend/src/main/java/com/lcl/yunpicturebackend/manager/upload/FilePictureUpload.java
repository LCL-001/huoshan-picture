package com.lcl.yunpicturebackend.manager.upload;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.exception.ThrowUtils;
import com.lcl.yunpicturebackend.utils.PictureFileCheckUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

/**
 * 文件图片上传
 */
@Service
public class FilePictureUpload extends PictureUploadTemplate {
    @Override
    protected void processFile(Object inputSource, File file) throws Exception {
        MultipartFile multipartFile = (MultipartFile) inputSource;
        multipartFile.transferTo(file);// 保存临时文件
    }

    @Override
    protected String getOriginalFilename(Object inputSource) {
        MultipartFile multipartFile = (MultipartFile) inputSource;
        return multipartFile.getOriginalFilename();
    }

    @Override
    protected void validPicture(Object inputSource) {
        MultipartFile multipartFile = (MultipartFile) inputSource;
        ThrowUtils.throwIf(multipartFile == null, ErrorCode.PARAMS_ERROR, "上传文件不能为空");
        // 1. 校验文件大小
        long fileSize = multipartFile.getSize();
        final long ONE_M = 1024 * 1024L;  // 1MB = 1024 * 1024 字节
        ThrowUtils.throwIf(fileSize > 2 * ONE_M, ErrorCode.PARAMS_ERROR, "文件大小不能超过 2M");

        // 2. 校验文件后缀
        // 2.1. 获取文件后缀，并转为小写
        String suffix = FileUtil.getSuffix(multipartFile.getOriginalFilename());
        ThrowUtils.throwIf(StrUtil.isBlank(suffix), ErrorCode.PARAMS_ERROR, "文件格式错误");
        // 2.2. 允许上传的后缀列表
        final List<String> ALLOW_FORMAT_LIST = Arrays.asList("jpg", "jpeg", "png", "webp");
        ThrowUtils.throwIf(!ALLOW_FORMAT_LIST.contains(suffix.toLowerCase()), ErrorCode.PARAMS_ERROR, "文件格式错误");

        // 3. 校验文件头魔数，防止伪造扩展名上传任意内容
        byte[] header;
        try (InputStream in = multipartFile.getInputStream()) {
            header = PictureFileCheckUtils.readHeader(in, PictureFileCheckUtils.HEADER_LENGTH);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件读取失败");
        }
        ThrowUtils.throwIf(!PictureFileCheckUtils.isSupportedImage(header),
                ErrorCode.PARAMS_ERROR, "文件内容不是支持的图片格式");
    }
}
