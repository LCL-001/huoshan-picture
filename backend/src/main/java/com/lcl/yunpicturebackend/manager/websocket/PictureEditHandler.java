package com.lcl.yunpicturebackend.manager.websocket;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.manager.websocket.disruptor.PictureEditEventProducer;
import com.lcl.yunpicturebackend.manager.websocket.model.PictureEditActionEnum;
import com.lcl.yunpicturebackend.manager.websocket.model.PictureEditMessageTypeEnum;
import com.lcl.yunpicturebackend.manager.websocket.model.PictureEditRequestMessage;
import com.lcl.yunpicturebackend.manager.websocket.model.PictureEditResponseMessage;
import com.lcl.yunpicturebackend.service.IUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图片编辑处理
 */
@Slf4j
@Component
public class PictureEditHandler extends TextWebSocketHandler {

    /**
     * WebSocket 并发发送保护参数：2 秒发送超时 / 512KB 发送缓冲上限
     */
    private static final int SEND_TIME_LIMIT_MS = 2000;
    private static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024;

    // 每张图片的编辑状态，key: pictureId, value: 当前编辑者与最近活跃时间
    private final Map<Long, EditLock> pictureEditLocks = new ConcurrentHashMap<>();

    // 保存所有连接的会话，key: pictureId, value: 用户会话集合
    private final Map<Long, Set<WebSocketSession>> pictureSessions = new ConcurrentHashMap<>();

    /**
     * 编辑锁空闲多久后强制释放（毫秒）。默认 5 分钟。
     * <p>
     * 客户端异常断开（如直接杀进程）不会触发 afterConnectionClosed，锁会一直留着，
     * 其他人再也进不了编辑态；用空闲超时给锁一个上界，代价是长时间挂机的编辑者会被回收后重进。
     */
    @Value("${app.websocket.edit-lock-idle-timeout-ms:300000}")
    private long editLockIdleTimeoutMs;

    @Resource
    private IUserService userService;

    @Resource
    private PictureEditEventProducer pictureEditEventProducer;

    @Resource
    private ObjectMapper objectMapper;

    /**
     * 应用启动时清空编辑态。编辑锁是进程内存态，重启即空；
     * 这里显式清一次，避免容器热重载或测试复用同一实例时残留上一轮的锁。
     */
    @PostConstruct
    public void clearEditStateOnStartup() {
        pictureEditLocks.clear();
        pictureSessions.clear();
    }

    /**
     * 编辑锁：记录持有者与最近活跃时间，供空闲超时回收判断
     */
    private static class EditLock {

        private final Long userId;

        /**
         * 最近活跃时间（收到进入编辑/编辑动作消息时刷新），volatile 保证扫描线程能看到最新值
         */
        private volatile long lastActiveAt;

        EditLock(Long userId, long lastActiveAt) {
            this.userId = userId;
            this.lastActiveAt = lastActiveAt;
        }

        Long getUserId() {
            return userId;
        }

        long getLastActiveAt() {
            return lastActiveAt;
        }

        void touch() {
            this.lastActiveAt = System.currentTimeMillis();
        }
    }

    /**
     * 定时回收空闲超时的编辑锁：兜底"客户端异常断开且关闭回调未触发"导致的锁泄漏。
     * <p>
     * 移除用的是 ConcurrentHashMap 的条件删除：只有仍是同一把锁时才移除，
     * 避免与同时刷新了活跃时间的请求竞态，把刚活跃的编辑者误踢出去。
     */
    @Scheduled(fixedDelayString = "${app.websocket.edit-lock-sweep-interval-ms:60000}")
    public void releaseIdleEditLocks() {
        long now = System.currentTimeMillis();
        pictureEditLocks.forEach((pictureId, lock) -> {
            long idleMs = now - lock.getLastActiveAt();
            if (idleMs <= editLockIdleTimeoutMs) {
                return;
            }
            if (pictureEditLocks.remove(pictureId, lock)) {
                log.warn("[ws-edit-lock] 编辑锁空闲 {}ms 超过阈值 {}ms，强制释放, pictureId={}, userId={}",
                        idleMs, editLockIdleTimeoutMs, pictureId, lock.getUserId());
                broadcastEditLockReleased(pictureId);
            }
        });
    }

