<template>
  <div id="postDetailPage" style="max-width: min(720px, 100%); margin: 0 auto">
    <a-button type="link" style="margin-bottom: 8px; padding: 0" @click="router.back()">
      <LeftOutlined /> 返回
    </a-button>
    <a-spin :spinning="loading">
      <template v-if="post.id">
        <!-- 帖子内容 -->
        <a-card style="margin-bottom: 16px">
          <template #extra>
            <a-space>
              <a-button v-if="isOwner" type="link" @click="doEdit">编辑</a-button>
              <a-button v-if="isOwner" type="link" danger @click="doDelete">删除</a-button>
            </a-space>
          </template>
          <a-flex align="center" gap="12" style="margin-bottom: 16px">
            <a-avatar :size="40" :src="post.user?.userAvatar" />
            <div style="flex: 1">
              <div style="font-weight: 600">{{ post.user?.userName }}</div>
              <div style="color: #999; font-size: 12px">{{ formatRelativeTime(post.createTime) }}</div>
            </div>
            <a-button v-if="!isOwner" :type="following ? 'default' : 'primary'" size="small"
              @click="doToggleFollow">{{ following ? '已关注' : '关注' }}</a-button>
          </a-flex>
          <!-- 图片：移动端轮播，桌面端网格 -->
          <div v-if="imageList.length" style="margin-bottom: 16px">
            <a-carousel v-if="isMobile && imageList.length > 1">
              <div v-for="(url, i) in imageList" :key="i">
                <a-image :src="url" style="width: 100%; max-height: 400px; object-fit: contain; border-radius: 8px" />
              </div>
            </a-carousel>
            <div v-else style="display: flex; gap: 8px; flex-wrap: wrap">
              <a-image
                v-for="(url, i) in imageList" :key="i" :src="url"
                style="width: 200px; height: 200px; object-fit: cover; border-radius: 8px"
              />
            </div>
          </div>
          <h2 style="margin-bottom: 16px">{{ post.title }}</h2>
          <div style="white-space: pre-wrap; margin-bottom: 16px; line-height: 1.8">
            {{ post.content }}
          </div>
          <a-space v-if="post.tagList?.length" style="margin-bottom: 16px">
            <a-tag v-for="tag in post.tagList" :key="tag" color="blue">{{ tag }}</a-tag>
          </a-space>
          <a-divider />
          <a-space size="large">
            <span style="cursor: pointer; user-select: none" @click="doToggleLike">
              <HeartFilled v-if="liked" style="color: #ff4d4f" />
              <HeartOutlined v-else />
              <span style="margin-left: 4px">{{ likeCount }}</span>
            </span>
            <span>{{ post.commentCount ?? 0 }} 评论</span>
            <span>{{ post.viewCount ?? 0 }} 浏览</span>
          </a-space>
        </a-card>

        <!-- 评论区 -->
        <a-card title="评论" style="margin-bottom: 16px">
          <!-- 发表评论 -->
          <a-flex gap="8" style="margin-bottom: 16px">
            <a-textarea v-model:value="commentText" :rows="2" placeholder="写下你的评论..." />
            <a-button type="primary" @click="doAddComment(null)">发布</a-button>
          </a-flex>
          <!-- 评论列表 -->
          <div v-if="comments.length">
            <div v-for="c in comments" :key="c.id" style="margin-bottom: 16px; padding-bottom: 12px; border-bottom: 1px solid #f0f0f0">
              <a-flex gap="8" align="start">
                <a-avatar :size="32" :src="c.user?.userAvatar" />
                <div style="flex: 1">
                  <div style="font-weight: 600; margin-bottom: 4px">{{ c.user?.userName }}</div>
                  <div style="margin-bottom: 4px">{{ c.content }}</div>
                  <a-flex gap="12" style="color: #999; font-size: 12px">
                    <span>{{ formatRelativeTime(c.createTime) }}</span>
                    <a-button type="link" size="small" @click="startReply(c)">回复</a-button>
                    <a-button v-if="loginUserStore.loginUser.id === c.userId" type="link" size="small" danger @click="doDeleteComment(c)">删除</a-button>
                  </a-flex>
                  <!-- 回复输入框 -->
                  <div v-if="replyingTo?.id === c.id" style="margin-top: 8px">
                    <a-flex gap="8">
                      <a-textarea v-model:value="replyText" :rows="2" :placeholder="'回复 ' + c.user?.userName" />
                      <a-button type="primary" size="small" @click="doAddComment(c)">回复</a-button>
                      <a-button size="small" @click="cancelReply">取消</a-button>
                    </a-flex>
                  </div>
                  <!-- 子回复 -->
                  <div v-if="c.children?.length" style="margin-top: 8px; padding-left: 12px; background: #fafafa; border-radius: 6px">
                    <div v-for="child in c.children" :key="child.id" style="padding: 6px 0">
                      <div style="display: flex; align-items: center; flex-wrap: wrap; gap: 4px">
                        <span style="font-weight: 600">{{ child.user?.userName }}</span>
                        <span v-if="child.replyToUser" style="color: #999"> 回复 {{ child.replyToUser?.userName }}</span>
                        <span>：{{ child.content }}</span>
                        <span style="color: #999; font-size: 11px">{{ formatRelativeTime(child.createTime) }}</span>
                        <span @click="startReply(child)" style="color: #1677ff; font-size: 11px; cursor: pointer">回复</span>
                        <a-button v-if="loginUserStore.loginUser.id === child.userId" type="link" size="small" danger @click="doDeleteComment(child)">删除</a-button>
                      </div>
                      <div v-if="replyingTo?.id === child.id" style="margin-top: 6px">
                        <a-flex gap="8">
                          <a-textarea v-model:value="replyText" :rows="2" :placeholder="'回复 ' + child.user?.userName" />
                          <a-button type="primary" size="small" @click="doAddComment(child)">回复</a-button>
                          <a-button size="small" @click="cancelReply">取消</a-button>
                        </a-flex>
                      </div>
                    </div>
                  </div>
                </div>
              </a-flex>
            </div>
          </div>
          <a-empty v-else description="暂无评论" />
        </a-card>
      </template>
      <div v-else-if="error && !loading" style="text-align: center; padding: 80px 0">
        <a-empty description="加载失败">
          <template #children>
            <a-button type="primary" @click="fetchDetail()">重新加载</a-button>
          </template>
        </a-empty>
      </div>
      <a-empty v-else-if="!loading" description="帖子不存在" />
    </a-spin>

    <!-- 编辑弹窗 -->
    <a-modal v-model:visible="editVisible" title="编辑帖子" :footer="false" width="640px">
      <a-form :model="editForm" layout="vertical" @finish="handleEdit">
        <a-form-item label="标题" required>
          <a-input v-model:value="editForm.title" />
        </a-form-item>
        <a-form-item label="内容" required>
          <a-textarea v-model:value="editForm.content" :rows="6" :maxlength="2000" show-count />
        </a-form-item>
        <a-form-item label="图片">
          <div style="display: flex; gap: 8px; flex-wrap: wrap">
            <div v-for="(url, i) in editImages" :key="url" style="position: relative">
              <img :src="url" style="width: 100px; height: 100px; object-fit: cover; border-radius: 4px" />
              <a-button size="small" danger type="link"
                style="position: absolute; top: -8px; right: -8px; min-width: auto; padding: 0"
                @click="removeEditImage(i)">×</a-button>
            </div>
            <a-tooltip title="上传本地图片">
              <a-upload :custom-request="handleEditImageUpload" :show-upload-list="false" accept="image/*">
                <div style="width: 100px; height: 100px; border: 1px dashed #d9d9d9;
                  border-radius: 4px; display: flex; align-items: center; justify-content: center; cursor: pointer">
                  <PlusOutlined />
                </div>
              </a-upload>
            </a-tooltip>
            <a-tooltip title="从我的图库选取">
              <div style="width: 100px; height: 100px; border: 1px dashed #d9d9d9;
                border-radius: 4px; display: flex; align-items: center; justify-content: center; cursor: pointer"
                @click="openGalleryPicker">
                <FolderOpenOutlined />
              </div>
            </a-tooltip>
          </div>
        </a-form-item>
        <a-form-item label="标签（逗号分隔）">
          <a-input v-model:value="editForm.tags" />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" html-type="submit" :loading="editing" block>保存</a-button>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 图库选取弹窗 -->
    <a-modal v-model:visible="galleryVisible" title="从图库选择" width="760px" :footer="false">
      <a-spin :spinning="galleryLoading">
        <div v-if="galleryList.length" style="display: flex; gap: 8px; flex-wrap: wrap; max-height: 400px; overflow-y: auto">
          <div v-for="pic in galleryList" :key="pic.id"
            style="width: 120px; cursor: pointer; border-radius: 4px; overflow: hidden; border: 2px solid transparent"
            :style="{ borderColor: selectedGalleryIds.has(pic.id!) ? '#1677ff' : 'transparent' }"
            @click="toggleGallerySelect(pic)">
            <img :src="pic.thumbnailUrl ?? pic.url" style="width: 100%; height: 100px; object-fit: cover" />
          </div>
        </div>
        <a-empty v-else description="暂无图片" />
      </a-spin>
      <a-flex justify="space-between" style="margin-top: 16px">
        <a-pagination v-model:current="galleryPage" :total="galleryTotal" :page-size="12" size="small"
          @change="loadGallery" simple />
        <a-button type="primary" @click="confirmGallerySelect">确认选择 ({{ selectedGalleryIds.size }})</a-button>
      </a-flex>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { FolderOpenOutlined, HeartFilled, HeartOutlined, LeftOutlined, PlusOutlined } from '@ant-design/icons-vue'
