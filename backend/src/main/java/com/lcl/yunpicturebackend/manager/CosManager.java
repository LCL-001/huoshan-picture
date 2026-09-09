package com.lcl.yunpicturebackend.manager;

import cn.hutool.core.io.FileUtil;
import com.lcl.yunpicturebackend.config.CosClientConfig;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.model.*;
import com.qcloud.cos.model.ciModel.persistence.PicOperations;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.ArrayList;

@Component
public class CosManager {  
  
    @Resource
    private CosClientConfig cosClientConfig;
  
    @Resource  
    private COSClient cosClient;
  
    // ... 一些操作 COS 的方法
    /**
     * 上传对象
     *
     * @param key  唯一键
     * @param file 文件
     */
    public PutObjectResult putObject(String key, File file) {
        PutObjectRequest putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(), key,
                file);
        return cosClient.putObject(putObjectRequest);
    }

    /**
     * 下载对象
     *
     * @param key 唯一键
     */
    public COSObject getObject(String key) {
        GetObjectRequest getObjectRequest = new GetObjectRequest(cosClientConfig.getBucket(), key);
        return cosClient.getObject(getObjectRequest);
    }

    /**
     * 删除对象
     *
     * @param key 文件 key
     */
    public void deleteObject(String key) throws CosClientException {
        cosClient.deleteObject(cosClientConfig.getBucket(), key);
    }


    /**
     * 上传对象（附带图片信息）
     *
     * @param key  唯一键
     * @param file 文件
     */
    public PutObjectResult putPictureObject(String key, File file) {
        PutObjectRequest putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(), key, file);
        // 对图片进行处理（获取基本信息也被视作为一种处理）
        PicOperations picOperations = new PicOperations();
        // 1 表示返回原图信息
        picOperations.setIsPicInfo(1);
        ArrayList<PicOperations.Rule> rules = new ArrayList<>();
        // 图片压缩（转成 webp 格式）
        String webpKey = FileUtil.mainName(key) + ".webp";// 生成 webp 文件名
        PicOperations.Rule compressRule = new PicOperations.Rule();
        compressRule.setFileId(webpKey);// 设置处理后的文件名
        compressRule.setRule("imageMogr2/format/webp");// 设置图片处理参数
        compressRule.setBucket(cosClientConfig.getBucket());// 设置处理后的文件存储桶
        rules.add(compressRule);// 添加图片处理规则
        // 缩略图处理, 如果图片大小大于 20 KB，则生成缩略图
        if (file.length() > 20 * 1024) {
            PicOperations.Rule thumbnailRule = new PicOperations.Rule();
            String thumbnailKey = String.format(FileUtil.mainName(key) + "_thumbnail." + FileUtil.getSuffix(key));
            thumbnailRule.setFileId(thumbnailKey);// 设置处理后的文件名
            // 缩略规则 imageMogr2/thumbnail/<Width>x<Height>> (如果大于原图宽高，则不处理)
            thumbnailRule.setRule(String.format("imageMogr2/thumbnail/%sx%s>", 256, 256));// 设置图片处理参数
            thumbnailRule.setBucket(cosClientConfig.getBucket());// 设置处理后的文件存储桶
            rules.add(thumbnailRule);// 添加图片处理规则
        }
        // 构造处理参数
        picOperations.setRules(rules);
        putObjectRequest.setPicOperations(picOperations);
        return cosClient.putObject(putObjectRequest);
    }

}
