package com.lcl.yunpicturebackend.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lcl.yunpicturebackend.annotation.AuthCheck;
import com.lcl.yunpicturebackend.api.aliyunai.model.CreateOutPaintingTaskResponse;
import com.lcl.yunpicturebackend.api.aliyunai.model.GetOutPaintingTaskResponse;
import com.lcl.yunpicturebackend.api.imagesearch.ImageSearchApiFacade;
import com.lcl.yunpicturebackend.api.imagesearch.model.ImageSearchResult;
import com.lcl.yunpicturebackend.common.BaseResponse;
import com.lcl.yunpicturebackend.common.DeleteRequest;
import com.lcl.yunpicturebackend.common.ResultUtils;
import com.lcl.yunpicturebackend.constant.UserConstant;
import com.lcl.yunpicturebackend.domain.dto.picture.*;
import com.lcl.yunpicturebackend.domain.po.Picture;
import com.lcl.yunpicturebackend.domain.po.Space;
import com.lcl.yunpicturebackend.domain.vo.PictureTagCategory;
import com.lcl.yunpicturebackend.domain.po.User;
import com.lcl.yunpicturebackend.domain.vo.PictureVO;
import com.lcl.yunpicturebackend.domain.vo.SpaceLevel;
import com.lcl.yunpicturebackend.enums.SpaceLevelEnum;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.exception.ThrowUtils;
import com.lcl.yunpicturebackend.manager.auth.SpaceUserAuthManager;
import com.lcl.yunpicturebackend.manager.auth.StpKit;
import com.lcl.yunpicturebackend.manager.auth.annotation.SaSpaceCheckPermission;
import com.lcl.yunpicturebackend.manager.auth.model.SpaceUserPermissionConstant;
import com.lcl.yunpicturebackend.service.IPictureService;
import com.lcl.yunpicturebackend.service.ISpaceService;
import com.lcl.yunpicturebackend.service.IUserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 * 图片 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-04-18
 */
@Api(tags = "图片相关接口")
@RestController
@RequestMapping("/picture")
@RequiredArgsConstructor
public class PictureController {

    private final IPictureService pictureService;
    private final IUserService userService;
    private final ISpaceService spaceService;
    private final SpaceUserAuthManager spaceUserAuthManager;

    @ApiOperation("审核图片")
    @PostMapping("/review")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> reviewPicture(@RequestBody PictureReviewRequest pictureReviewRequest,
                                               HttpServletRequest request) {
        ThrowUtils.throwIf(pictureReviewRequest == null, ErrorCode.PARAMS_ERROR);
        // 获取登录用户
        User loginUser = userService.getLoginUser(request);
        pictureService.doPictureReview(pictureReviewRequest, loginUser);
        return BaseResponse.success(true);
    }

    /**
     * 上传图片
     *
     * @return
     */
    @ApiOperation("上传图片")
    @PostMapping("/upload")
    @SaSpaceCheckPermission(value = SpaceUserPermissionConstant.PICTURE_UPLOAD)
    public BaseResponse<PictureVO> uploadPicture(
            @RequestPart("file") MultipartFile multipartFile,
            PictureUploadRequest pictureUploadRequest,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        PictureVO pictureVO = pictureService.uploadPicture(multipartFile, pictureUploadRequest, loginUser);
        return BaseResponse.success(pictureVO);
    }

    /**
     * 上传图片
     *
     * @return
     */
    @ApiOperation("批量抓取上传图片")
    @PostMapping("/upload/batch")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Integer> uploadPictureByBatch(
            @RequestBody PictureUploadByBatchRequest pictureUploadByBatchRequest,
            HttpServletRequest request) {
        ThrowUtils.throwIf(pictureUploadByBatchRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        int uploadCount = pictureService.uploadPictureByBatch(pictureUploadByBatchRequest, loginUser);
        return BaseResponse.success(uploadCount);
    }


    /**
     * 上传图片
     *
     * @return
     */
    @ApiOperation("上传图片")
    @PostMapping("/upload/url")
    @SaSpaceCheckPermission(value = SpaceUserPermissionConstant.PICTURE_UPLOAD)
    public BaseResponse<PictureVO> uploadPicture(
            @RequestBody PictureUploadRequest pictureUploadRequest,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        String fileUrl = pictureUploadRequest.getFileUrl();
        PictureVO pictureVO = pictureService.uploadPicture(fileUrl, pictureUploadRequest, loginUser);
        return BaseResponse.success(pictureVO);
    }

    /**
     * 删除图片
     */
    @ApiOperation("删除图片")
    @PostMapping("/delete")
    @SaSpaceCheckPermission(value = SpaceUserPermissionConstant.PICTURE_DELETE)
    public BaseResponse<Boolean> deletePicture(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        pictureService.deletePicture(deleteRequest, request);
        return ResultUtils.success(true);
    }

    /**
     * 更新图片（仅管理员可用）
     */
    @ApiOperation("更新图片")
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updatePicture(@RequestBody PictureUpdateRequest pictureUpdateRequest,
                                               HttpServletRequest request) {
        pictureService.updatePicture(pictureUpdateRequest, request);
        return ResultUtils.success(true);
    }

    /**
     * 根据 id 获取图片（仅管理员可用）
     */
    @ApiOperation("根据 id 获取图片")
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Picture> getPictureById(long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        // 查询数据库
        Picture picture = pictureService.getById(id);
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR);
        // 获取封装类
        return ResultUtils.success(picture);
    }

