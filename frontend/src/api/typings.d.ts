declare namespace API {
  type BaseResponseBoolean_ = {
    code?: number
    data?: boolean
    message?: string
  }

  type BaseResponseCreateOutPaintingTaskResponse_ = {
    code?: number
    data?: CreateOutPaintingTaskResponse
    message?: string
  }

  type BaseResponseGetOutPaintingTaskResponse_ = {
    code?: number
    data?: GetOutPaintingTaskResponse
    message?: string
  }

  type BaseResponseInt_ = {
    code?: number
    data?: number
    message?: string
  }

  type BaseResponseListImageSearchResult_ = {
    code?: number
    data?: ImageSearchResult[]
    message?: string
  }

  type BaseResponseListObject_ = {
    code?: number
    data?: unknown[]
    message?: string
  }

  type BaseResponseListPictureAiTagSuggestionVO_ = {
    code?: number
    data?: PictureAiTagSuggestionVO[]
    message?: string
  }

  type BaseResponseListPictureVO_ = {
    code?: number
    data?: PictureVO[]
    message?: string
  }

  type BaseResponseListSpace_ = {
    code?: number
    data?: Space[]
    message?: string
  }

  type BaseResponseListSpaceCategoryAnalyzeResponse_ = {
    code?: number
    data?: SpaceCategoryAnalyzeResponse[]
    message?: string
  }

  type BaseResponseListSpaceLevel_ = {
    code?: number
    data?: SpaceLevel[]
    message?: string
  }

  type BaseResponseListSpaceSizeAnalyzeResponse_ = {
    code?: number
    data?: SpaceSizeAnalyzeResponse[]
    message?: string
  }

  type BaseResponseListSpaceTagAnalyzeResponse_ = {
    code?: number
    data?: SpaceTagAnalyzeResponse[]
    message?: string
  }

  type BaseResponseListSpaceUserAnalyzeResponse_ = {
    code?: number
    data?: SpaceUserAnalyzeResponse[]
    message?: string
  }

  type BaseResponseListSpaceUserVO_ = {
    code?: number
    data?: SpaceUserVO[]
    message?: string
  }

  type BaseResponseLoginUserVO_ = {
    code?: number
    data?: LoginUserVO
    message?: string
  }

  type BaseResponseLong_ = {
    code?: number
    data?: string
    message?: string
  }

  type BaseResponsePagePicture_ = {
    code?: number
    data?: PagePicture_
    message?: string
  }

  type BaseResponsePagePictureVO_ = {
    code?: number
    data?: PagePictureVO_
    message?: string
  }

  type BaseResponsePageSpace_ = {
    code?: number
    data?: PageSpace_
    message?: string
  }

  type BaseResponsePageSpaceVO_ = {
    code?: number
    data?: PageSpaceVO_
    message?: string
  }

  type BaseResponsePageUserVO_ = {
    code?: number
    data?: PageUserVO_
    message?: string
  }

  type BaseResponsePicture_ = {
    code?: number
    data?: Picture
    message?: string
  }

  type BaseResponsePictureTagCategory_ = {
    code?: number
    data?: PictureTagCategory
    message?: string
  }

  type BaseResponsePictureVO_ = {
    code?: number
    data?: PictureVO
    message?: string
  }

  type BaseResponseSpace_ = {
    code?: number
    data?: Space
    message?: string
  }

  type BaseResponseSpaceUsageAnalyzeResponse_ = {
    code?: number
    data?: SpaceUsageAnalyzeResponse
    message?: string
  }

  type BaseResponseSpaceUser_ = {
    code?: number
    data?: SpaceUser
    message?: string
  }

  type BaseResponseSpaceVO_ = {
    code?: number
    data?: SpaceVO
    message?: string
  }

  type BaseResponseString_ = {
    code?: number
    data?: string
    message?: string
  }

  type BaseResponseUser_ = {
    code?: number
    data?: User
    message?: string
  }

  type BaseResponseUserVO_ = {
    code?: number
    data?: UserVO
    message?: string
  }

  type BaseResponseVOutPaintingStatusVO_ = {
    code?: number
    data?: VOutPaintingStatusVO
    message?: string
  }

  type CreateOutPaintingTaskResponse = {
    code?: string
    message?: string
    output?: Output
    requestId?: string
  }

  type CreatePictureOutPaintingTaskRequest = {
    parameters?: Parameters
    pictureId?: string
  }

  type DeleteRequest = {
    id?: string
  }

  type GetOutPaintingTaskResponse = {
    output?: Output1
    requestId?: string
  }

  type getPictureByIdUsingGETParams = {
    /** id */
    id?: string
  }

  type getPictureOutPaintingTaskUsingGETParams = {
    /** taskId */
    taskId?: string
  }

  type getPictureVOByIdUsingGETParams = {
    /** id */
    id?: string
  }

  type getSpaceByIdUsingGETParams = {
    /** id */
    id?: string
  }

  type getSpaceVOByIdUsingGETParams = {
    /** id */
    id?: string
  }

  type getUserByIdUsingGETParams = {
    /** id */
    id?: string
  }

  type getUserVOByIdUsingGETParams = {
    /** id */
    id?: string
  }

  type ImageSearchResult = {
    fromUrl?: string
    thumbUrl?: string
  }

  type LoginUserVO = {
    createTime?: string
    editTime?: string
    id?: string
    updateTime?: string
    userAccount?: string
    userAvatar?: string
    userName?: string
    userProfile?: string
    userRole?: string
  }

  type Output = {
    taskId?: string
    taskStatus?: string
  }

  type Output1 = {
    code?: string
    endTime?: string
    message?: string
    outputImageUrl?: string
    scheduledTime?: string
    submitTime?: string
    taskId?: string
    taskMetrics?: TaskMetrics
    taskStatus?: string
  }

  type PagePicture_ = {
    current?: string
    pages?: string
    records?: Picture[]
    size?: string
    total?: string
  }

  type PagePictureVO_ = {
    current?: string
    pages?: string
    records?: PictureVO[]
    size?: string
    total?: string
  }

  type PageSpace_ = {
    current?: string
    pages?: string
    records?: Space[]
    size?: string
    total?: string
  }

  type PageSpaceVO_ = {
    current?: string
    pages?: string
    records?: SpaceVO[]
    size?: string
    total?: string
  }

  type PageUserVO_ = {
    current?: string
    pages?: string
    records?: UserVO[]
    size?: string
    total?: string
  }

  type PostVO = {
    id?: string
    userId?: string
    title?: string
    content?: string
    images?: string
    tagList?: string[]
    likeCount?: number
    commentCount?: number
    viewCount?: number
    status?: number
    createTime?: string
    updateTime?: string
    user?: UserVO
    isLiked?: boolean
  }

  type PostAddRequest = {
    title?: string
    content?: string
    images?: string
    tags?: string
  }

  type PostEditRequest = {
    id?: string
    title?: string
    content?: string
    images?: string
    tags?: string
  }

  type PostQueryRequest = {
    current?: number
    pageSize?: number
    userId?: string
    keyword?: string
  }

  type UserNotification = {
    id?: string
    userId?: string
    fromUserId?: string
    type?: string
    targetId?: string
    summary?: string
    isRead?: number
    createTime?: string
  }

  type BaseResponsePageUserNotification_ = {
    code?: number
    data?: PageUserNotification_
    message?: string
  }

  type PageUserNotification_ = {
    current?: string
    pages?: string
    records?: UserNotification[]
    size?: string
    total?: string
  }

  type BaseResponsePostVO_ = {
    code?: number
    data?: PostVO
    message?: string
  }

  type BaseResponsePagePostVO_ = {
    code?: number
    data?: PagePostVO_
    message?: string
  }

  type PagePostVO_ = {
    current?: number
    pages?: string
    records?: PostVO[]
    size?: string
    total?: number
  }

  type Parameters = {
    addWatermark?: boolean
    angle?: number
    bestQuality?: boolean
    bottomOffset?: number
    leftOffset?: number
    limitImageSize?: boolean
    outputRatio?: string
    rightOffset?: number
    topOffset?: number
    xScale?: number
    yScale?: number
  }

  type Picture = {
    category?: string
    createTime?: string
    editTime?: string
    id?: string
    introduction?: string
    isDelete?: number
    name?: string
    picColor?: string
    picFormat?: string
    picHeight?: number
    picScale?: number
    picSize?: string
    picWidth?: number
    reviewMessage?: string
    reviewStatus?: number
    reviewTime?: string
    reviewerId?: string
    spaceId?: string
    tags?: string
    thumbnailUrl?: string
    updateTime?: string
    url?: string
    userId?: string
  }

  /** AI 打标出建议入参：id 是雪花 id，图库统一序列化成字符串，按字符串回传防精度丢失 */
  type PictureAiTagRequest = {
    pictureIdList?: string[]
  }

  /** AI 打标逐张建议/写入结果 */
  type PictureAiTagSuggestionVO = {
    category?: string
    message?: string
    ok?: boolean
    pictureId?: string
    tags?: string[]
    url?: string
  }

  /** AI 打标应用入参的单张（空值表示"这项不改"） */
  type PictureAiTagApplyItem = {
    category?: string
    pictureId?: string
    tags?: string[]
  }

  type PictureAiTagApplyRequest = {
    items?: PictureAiTagApplyItem[]
  }

  type PictureEditByBatchRequest = {
    category?: string
    nameRule?: string
    pictureIdList?: string[]
    spaceId?: string
    tags?: string[]
  }

  type PictureEditRequest = {
    category?: string
    id?: string
    introduction?: string
    name?: string
    tags?: string[]
  }

  type PictureQueryRequest = {
    category?: string
    current?: number
    endEditTime?: string
    id?: string
    introduction?: string
    name?: string
    nullSpaceId?: boolean
    pageSize?: number
    picFormat?: string
    picHeight?: number
    picScale?: number
    picSize?: string
    picWidth?: number
    reviewMessage?: string
    reviewStatus?: number
    reviewTime?: string
    reviewerId?: string
    searchText?: string
    sortField?: string
    sortOrder?: string
    spaceId?: string
    startEditTime?: string
    tags?: string[]
    userId?: string
  }

  type PictureReviewRequest = {
    id?: string
    reviewMessage?: string
    reviewStatus?: number
  }

  type PictureTagCategory = {
    categoryList?: string[]
    tagList?: string[]
  }

  type PictureUpdateRequest = {
    category?: string
    id?: string
    introduction?: string
    name?: string
    tags?: string[]
  }

  type PictureUploadByBatchRequest = {
    count?: number
    namePrefix?: string
    searchText?: string
    offset?: number
  }

  type PictureUploadRequest = {
    fileUrl?: string
    id?: string
    picName?: string
    spaceId?: string
  }

  type PictureVO = {
    category?: string
    createTime?: string
    editTime?: string
    id?: string
    introduction?: string
    name?: string
    permissionList?: string[]
    picColor?: string
    picFormat?: string
    picHeight?: number
    picScale?: number
    picSize?: string
    picWidth?: number
    spaceId?: string
    tags?: string[]
    thumbnailUrl?: string
    updateTime?: string
    url?: string
    user?: UserVO
    userId?: string
  }

  type SearchPictureByColorRequest = {
    picColor?: string
    spaceId?: string
  }

  type SearchPictureByPictureRequest = {
    pictureId?: string
  }

  type Space = {
    createTime?: string
    editTime?: string
    id?: string
    isDelete?: number
    maxCount?: string
    maxSize?: string
    spaceLevel?: number
    spaceName?: string
    spaceType?: number
    totalCount?: string
    totalSize?: string
    updateTime?: string
    userId?: string
  }

  type SpaceAddRequest = {
    spaceLevel?: number
    spaceName?: string
    spaceType?: number
  }

  type SpaceCategoryAnalyzeRequest = {
    queryAll?: boolean
    queryPublic?: boolean
    spaceId?: string
  }

  type SpaceCategoryAnalyzeResponse = {
    category?: string
    count?: string
    totalSize?: string
  }

  type SpaceEditRequest = {
    id?: string
    spaceName?: string
  }

  type SpaceLevel = {
    maxCount?: string
    maxSize?: string
    text?: string
    value?: number
  }

  type SpaceQueryRequest = {
    current?: number
    id?: string
    pageSize?: number
    sortField?: string
    sortOrder?: string
    spaceLevel?: number
    spaceName?: string
    spaceType?: number
    userId?: string
  }

  type SpaceRankAnalyzeRequest = {
    queryAll?: boolean
    queryPublic?: boolean
    spaceId?: string
    topN?: number
  }

  type SpaceSizeAnalyzeRequest = {
    queryAll?: boolean
    queryPublic?: boolean
    spaceId?: string
  }

  type SpaceSizeAnalyzeResponse = {
    count?: string
    sizeRange?: string
  }

  type SpaceTagAnalyzeRequest = {
    queryAll?: boolean
    queryPublic?: boolean
    spaceId?: string
  }

  type SpaceTagAnalyzeResponse = {
    count?: string
    tag?: string
  }

  type SpaceUpdateRequest = {
    id?: string
    maxCount?: string
    maxSize?: string
    spaceLevel?: number
    spaceName?: string
  }

  type SpaceUsageAnalyzeRequest = {
    queryAll?: boolean
    queryPublic?: boolean
    spaceId?: string
  }

  type SpaceUsageAnalyzeResponse = {
    countUsageRatio?: number
    maxCount?: string
    maxSize?: string
    sizeUsageRatio?: number
    usedCount?: string
    usedSize?: string
  }

  type SpaceUser = {
    createTime?: string
    id?: string
    spaceId?: string
    spaceRole?: string
    updateTime?: string
    userId?: string
  }

  type SpaceUserAddRequest = {
    spaceId?: string
    spaceRole?: string
    userId?: string
  }

  type SpaceUserAnalyzeRequest = {
    queryAll?: boolean
    queryPublic?: boolean
    spaceId?: string
    timeDimension?: string
    userId?: string
  }

  type SpaceUserAnalyzeResponse = {
    count?: string
    period?: string
  }

  type SpaceUserEditRequest = {
    id?: string
    spaceRole?: string
  }

  type SpaceUserQueryRequest = {
    id?: string
    spaceId?: string
    spaceRole?: string
    userId?: string
  }

  type SpaceUserVO = {
    createTime?: string
    id?: string
    space?: SpaceVO
    spaceId?: string
    spaceRole?: string
    updateTime?: string
    user?: UserVO
    userId?: string
  }

  type SpaceVO = {
    createTime?: string
    editTime?: string
    id?: string
    maxCount?: string
    maxSize?: string
    permissionList?: string[]
    spaceLevel?: number
    spaceName?: string
    spaceType?: number
    totalCount?: string
    totalSize?: string
    updateTime?: string
    user?: UserVO
    userId?: string
  }

  type TaskMetrics = {
    failed?: number
    succeeded?: number
    total?: number
  }

  type testDownloadFileUsingGETParams = {
    /** filepath */
    filepath?: string
  }

  type uploadPictureUsingPOSTParams = {
    fileUrl?: string
    id?: string
    picName?: string
    spaceId?: string
  }

  type User = {
    createTime?: string
    editTime?: string
    id?: string
    isDelete?: number
    updateTime?: string
    userAccount?: string
    userAvatar?: string
    userName?: string
    userPassword?: string
    userProfile?: string
    userRole?: string
    vipCode?: string
    vipExpireTime?: string
    vipNumber?: number
  }

  type UserAddRequest = {
    userAccount?: string
    userAvatar?: string
    userName?: string
    userProfile?: string
    userRole?: string
  }

  type UserLoginRequest = {
    captchaCode?: string
    captchaUuid?: string
    userAccount?: string
    userPassword?: string
  }

  type CaptchaVO = {
    captchaUuid?: string
    imageBase64?: string
  }

  type BaseResponseCaptchaVO_ = {
    code?: number
    data?: CaptchaVO
    message?: string
  }

  type UserQueryRequest = {
    current?: number
    id?: string
    pageSize?: number
    sortField?: string
    sortOrder?: string
    userAccount?: string
    userName?: string
    userProfile?: string
    userRole?: string
  }

  type UserRegisterRequest = {
    captchaCode?: string
    checkPassword?: string
    email?: string
    emailCode?: string
    userAccount?: string
    userPassword?: string
  }

  type EmailCaptchaRequest = {
    captchaCode?: string
    captchaUuid?: string
    email?: string
    scene?: string
  }

  type UserPasswordResetRequest = {
    captchaCode?: string
    email?: string
    emailCode?: string
    newPassword?: string
  }

  type UserEmailBindRequest = {
    email?: string
    emailCode?: string
  }

  type UserEditRequest = {
    id?: string
    userAvatar?: string
    userName?: string
    userProfile?: string
  }

  type UserUpdateRequest = {
    id?: string
    userAvatar?: string
    userName?: string
    userProfile?: string
    userRole?: string
  }

  type UserVO = {
    createTime?: string
    email?: string
    id?: string
    userAccount?: string
    userAvatar?: string
    userName?: string
    userProfile?: string
    userRole?: string
    vipCode?: string
    vipExpireTime?: string
    vipNumber?: number
  }

  type VOutPaintingStatusVO = {
    /** 功能是否可用（false = 维护态，隐藏生成入口） */
    available?: boolean
    /** 不可用时的维护文案（后端下发，前端只展示） */
    message?: string
  }

  type VipExchangeRequest = {
    vipCode?: string
  }
}