import {
  deletePostUsingPost, getPostByIdUsingGet,
  togglePostLikeUsingPost, getPostLikeCountUsingGet,
  addPostCommentUsingPost, listPostCommentsUsingGet, deletePostCommentUsingPost,
  toggleFollowUsingPost, getFollowStatusUsingGet,
} from '@/api/postController.ts'
import { formatRelativeTime } from '@/utils'
import { editPostUsingPost } from '@/api/postController.ts'
import { uploadFileUsingPost } from '@/api/fileController.ts'
import { listPictureVoByPageWithCacheUsingPost } from '@/api/pictureController.ts'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'

interface Props { id: string | number }
const props = defineProps<Props>()
const router = useRouter()
const loginUserStore = useLoginUserStore()

const post = ref<API.PostVO>({})
const loading = ref(false)
const error = ref(false)
const isMobile = ref(window.innerWidth < 768)
window.addEventListener('resize', () => { isMobile.value = window.innerWidth < 768 })
const liked = ref(false)
const likeCount = ref(0)
const following = ref(false)
const comments = ref<any[]>([])
const commentText = ref('')
const replyText = ref('')
const replyingTo = ref<any>(null)

const imageList = computed(() => post.value.images ? post.value.images.split(',').filter(Boolean) : [])
const isOwner = computed(() => loginUserStore.loginUser.id === post.value.userId)