    /**
     * 根据 id 获取图片（封装类）
     */
    @ApiOperation("根据 id 获取图片（封装类）")
    @GetMapping("/get/vo")
    public BaseResponse<PictureVO> getPictureVOById(long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        // 查询数据库
        Picture picture = pictureService.getById(id);
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR);
        // 空间权限校验
        Long spaceId = picture.getSpaceId();
        Space space = null;
        if (spaceId != null) {
//            User loginUser = userService.getLoginUser(request);
//            pictureService.checkPictureAuth(loginUser, picture);
            boolean hasPermission = StpKit.SPACE.hasPermission(SpaceUserPermissionConstant.PICTURE_VIEW);
            ThrowUtils.throwIf(!hasPermission, ErrorCode.NO_AUTH_ERROR);
            space = spaceService.getById(spaceId);
            ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        }
        // 获取权限列表
        User loginUser = userService.getLoginUser(request);
        List<String> permissionList = spaceUserAuthManager.getPermissionList(space, loginUser);
        PictureVO pictureVO = pictureService.getPictureVO(picture, request);
        pictureVO.setPermissionList(permissionList);

        // 获取封装类
        return ResultUtils.success(pictureVO);
    }

    /**
     * 分页获取图片列表（仅管理员可用）
     */
    @ApiOperation("分页获取图片列表")
    @PostMapping("/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<Picture>> listPictureByPage(@RequestBody PictureQueryRequest pictureQueryRequest) {
        long current = pictureQueryRequest.getCurrent();
        long size = pictureQueryRequest.getPageSize();
        // 查询数据库
        Page<Picture> picturePage = pictureService.page(new Page<>(current, size),
                pictureService.getQueryWrapper(pictureQueryRequest));
        return ResultUtils.success(picturePage);
    }

    /**
     * 分页获取图片列表（封装类）
     */
    @ApiOperation("分页获取图片列表（封装类）")
    @PostMapping("/list/page/vo")
    public BaseResponse<Page<PictureVO>> listPictureVOByPage(@RequestBody PictureQueryRequest pictureQueryRequest,
                                                             HttpServletRequest request) {
        Page<Picture> picturePage = pictureService.listPictureVOByPage(pictureQueryRequest, request);
        // 获取封装类
        return ResultUtils.success(pictureService.getPictureVOPage(picturePage, request));
    }

    /**
     * 分页获取图片列表（封装类），通过缓存
     */
    @ApiOperation("分页获取图片列表（封装类），通过缓存")
    @PostMapping("/list/page/vo/cache")
