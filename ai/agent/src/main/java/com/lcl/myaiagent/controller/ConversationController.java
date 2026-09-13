package com.lcl.myaiagent.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lcl.myaiagent.common.*;
import com.lcl.myaiagent.constant.UserConstant;
import com.lcl.myaiagent.model.po.ChatMessage;
import com.lcl.myaiagent.model.po.User;
import com.lcl.myaiagent.model.vo.ChatMessageVO;
import com.lcl.myaiagent.model.vo.ConversationVO;
import com.lcl.myaiagent.service.ChatMessageService;
import com.lcl.myaiagent.service.ConversationService;
import com.lcl.myaiagent.utils.ChatHistoryAssembler;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.Data;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/conversations")
public class ConversationController {

    @Resource
    private ConversationService conversationService;

    @Resource
    private ChatMessageService chatMessageService;

    /**
     * 从 session 获取当前登录用户
     */
    private User getLoginUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        return (User) session.getAttribute(UserConstant.USER_LOGIN_STATE);
    }

    /**
     * 分页列出当前用户的会话
     */
    @GetMapping
    public BaseResponse<Page<ConversationVO>> listConversations(PageRequest pageRequest,
                                                                  HttpServletRequest request) {
        User loginUser = getLoginUser(request);
        if (loginUser == null) {
            return ResultUtils.error(ErrorCode.NOT_LOGIN_ERROR);
        }
        return ResultUtils.success(
                conversationService.listUserConversations(loginUser.getId(), pageRequest));
    }

    /**
     * 创建新会话（需登录）
     */
    @PostMapping
    public BaseResponse<ConversationVO> createConversation(@RequestBody(required = false) CreateConversationRequest req,
                                                            HttpServletRequest request) {
        User loginUser = getLoginUser(request);
        if (loginUser == null) {
            return ResultUtils.error(ErrorCode.NOT_LOGIN_ERROR);
        }
        String type = (req != null && req.getType() != null) ? req.getType() : "manus";
        String conversationId = UUID.randomUUID().toString();
        var conv = conversationService.getOrCreate(conversationId, loginUser.getId(), type);
        ConversationVO vo = new ConversationVO();
        vo.setId(conv.getId());
        vo.setType(conv.getType());
        vo.setTitle(conv.getTitle());
        vo.setCreateTime(conv.getCreateTime());
        vo.setUpdateTime(conv.getUpdateTime());
        vo.setMessageCount(0);
        return ResultUtils.success(vo);
    }

    /**
     * 更新会话标题（仅归属用户可操作）
     */
    @PutMapping("/{conversationId}/title")
    public BaseResponse<?> updateTitle(@PathVariable String conversationId,
                                        @RequestBody UpdateTitleRequest req,
                                        HttpServletRequest request) {
        User loginUser = getLoginUser(request);
        if (loginUser == null) {
            return ResultUtils.error(ErrorCode.NOT_LOGIN_ERROR);
        }
        var conv = conversationService.updateTitle(conversationId, loginUser.getId(), req.getTitle());
        if (conv == null) {
            return ResultUtils.error(ErrorCode.NOT_FOUND_ERROR, "会话不存在或无权操作");
        }
        return ResultUtils.success(null);
    }

    /**
     * 删除会话（仅归属用户可操作）
     */
    @DeleteMapping("/{conversationId}")
    public BaseResponse<?> deleteConversation(@PathVariable String conversationId,
                                               HttpServletRequest request) {
        User loginUser = getLoginUser(request);
        if (loginUser == null) {
            return ResultUtils.error(ErrorCode.NOT_LOGIN_ERROR);
        }
        boolean ok = conversationService.deleteConversation(conversationId, loginUser.getId());
        if (!ok) {
            return ResultUtils.error(ErrorCode.NOT_FOUND_ERROR, "会话不存在或无权操作");
        }
        return ResultUtils.success(null);
    }

    /**
     * 获取会话的所有消息
     */
    @GetMapping("/{conversationId}/messages")
    public BaseResponse<List<ChatMessageVO>> getMessages(@PathVariable String conversationId, HttpServletRequest request) {
        User loginUser = getLoginUser(request);
        if (loginUser == null) {
            return ResultUtils.error(ErrorCode.NOT_LOGIN_ERROR);
        }
        if (!conversationService.belongsToUser(conversationId, loginUser.getId())) {
            return ResultUtils.error(ErrorCode.NOT_FOUND_ERROR, "会话不存在或无权访问");
        }
        // 按 id 排序 + 组装成"轮"结构（内部提示/工具步骤归组、askHuman 还原），见 ChatHistoryAssembler
        List<ChatMessage> messages = chatMessageService.lambdaQuery()
                .eq(ChatMessage::getConversationId, conversationId)
                .orderByAsc(ChatMessage::getId)
                .list();
        return ResultUtils.success(ChatHistoryAssembler.assemble(messages));
    }

    @Data
    static class CreateConversationRequest {
        private String type;
    }

    @Data
    static class UpdateTitleRequest {
        private String title;
    }
}