const fetchDetail = async () => {
  loading.value = true
  error.value = false
  try {
    const id = Number(props.id)
    if (!id) return
    const res = await getPostByIdUsingGet(id)
    if (res.data.code === 0 && res.data.data) {
      post.value = res.data.data
      liked.value = post.value.isLiked ?? false
      loadInteractionData(id)
      if (post.value.userId && post.value.userId !== loginUserStore.loginUser.id) {
        loadFollowStatus(post.value.userId)
      }
    }
  } catch {
    error.value = true
  } finally { loading.value = false }
}

const loadInteractionData = async (id: number) => {
  const results = await Promise.allSettled([
    getPostLikeCountUsingGet(id),
    listPostCommentsUsingGet(id),
  ])
  const [likeCountR, commentsR] = results
  if (likeCountR.status === 'fulfilled' && likeCountR.value.data.code === 0) {
    likeCount.value = likeCountR.value.data.data ?? 0
  }
  if (commentsR.status === 'fulfilled' && commentsR.value.data.code === 0) {
    comments.value = commentsR.value.data.data ?? []
  }
}

onMounted(() => fetchDetail())

const loadFollowStatus = async (userId: number) => {
  try {
    const res = await getFollowStatusUsingGet(userId)
    if (res.data.code === 0) following.value = res.data.data ?? false
  } catch { /* ignore */ }
}

const doToggleFollow = async () => {
  const userId = post.value.userId
  if (!userId) return
  try {
    const res = await toggleFollowUsingPost({ followeeId: userId })
    if (res.data.code === 0) following.value = res.data.data ?? false
  } catch { message.error('操作失败') }
}

const doToggleLike = async () => {
  const id = post.value.id
  if (!id) return
  const res = await togglePostLikeUsingPost({ postId: id })
  if (res.data.code === 0) {
    liked.value = res.data.data ?? false
    const countRes = await getPostLikeCountUsingGet(id)
    if (countRes.data.code === 0) {
      likeCount.value = countRes.data.data ?? 0
    }
  }
}