//    @Deprecated
    public BaseResponse<Page<PictureVO>> listPictureVOByPageByCache(@RequestBody PictureQueryRequest pictureQueryRequest,
                                                                    HttpServletRequest request) {
        Page<PictureVO> pictureVOPage = pictureService.listPictureVOByPageByCache(pictureQueryRequest, request);
        // 返回结果
        return ResultUtils.success(pictureVOPage);
    }

    /**
     * 编辑图片（给用户使用）
     */
    @ApiOperation("编辑图片")
    @PostMapping("/edit")
    @SaSpaceCheckPermission(value = SpaceUserPermissionConstant.PICTURE_EDIT)
    public BaseResponse<Boolean> editPicture(@RequestBody PictureEditRequest pictureEditRequest, HttpServletRequest request) {
        pictureService.editPicture(pictureEditRequest, request);
        return ResultUtils.success(true);
    }

    /**
     * 获取图片标签和分类
     */
    @ApiOperation("获取图片标签和分类")
    @GetMapping("/tag_category")
    public BaseResponse<PictureTagCategory> listPictureTagCategory() {
        PictureTagCategory pictureTagCategory = new PictureTagCategory();
        List<String> tagList = Arrays.asList("热门", "搞笑", "生活", "高清", "艺术", "校园", "背景", "简历", "创意");
        List<String> categoryList = Arrays.asList("模板", "电商", "表情包", "素材", "海报");
        pictureTagCategory.setTagList(tagList);
        pictureTagCategory.setCategoryList(categoryList);
        return ResultUtils.success(pictureTagCategory);
    }

    /**
     * 将枚举转换为空间级别对象
     */
    @ApiOperation("将枚举转换为空间级别对象")
    @GetMapping("/list/level")
    public BaseResponse<List<SpaceLevel>> listSpaceLevel() {
        List<SpaceLevel> spaceLevelList = Arrays.stream(SpaceLevelEnum.values()) // 获取所有枚举
                .map(spaceLevelEnum -> new SpaceLevel(
                        spaceLevelEnum.getValue(),
                        spaceLevelEnum.getText(),
                        spaceLevelEnum.getMaxCount(),
                        spaceLevelEnum.getMaxSize()))
                .collect(Collectors.toList());
        return ResultUtils.success(spaceLevelList);
    }

    /**
     * 以图搜图
     */
    @ApiOperation("以图搜图")
    @PostMapping("/search/picture")
    public BaseResponse<List<ImageSearchResult>> searchPictureByPicture(@RequestBody SearchPictureByPictureRequest searchPictureByPictureRequest,
                                                                        HttpServletRequest request) {
        // 1. 登录校验（接口会调用外部识图 API，防止被匿名刷量）
        userService.getLoginUser(request);
        // 2. 参数校验
        ThrowUtils.throwIf(searchPictureByPictureRequest == null, ErrorCode.PARAMS_ERROR);
        Long pictureId = searchPictureByPictureRequest.getPictureId();
        ThrowUtils.throwIf(pictureId == null || pictureId <= 0, ErrorCode.PARAMS_ERROR);
        // 3. 从数据库获取原图片信息
        Picture oldPicture = pictureService.getById(pictureId);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);
        // 4. 调用百度识图API进行搜索
        List<ImageSearchResult> resultList = ImageSearchApiFacade.searchImage(oldPicture.getUrl());
        // 5. 返回相似图片列表
        return ResultUtils.success(resultList);
    }

    /**
     * 以图片主色调搜图
     */
    @ApiOperation("以图片主色调搜图")
    @PostMapping("/search/color")
    @SaSpaceCheckPermission(value = SpaceUserPermissionConstant.PICTURE_VIEW)
    public BaseResponse<List<PictureVO>> searchPictureByColor(@RequestBody SearchPictureByColorRequest searchPictureByColorRequest,
                                                              HttpServletRequest request) {
        ThrowUtils.throwIf(searchPictureByColorRequest == null, ErrorCode.PARAMS_ERROR);
        String picColor = searchPictureByColorRequest.getPicColor();
        Long spaceId = searchPictureByColorRequest.getSpaceId();
        User loginUser = userService.getLoginUser(request);
        List<PictureVO> result = pictureService.searchPictureByColor(spaceId, picColor, loginUser);
        return ResultUtils.success(result);
    }

    /**
     * 批量编辑图片（给用户使用）
     */
    @ApiOperation("批量编辑图片")
    @PostMapping("/edit/batch")
    @SaSpaceCheckPermission(value = SpaceUserPermissionConstant.PICTURE_EDIT)
    public BaseResponse<Boolean> editPictureByBatch(@RequestBody PictureEditByBatchRequest pictureEditByBatchRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(pictureEditByBatchRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        pictureService.editPictureByBatch(pictureEditByBatchRequest, loginUser);
        return ResultUtils.success(true);
    }

    /**
     * 创建 AI 扩图任务
     */
    @ApiOperation("创建 AI 扩图任务")
    @PostMapping("/out_painting/create_task")
    @SaSpaceCheckPermission(value = SpaceUserPermissionConstant.PICTURE_EDIT)
    public BaseResponse<CreateOutPaintingTaskResponse> createOutPaintingTask(@RequestBody CreatePictureOutPaintingTaskRequest createPictureOutPaintingTaskRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(createPictureOutPaintingTaskRequest == null || createPictureOutPaintingTaskRequest.getPictureId() == null,
                ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        CreateOutPaintingTaskResponse response = pictureService.createOutPaintingTask(createPictureOutPaintingTaskRequest, loginUser);
        return ResultUtils.success(response);
    }

    /**
     * 获取 AI 扩图任务结果（校验任务归属）
     */
    @ApiOperation("获取 AI 扩图任务结果")
    @GetMapping("/out_painting/get_task")
    public BaseResponse<GetOutPaintingTaskResponse> getOutPaintingTask(@RequestParam String taskId,
                                                                       HttpServletRequest request) {
        ThrowUtils.throwIf(taskId == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        GetOutPaintingTaskResponse task = pictureService.getOutPaintingTask(taskId, loginUser);
        return ResultUtils.success(task);
    }

}