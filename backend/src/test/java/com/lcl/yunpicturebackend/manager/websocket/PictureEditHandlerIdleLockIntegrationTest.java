package com.lcl.yunpicturebackend.manager.websocket;

import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.manager.websocket.model.PictureEditActionEnum;
import com.lcl.yunpicturebackend.manager.websocket.model.PictureEditRequestMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编辑锁空闲兜底的验证：客户端异常断开不会触发关闭回调，只能靠空闲超时回收。
 * <p>
 * 把空闲阈值压到 400ms 来复现，避免测试里真等几分钟；扫描间隔调大，防止定时任务干扰手工触发的扫描。
 */
@SpringBootTest(properties = {
        "app.websocket.edit-lock-idle-timeout-ms=400",
        "app.websocket.edit-lock-sweep-interval-ms=600000"
})
class PictureEditHandlerIdleLockIntegrationTest {

    private static final long PICTURE_ID = 1001L;
    private static final long OTHER_PICTURE_ID = 1002L;

    @Resource
    private PictureEditHandler pictureEditHandler;

    @AfterEach
    void clearEditState() {
        pictureEditHandler.clearEditStateOnStartup();
    }

    /**
     * 空闲锁被回收，并且广播 EXIT_EDIT 让房间里其他人重新获得编辑权
     */
    @Test
    void idleLockShouldBeReleasedAndBroadcastExitEdit() throws Exception {
        User editor = user(2001L, "editor");
        User watcher = user(2002L, "watcher");
        WebSocketSession editorSession = mockSession("s-editor", editor, PICTURE_ID);
        WebSocketSession watcherSession = mockSession("s-watcher", watcher, PICTURE_ID);

        pictureEditHandler.afterConnectionEstablished(watcherSession);
        pictureEditHandler.afterConnectionEstablished(editorSession);
        pictureEditHandler.handleEnterEditMessage(null, editorSession, editor, PICTURE_ID);
        assertEquals(editor.getId(), pictureEditHandler.getEditingUserId(PICTURE_ID));

        // 未超过阈值：不能回收，否则正常编辑会被误踢
        Thread.sleep(150);
        pictureEditHandler.releaseIdleEditLocks();
        assertEquals(editor.getId(), pictureEditHandler.getEditingUserId(PICTURE_ID), "未超时不应回收编辑锁");

        // 超过阈值（400ms）后回收，并广播给房间内其它会话
        Thread.sleep(400);
        pictureEditHandler.releaseIdleEditLocks();
        assertNull(pictureEditHandler.getEditingUserId(PICTURE_ID), "超过空闲阈值应释放编辑锁");

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(watcherSession, atLeastOnce()).sendMessage(captor.capture());
        assertTrue(captor.getAllValues().stream()
                        .anyMatch(message -> message.getPayload().contains("EXIT_EDIT")),
                "释放编辑权应广播 EXIT_EDIT，实际消息：" + captor.getAllValues());
    }

    /**
     * 挂机期间有编辑动作就不算空闲：活跃时间被刷新，锁不能被回收
     */
    @Test
    void editActionShouldRefreshIdleTimer() throws Exception {
        User editor = user(2001L, "editor");
        WebSocketSession session = mockSession("s-editor-2", editor, OTHER_PICTURE_ID);

        pictureEditHandler.handleEnterEditMessage(null, session, editor, OTHER_PICTURE_ID);

        // 空闲 300ms 后产生一次编辑动作，刷新活跃时间
        Thread.sleep(300);
        PictureEditRequestMessage actionMessage = new PictureEditRequestMessage();
        actionMessage.setEditAction(PictureEditActionEnum.ROTATE_LEFT.getValue());
        pictureEditHandler.handleEditActionMessage(actionMessage, session, editor, OTHER_PICTURE_ID);

        // 再等 300ms：总时长已超过 400ms 阈值，但活跃时间被刷新过，锁应保留
        Thread.sleep(300);
        pictureEditHandler.releaseIdleEditLocks();
        assertEquals(editor.getId(), pictureEditHandler.getEditingUserId(OTHER_PICTURE_ID),
                "有编辑动作应刷新活跃时间，锁不应被回收");
    }

    private User user(long id, String name) {
        User user = new User();
        user.setId(id);
        user.setUserName(name);
        return user;
    }

    private WebSocketSession mockSession(String sessionId, User user, long pictureId) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("user", user);
        attributes.put("pictureId", pictureId);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getId()).thenReturn(sessionId);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