    /**
     * 广播"编辑权已被自动释放"。取不到 User 对象就不带用户信息，
     * 前端只依赖 type + message（见 ImageCropper.vue 的 EXIT_EDIT 处理）
     */
    private void broadcastEditLockReleased(Long pictureId) {
        PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
        pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.EXIT_EDIT.getValue());
        pictureEditResponseMessage.setMessage("编辑者长时间无操作，编辑权已自动释放，可重新进入编辑");
        try {
            broadcastToPicture(pictureId, pictureEditResponseMessage);
        } catch (Exception e) {
            // 定时任务里不能把异常抛出去，否则后续扫描会被调度器跳过
            log.error("[ws-edit-lock] 广播编辑权释放失败, pictureId={}", pictureId, e);
        }
    }

    /**
     * 当前编辑者（无锁返回 null）。供测试观测锁状态。
     */
    Long getEditingUserId(Long pictureId) {
        EditLock lock = pictureEditLocks.get(pictureId);
        return lock == null ? null : lock.getUserId();
    }

    /**
     * 接收客户端消息
     * @param session
     * @param message
     * @throws Exception
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 将消息解析为 PictureEditMessage
        PictureEditRequestMessage pictureEditRequestMessage = JSONUtil.toBean(message.getPayload(), PictureEditRequestMessage.class);
        // 从 Session 属性中获取公共参数
        Map<String, Object> attributes = session.getAttributes();
        User user = (User) attributes.get("user");
        Long pictureId = (Long) attributes.get("pictureId");
        // 生产消息
        pictureEditEventProducer.publishEvent(pictureEditRequestMessage, session, user, pictureId);
    }

    /**
     * 连接建立成功时触发
     * @param session
     * @throws Exception
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // 保存会话到集合中（使用并发安全装饰器包装：Disruptor 消费线程与其它广播线程并发发送时串行化，
        // 避免 WebSocket 底层 TEXT_PARTIAL_WRITING 异常）
        User user = (User) session.getAttributes().get("user");
        Long pictureId = (Long) session.getAttributes().get("pictureId");
        pictureSessions.putIfAbsent(pictureId, ConcurrentHashMap.newKeySet());
        pictureSessions.get(pictureId).add(new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE_LIMIT));

        // 构造响应
        PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
        pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.INFO.getValue());
        String message = String.format("%s加入编辑", user.getUserName());
        pictureEditResponseMessage.setMessage(message);
        pictureEditResponseMessage.setUser(userService.getUserVO(user));
        // 广播给同一张图片的用户
        broadcastToPicture(pictureId, pictureEditResponseMessage);
    }

    /**
     * 处理进入编辑
     * @param pictureEditRequestMessage
     * @param session
     * @param user
     * @param pictureId
     * @throws Exception
     */
    public void handleEnterEditMessage(PictureEditRequestMessage pictureEditRequestMessage, WebSocketSession session, User user, Long pictureId) throws Exception {
        // putIfAbsent 基于 CAS 原子占位，避免 containsKey + put 的 check-then-act 竞态
        EditLock previousLock = pictureEditLocks.putIfAbsent(pictureId,
                new EditLock(user.getId(), System.currentTimeMillis()));
        if (previousLock == null) {
            // 抢占编辑权成功，广播开始编辑
            PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
            pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.ENTER_EDIT.getValue());
            String message = String.format("%s开始编辑图片", user.getUserName());
            pictureEditResponseMessage.setMessage(message);
            pictureEditResponseMessage.setUser(userService.getUserVO(user));
            broadcastToPicture(pictureId, pictureEditResponseMessage);
        } else if (!previousLock.getUserId().equals(user.getId())) {
            // 已被其他用户占用：仅给当前请求者定向提示，不广播
            PictureEditResponseMessage busyMessage = new PictureEditResponseMessage();
            busyMessage.setType(PictureEditMessageTypeEnum.INFO.getValue());
            busyMessage.setMessage("该图片正在被其他用户编辑");
            sendToSession(pictureId, session, busyMessage);
        } else {
            // 同一用户重复进入编辑：忽略，但要刷新活跃时间，避免被判为空闲后误回收
            previousLock.touch();
        }
    }

    /**
     * 向单个会话发送消息（优先使用集合中已包装的并发安全装饰器，避免与其它广播线程并发写同一连接）
     * @param pictureId
     * @param session
     * @param message
     * @throws Exception
     */
    private void sendToSession(Long pictureId, WebSocketSession session, PictureEditResponseMessage message) throws Exception {
        if (session == null) {
            return;
        }
        WebSocketSession target = session;
        Set<WebSocketSession> sessionSet = pictureSessions.get(pictureId);
        if (sessionSet != null) {
            target = sessionSet.stream()
                    .filter(stored -> stored.getId().equals(session.getId()))
                    .findFirst()
                    .orElse(session);
        }
        if (target.isOpen()) {
            target.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        }
    }

    /**
     * 处理编辑动作
     * @param pictureEditRequestMessage
     * @param session
     * @param user
     * @param pictureId
     * @throws Exception
     */
    public void handleEditActionMessage(PictureEditRequestMessage pictureEditRequestMessage, WebSocketSession session, User user, Long pictureId) throws Exception {
        EditLock editLock = pictureEditLocks.get(pictureId);
        String editAction = pictureEditRequestMessage.getEditAction();
        PictureEditActionEnum actionEnum = PictureEditActionEnum.getEnumByValue(editAction);
        if (actionEnum == null) {
            return;
        }
        // 确认是当前编辑者
        if (editLock != null && editLock.getUserId().equals(user.getId())) {
            // 有编辑动作说明用户还活跃，刷新时间戳，避免执行中的编辑被空闲回收
            editLock.touch();
            PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
            pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.EDIT_ACTION.getValue());
            String message = String.format("%s执行%s", user.getUserName(), actionEnum.getText());
            pictureEditResponseMessage.setMessage(message);
            pictureEditResponseMessage.setEditAction(editAction);
            pictureEditResponseMessage.setUser(userService.getUserVO(user));
            // 广播给除了当前客户端之外的其他用户，否则会造成重复编辑
            broadcastToPicture(pictureId, pictureEditResponseMessage, session);
        }
    }

    /**
     * 处理退出编辑
     * @param pictureEditRequestMessage
     * @param session
     * @param user
     * @param pictureId
     * @throws Exception
     */
    public void handleExitEditMessage(PictureEditRequestMessage pictureEditRequestMessage, WebSocketSession session, User user, Long pictureId) throws Exception {
        EditLock editLock = pictureEditLocks.get(pictureId);
        if (editLock != null && editLock.getUserId().equals(user.getId())) {
            // 条件删除：只有仍是同一把锁时才移除，避免把并发抢到锁的新编辑者误删
            if (!pictureEditLocks.remove(pictureId, editLock)) {
                return;
            }
            // 构造响应，发送退出编辑的消息通知
            PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
            pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.EXIT_EDIT.getValue());
            String message = String.format("%s退出编辑图片", user.getUserName());
            pictureEditResponseMessage.setMessage(message);
            pictureEditResponseMessage.setUser(userService.getUserVO(user));
            broadcastToPicture(pictureId, pictureEditResponseMessage);
        }
    }

    /**
     * 连接关闭时触发
     * @param session
     * @param status
     * @throws Exception
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, @NonNull CloseStatus status) throws Exception {
        Map<String, Object> attributes = session.getAttributes();
        Long pictureId = (Long) attributes.get("pictureId");
        User user = (User) attributes.get("user");
        // 移除当前用户的编辑状态
        handleExitEditMessage(null, session, user, pictureId);

        // 删除会话（集合中保存的是装饰器，按 sessionId 匹配移除）
        Set<WebSocketSession> sessionSet = pictureSessions.get(pictureId);
        if (sessionSet != null) {
            sessionSet.removeIf(stored -> stored.getId().equals(session.getId()));
            if (sessionSet.isEmpty()) {
                pictureSessions.remove(pictureId);
            }
        }

        // 响应
        PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
        pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.INFO.getValue());
        String message = String.format("%s离开编辑", user.getUserName());
        pictureEditResponseMessage.setMessage(message);
        pictureEditResponseMessage.setUser(userService.getUserVO(user));
        broadcastToPicture(pictureId, pictureEditResponseMessage);
    }

    /**
     * 广播图片编辑信息
     * @param pictureId
     * @param pictureEditResponseMessage
     * @param excludeSession
     * @throws Exception
     */
    private void broadcastToPicture(Long pictureId, PictureEditResponseMessage pictureEditResponseMessage, WebSocketSession excludeSession) throws Exception {
        Set<WebSocketSession> sessionSet = pictureSessions.get(pictureId);
        if (CollUtil.isNotEmpty(sessionSet)) {
            // 复用全局 ObjectMapper（JsonConfig 已配置 Long → String 序列化，解决精度丢失）
            String message = objectMapper.writeValueAsString(pictureEditResponseMessage);
            TextMessage textMessage = new TextMessage(message);
            for (WebSocketSession session : sessionSet) {
                // 排除掉的 session 不发送（集合中保存的是装饰器，按 sessionId 匹配）
                if (excludeSession != null && excludeSession.getId().equals(session.getId())) {
                    continue;
                }
                if (session.isOpen()) {
                    session.sendMessage(textMessage);
                }
            }
        }
    }

    // 全部广播
    private void broadcastToPicture(Long pictureId, PictureEditResponseMessage pictureEditResponseMessage) throws Exception {
        broadcastToPicture(pictureId, pictureEditResponseMessage, null);
    }

}
