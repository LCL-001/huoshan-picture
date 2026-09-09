package com.lcl.yunpicturebackend.service;

import cn.hutool.core.util.StrUtil;
import com.lcl.yunpicturebackend.config.CosClientConfig;
import com.lcl.yunpicturebackend.domain.po.Picture;
import com.lcl.yunpicturebackend.manager.CosManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 图片文件清理服务
 * <p>
 * 独立成 Bean 以保证 @Async 经过代理生效，避免从其它 Service 内部自调用时
 * 异步失效、COS 删除阻塞请求线程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PictureFileCleanupService {

    private final CosManager cosManager;

    private final CosClientConfig cosClientConfig;

    /**
     * 异步清理 COS 中的图片资源（原图 + 缩略图）
     *
     * @param oldPicture 待清理的图片记录
     */
    @Async
    public void clearPictureFile(Picture oldPicture) {
        try {
            String host = cosClientConfig.getHost();
            String url = oldPicture.getUrl();
            if (StrUtil.isNotBlank(url) && url.startsWith(host)) {
                cosManager.deleteObject(url.substring(host.length()));
            }
            // 清理缩略图
            String thumbnailUrl = oldPicture.getThumbnailUrl();
            if (StrUtil.isNotBlank(thumbnailUrl) && thumbnailUrl.startsWith(host)) {
                cosManager.deleteObject(thumbnailUrl.substring(host.length()));
            }
        } catch (Exception e) {
            // 清理失败不影响主流程，仅记录日志便于补偿
            log.error("清理 COS 图片文件失败, url: {}", oldPicture.getUrl(), e);
        }
    }
}
