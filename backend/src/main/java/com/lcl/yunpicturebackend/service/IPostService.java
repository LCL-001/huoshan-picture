package com.lcl.yunpicturebackend.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.lcl.yunpicturebackend.domain.dto.post.PostAddRequest;
import com.lcl.yunpicturebackend.domain.dto.post.PostEditRequest;
import com.lcl.yunpicturebackend.domain.dto.post.PostQueryRequest;
import com.lcl.yunpicturebackend.domain.po.Post;
import com.lcl.yunpicturebackend.domain.vo.PostVO;

import javax.servlet.http.HttpServletRequest;

/**
 * 帖子服务接口
 * <p>提供帖子的发布、编辑、删除、查询等核心业务功能</p>
 */
public interface IPostService extends IService<Post> {

    /**
     * 发布帖子
     *
     * @param request 帖子添加请求对象，包含标题、内容、图片、标签等信息
     * @param userId  发布帖子的用户ID
     * @return 新发布帖子的ID
     */
    long publishPost(PostAddRequest request, Long userId);

    /**
     * 将帖子实体转换为视图对象
     *
     * @param post    帖子实体对象
     * @param request HTTP请求对象，用于获取当前用户信息等上下文数据
     * @return 转换后的帖子视图对象
     */
    PostVO getPostVO(Post post, HttpServletRequest request);

    /**
     * 分页查询帖子列表并转换为视图对象
     *
     * @param request    帖子查询请求对象，包含用户ID、关键词、分页参数等筛选条件
     * @param httpRequest HTTP请求对象，用于获取当前用户信息等上下文数据
     * @return 分页的帖子视图对象列表
     */
    Page<PostVO> getPostVOPage(PostQueryRequest request, HttpServletRequest httpRequest);

    /**
     * 根据ID查询帖子并转换为视图对象
     *
     * @param id      帖子ID
     * @param request HTTP请求对象，用于获取当前用户信息等上下文数据
     * @return 帖子视图对象
     */
    PostVO getPostVOById(Long id, HttpServletRequest request);

    /**
     * 编辑帖子
     *
     * @param request 帖子编辑请求对象，包含帖子ID及需要更新的字段信息
     * @param userId  执行编辑操作的用户ID，用于权限校验
     */
    void editPost(PostEditRequest request, Long userId);

    /**
     * 删除帖子
     *
     * @param id     要删除的帖子ID
     * @param userId 执行删除操作的用户ID，用于权限校验
     */
    void deletePost(Long id, Long userId);
}

