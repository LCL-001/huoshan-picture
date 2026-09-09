package com.lcl.yunpicturebackend.manager.websocket.disruptor;

import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.manager.websocket.model.PictureEditRequestMessage;
import lombok.Data;
import org.springframework.web.socket.WebSocketSession;

/**
 * 事件定义
 */
@Data
public class PictureEditEvent {

    /**
     * 消息
     */
    private PictureEditRequestMessage pictureEditRequestMessage;

    /**
     * 当前用户的 session
     */
    private WebSocketSession session;
    
    /**
     * 当前用户
     */
    private User user;

    /**
     * 图片 id
     */
    private Long pictureId;

}
