package com.lcl.yunpicturebackend.manager.upload;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.lcl.yunpicturebackend.config.CosClientConfig;
import com.lcl.yunpicturebackend.domain.dto.file.UploadPictureResult;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.manager.CosManager;
import com.lcl.yunpicturebackend.utils.ColorTransformUtils;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.CIObject;
import com.qcloud.cos.model.ciModel.persistence.ImageInfo;
import com.qcloud.cos.model.ciModel.persistence.ProcessResults;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Resource;
import java.io.File;
import java.util.Date;
import java.util.List;

@Slf4j
public abstract class PictureUploadTemplate {

    @Resource
    private CosClientConfig cosClientConfig;

    @Resource
    private CosManager cosManager;

    public final UploadPictureResult uploadPicture(Object inputSource, String uploadPathPrefix) {
        // 1. 校验图片
        validPicture(inputSource);
        // 2. 获取图片上传地址
        String uuid = RandomUtil.randomString(12);
        String originalFilename = getOriginalFilename(inputSource);
        // 获取文件扩展名
        String suffix = StrUtil.blankToDefault(FileUtil.getSuffix(originalFilename), "jpg");
        String uploadFilename = String.format("%s_%s.%s", DateUtil.formatDate(new Date()), uuid, suffix);
        String uploadPath = String.format("%s/%s", uploadPathPrefix, uploadFilename);
        File file = null;
        try {
            // 3. 创建临时文件
            file = File.createTempFile(uploadPath, null);
            // 4. 处理文件来源(本地或 URL)
            processFile(inputSource, file);
            // 5. 上传图片
            PutObjectResult putObjectResult = cosManager.putPictureObject(uploadPath, file);
            // 6. 获取图片信息
            ImageInfo imageInfo = putObjectResult.getCiUploadResult().getOriginalInfo().getImageInfo();
            ProcessResults processResults = putObjectResult.getCiUploadResult().getProcessResults();
            List<CIObject> objectList = processResults.getObjectList();
            if (CollUtil.isNotEmpty(objectList)) {
                CIObject compressdCiObject = objectList.get(0);// 压缩图
                // 缩略图默认为压缩图
                CIObject thumbnailCiObject = compressdCiObject;
                if (objectList.size() > 1) {
                    thumbnailCiObject = objectList.get(1);// 缩略图
                }
                // 封装压缩图返回结果
                return buildResult(originalFilename, compressdCiObject, thumbnailCiObject, imageInfo);
            }
            // 封装原图返回结果
            return buildResult(originalFilename, file, uploadPath, imageInfo);
        } catch (Exception e) {
            log.error("图片上传到对象存储失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上传失败");
        } finally {
            this.deleteTempFile(file);
        }

    }

    /**
     * 构建上传结果
     *
     * @param originalFilename  原始文件名
     * @param compressdCiObject 压缩图
     * @param thumbnailCiObject
     * @param imageInfo
     * @return
     */
    private UploadPictureResult buildResult(String originalFilename, CIObject compressdCiObject, CIObject thumbnailCiObject, ImageInfo imageInfo) {
        UploadPictureResult uploadPictureResult = new UploadPictureResult();
        int picHeight = compressdCiObject.getHeight();
        int picWidth = compressdCiObject.getWidth();
        double picScale = NumberUtil.round(picWidth * 1.0 / picHeight, 2).doubleValue();
        uploadPictureResult.setPicName(FileUtil.mainName(originalFilename));
        uploadPictureResult.setPicSize(compressdCiObject.getSize().longValue());
        uploadPictureResult.setPicWidth(picWidth);
        uploadPictureResult.setPicHeight(picHeight);
        uploadPictureResult.setPicScale(picScale);
        uploadPictureResult.setPicFormat(compressdCiObject.getFormat());
        uploadPictureResult.setPicColor(ColorTransformUtils.getStandardColor(imageInfo.getAve()));
        // 设置图片为压缩后的地址
        uploadPictureResult.setUrl(cosClientConfig.getHost() + "/" + compressdCiObject.getKey());
        // 设置缩略图的地址
        uploadPictureResult.setThumbnailUrl(cosClientConfig.getHost() + "/" + thumbnailCiObject.getKey());
        return uploadPictureResult;
    }


    /**
     * 构建上传结果
     * @param originalFilename
     * @param file
     * @param uploadPath
     * @param imageInfo
     * @return
     */
    private UploadPictureResult buildResult(String originalFilename, File file, String uploadPath, ImageInfo imageInfo) {
        // 封装返回结果
        int picWidth = imageInfo.getWidth();
        int picHeight = imageInfo.getHeight();
        double picScale = NumberUtil.round(picWidth * 1.0 / picHeight, 2).doubleValue();
        UploadPictureResult uploadPictureResult = new UploadPictureResult();
        uploadPictureResult.setUrl(cosClientConfig.getHost() + "/" + uploadPath);
        uploadPictureResult.setPicName(FileUtil.mainName(originalFilename));
        uploadPictureResult.setPicSize(FileUtil.size(file));
        uploadPictureResult.setPicWidth(picWidth);
        uploadPictureResult.setPicHeight(picHeight);
        uploadPictureResult.setPicScale(picScale);
        uploadPictureResult.setPicFormat(imageInfo.getFormat());
        uploadPictureResult.setPicColor(ColorTransformUtils.getStandardColor(imageInfo.getAve()));
        return uploadPictureResult;
    }

    /**
     * 处理输入源(本地或 URL)，并生成临时文件
     * @param inputSource
     */
    protected abstract void processFile(Object inputSource, File file) throws Exception;

    /**
     * 获取输入源的原始文件名
     * @param inputSource
     */
    protected abstract String getOriginalFilename(Object inputSource);

    /**
     * 校验输入源(本地或 URL)
     * @param inputSource
     */
    protected abstract void validPicture(Object inputSource);

    /**
     * 删除临时文件
     * @param file
     */
    private void deleteTempFile(File file) {
        if (file == null) {
            return;
        }
        boolean delete = file.delete();
        if (!delete) {
            log.error("删除临时文件失败, 文件路径={}", file.getAbsolutePath());
        }
    }


}