const doAddComment = async (parent: any) => {
  const postId = post.value.id
  if (!postId) return
  const content = parent ? replyText.value : commentText.value
  if (!content.trim()) { message.warning('内容不能为空'); return }
  const isChild = parent?.parentId && parent.parentId > 0
  try {
    const res = await addPostCommentUsingPost({
      postId,
      parentId: isChild ? parent.parentId : parent?.id,
      replyToUserId: isChild ? parent?.userId : null,
      content,
    })
    if (res.data.code === 0) {
      message.success('评论成功')
      if (parent) { replyText.value = ''; replyingTo.value = null }
      else commentText.value = ''
      fetchDetail()
    }
  } catch { message.error('评论失败') }
}

const startReply = (c: any) => { replyingTo.value = c; replyText.value = '' }
const cancelReply = () => { replyingTo.value = null }

const doDeleteComment = async (c: any) => {
  try {
    await deletePostCommentUsingPost({ id: c.id })
    message.success('已删除')
    fetchDetail()
  } catch { message.error('删除失败') }
}

// 编辑
const editVisible = ref(false)
const editing = ref(false)
const editImages = ref<string[]>([])
const editForm = reactive({ title: '', content: '', tags: '' })

const doEdit = () => {
  editForm.title = post.value.title ?? ''
  editForm.content = post.value.content ?? ''
  editForm.tags = post.value.tagList?.join(',') ?? ''
  editImages.value = post.value.images ? post.value.images.split(',').filter(Boolean) : []
  editVisible.value = true
}

const removeEditImage = (i: number) => { editImages.value.splice(i, 1) }

const handleEditImageUpload = async ({ file }: any) => {
  try {
    const res = await uploadFileUsingPost(file)
    if (res.data.code === 0 && res.data.data) editImages.value.push(res.data.data)
  } catch { message.error('上传失败') }
}

const handleEdit = async () => {
  if (!editForm.title || !editForm.content) { message.warning('标题和内容不能为空'); return }
  editing.value = true
  try {
    const res = await editPostUsingPost({
      id: post.value.id,
      ...editForm,
      images: editImages.value.join(','),
    })
    if (res.data.code === 0) {
      message.success('保存成功')
      editVisible.value = false
      fetchDetail()
    } else {
      message.error('保存失败，' + res.data.message)
    }
  } finally { editing.value = false }
}

// 图库选取
const galleryVisible = ref(false)
const galleryLoading = ref(false)
const galleryList = ref<API.PictureVO[]>([])
const galleryPage = ref(1)
const galleryTotal = ref(0)
const selectedGalleryIds = ref(new Set<number>())

const openGalleryPicker = () => {
  selectedGalleryIds.value = new Set()
  galleryPage.value = 1
  galleryVisible.value = true
  loadGallery()
}

const loadGallery = async () => {
  galleryLoading.value = true
  try {
    const res = await listPictureVoByPageWithCacheUsingPost({ current: galleryPage.value, pageSize: 12, userId: loginUserStore.loginUser.id })
    if (res.data.code === 0 && res.data.data) {
      galleryList.value = res.data.data.records ?? []
      galleryTotal.value = res.data.data.total ?? 0
    }
  } finally { galleryLoading.value = false }
}

const toggleGallerySelect = (pic: API.PictureVO) => {
  const id = pic.id!
  if (selectedGalleryIds.value.has(id)) selectedGalleryIds.value.delete(id)
  else selectedGalleryIds.value.add(id)
}

const confirmGallerySelect = () => {
  const urls = galleryList.value
    .filter(p => selectedGalleryIds.value.has(p.id!))
    .map(p => p.url!)
  editImages.value.push(...urls)
  galleryVisible.value = false
}
const doDelete = () => {
  const id = post.value.id
  if (!id) return
  Modal.confirm({
    title: '确认删除',
    content: '确定要删除这篇帖子吗？此操作不可撤销。',
    okText: '确认删除',
    cancelText: '取消',
    okType: 'danger',
    onOk: async () => {
      await deletePostUsingPost({ id })
      message.success('删除成功')
      router.push('/square')
    },
  })
}
</script>

<style scoped>
.carousel-arrow {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: rgba(255,255,255,0.8);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #333;
  font-size: 14px;
  cursor: pointer;
}
</style>
