package com.lcl.yunpicturebackend.manager.trash;

import com.lcl.yunpicturebackend.service.IPictureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T51：定时任务只做"取配置 + 调服务 + 兜异常"，逻辑契约就两条。 */
@DisplayName("回收站保留期清理任务")
class PictureTrashCleanupTaskTest {

    @Mock
    private IPictureService pictureService;

    @InjectMocks
    private PictureTrashCleanupTask task;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ReflectionTestUtils.setField(task, "retentionDays", 30);
    }

    @Test
    @DisplayName("按配置的保留天数与批次上限调服务")
    void delegatesWithConfiguredRetentionAndBatchLimit() {
        when(pictureService.purgeExpiredTrash(30, PictureTrashCleanupTask.BATCH_LIMIT)).thenReturn(3);

        task.purgeExpiredTrash();

        verify(pictureService).purgeExpiredTrash(30, PictureTrashCleanupTask.BATCH_LIMIT);
    }

    @Test
    @DisplayName("服务抛异常时不外溢（否则一次失败会让调度停摆）")
    void swallowsServiceFailure() {
        when(pictureService.purgeExpiredTrash(anyInt(), anyInt())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> task.purgeExpiredTrash()).doesNotThrowAnyException();
    }
}
