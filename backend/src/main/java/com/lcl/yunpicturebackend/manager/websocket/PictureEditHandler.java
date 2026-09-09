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
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import javax.annotation.Resource;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图片编辑处理
 */
@Component
public class PictureEditHandler extends TextWebSocketHandler {

    /**
     * WebSocket 并发发送保护参数：2 秒发送超时 / 512KB 发送缓冲上限
     */
    private static final int SEND_TIME_LIMIT_MS = 2000;
    private static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024;

    // 每张图片的编辑状态，key: pictureId, value: 当前正在编辑的用户 ID
    private final Map<Long, Long> pictureEditingUsers = new ConcurrentHashMap<>();

    // 保存所有连接的会话，key: pictureId, value: 用户会话集合
    private final Map<Long, Set<WebSocketSession>> pictureSessions = new ConcurrentHashMap<>();

    @Resource
    private IUserService userService;

    @Resource
    private PictureEditEventProducer pictureEditEventProducer;

    @Resource
    private ObjectMapper objectMapper;

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
        Long previousEditorId = pictureEditingUsers.putIfAbsent(pictureId, user.getId());
        if (previousEditorId == null) {
            // 抢占编辑权成功，广播开始编辑
            PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
            pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.ENTER_EDIT.getValue());
            String message = String.format("%s开始编辑图片", user.getUserName());
            pictureEditResponseMessage.setMessage(message);
            pictureEditResponseMessage.setUser(userService.getUserVO(user));
            broadcastToPicture(pictureId, pictureEditResponseMessage);
        } else if (!previousEditorId.equals(user.getId())) {
            // 已被其他用户占用：仅给当前请求者定向提示，不广播
            PictureEditResponseMessage busyMessage = new PictureEditResponseMessage();
            busyMessage.setType(PictureEditMessageTypeEnum.INFO.getValue());
            busyMessage.setMessage("该图片正在被其他用户编辑");
            sendToSession(pictureId, session, busyMessage);
        }
        // 同一用户重复进入编辑：忽略
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
        Long editingUserId = pictureEditingUsers.get(pictureId);
        String editAction = pictureEditRequestMessage.getEditAction();
        PictureEditActionEnum actionEnum = PictureEditActionEnum.getEnumByValue(editAction);
        if (actionEnum == null) {
            return;
        }
        // 确认是当前编辑者
        if (editingUserId != null && editingUserId.equals(user.getId())) {
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
        Long editingUserId = pictureEditingUsers.get(pictureId);
        if (editingUserId != null && editingUserId.equals(user.getId())) {
            // 移除当前用户的编辑状态
            pictureEditingUsers.remove(pictureId);
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
