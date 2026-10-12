package com.lcl.yunpicturebackend.manager.trash;

import com.lcl.yunpicturebackend.service.IPictureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 回收站保留期清理（T51）：定时把超过保留天数的回收站记录彻底删掉（物理删行 + 引用计数为 0 才清 COS）。
 * <p>
 * 复用仓内既有的定时任务形态（{@code PictureEditHandler.releaseIdleEditLocks}：{@code @Scheduled(fixedDelayString=...)}
 * + 自己吞异常）。默认每小时扫一次、启动后 1 分钟首次扫；口径见 {@code docs/features/F17-回收站.md}。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PictureTrashCleanupTask {

    /** 单轮最多处理条数：分批，避免一次把大批过期行全拉进内存 */
    static final int BATCH_LIMIT = 200;

    private final IPictureService pictureService;

    @Value("${app.trash.retention-days:30}")
    private int retentionDays;

    @Scheduled(fixedDelayString = "${app.trash.cleanup-interval-ms:3600000}",
            initialDelayString = "${app.trash.cleanup-initial-delay-ms:60000}")
    public void purgeExpiredTrash() {
        try {
            int purged = pictureService.purgeExpiredTrash(retentionDays, BATCH_LIMIT);
            if (purged > 0) {
                log.info("回收站保留期清理：彻底删除 {} 条超过 {} 天的记录", purged, retentionDays);
            }
        } catch (Exception e) {
            // 定时任务吞掉异常，避免一次失败后调度器停摆；下一轮会再试
            log.warn("回收站保留期清理失败", e);
        }
    }
}
