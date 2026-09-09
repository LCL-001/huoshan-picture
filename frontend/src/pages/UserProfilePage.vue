<template>
  <div id="userProfilePage" style="max-width: 800px; margin: 0 auto">
    <a-button type="link" style="margin-bottom: 8px; padding: 0" @click="router.back()">
      <LeftOutlined /> 返回
    </a-button>
    <a-spin :spinning="loading">
      <template v-if="profile">
        <!-- 用户信息卡片 -->
        <a-card style="margin-bottom: 16px">
          <a-flex align="center" gap="16">
            <a-avatar :size="64" :src="profile.userAvatar" />
            <div style="flex: 1">
              <div style="font-size: 20px; font-weight: 700; margin-bottom: 4px">{{ profile.userName }}</div>
              <div style="color: #666; font-size: 13px; margin-bottom: 8px">{{ profile.userProfile || '这个人很懒，什么都没写~' }}</div>
              <a-space size="small">
                <span style="font-size: 12px; color: #999">{{ followingCount }} 关注</span>
                <span style="font-size: 12px; color: #999">{{ followerCount }} 粉丝</span>
              </a-space>
            </div>
            <a-button v-if="!isSelf" :type="following ? 'default' : 'primary'" @click="doToggleFollow">
              {{ following ? '已关注' : '关注' }}
            </a-button>
          </a-flex>
        </a-card>
        <!-- 帖子列表 -->
        <h3 style="margin-bottom: 12px">发布的帖子</h3>
        <div v-if="posts.length" class="post-grid">
          <a-card v-for="p in posts" :key="p.id" hoverable @click="router.push('/post/' + p.id)">
            <template #cover>
              <img v-if="getFirstImage(p.images)" :src="getFirstImage(p.images)" loading="lazy" style="width: 100%; height: 140px; object-fit: cover" />
            </template>
            <a-card-meta :title="p.title">
              <template #description>
                <a-space size="small">
                  <span>{{ p.likeCount ?? 0 }} 赞</span>
                  <span>{{ p.commentCount ?? 0 }} 评论</span>
                </a-space>
              </template>
            </a-card-meta>
          </a-card>
        </div>
        <a-empty v-else description="暂无帖子" />
      </template>
      <div v-else-if="error && !loading" style="text-align: center; padding: 80px 0">
        <a-empty description="加载失败">
          <template #children>
            <a-button type="primary" @click="fetchData()">重新加载</a-button>
          </template>
        </a-empty>
      </div>
      <a-empty v-else-if="!loading" description="用户不存在" />
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { LeftOutlined } from '@ant-design/icons-vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { getUserVoByIdUsingGet } from '@/api/userController.ts'
import { listPostByPageUsingPost, toggleFollowUsingPost, getFollowStatusUsingGet, getFollowingCountUsingGet, getFollowerCountUsingGet } from '@/api/postController.ts'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'

interface Props { id: string }
const props = defineProps<Props>()

const router = useRouter()
const loginUserStore = useLoginUserStore()

const profile = ref<API.UserVO | null>(null)
const posts = ref<API.PostVO[]>([])
const loading = ref(true)
const error = ref(false)
const following = ref(false)
const followingCount = ref(0)
const followerCount = ref(0)

const isSelf = computed(() => loginUserStore.loginUser.id === profile.value?.id)

const fetchData = async () => {
  const id = props.id
  if (!id) return
  loading.value = true
  error.value = false
  try {
    const [userRes, postRes, followStatus, followRes, followerRes] = await Promise.allSettled([
      getUserVoByIdUsingGet({ id }),
      listPostByPageUsingPost({ userId: id, current: 1, pageSize: 50 }),
      getFollowStatusUsingGet(id),
      getFollowingCountUsingGet(id),
      getFollowerCountUsingGet(id),
    ])
    if (userRes.status === 'fulfilled' && userRes.value.data.code === 0 && userRes.value.data.data) {
      profile.value = userRes.value.data.data
    } else if (userRes.status === 'rejected') {
      error.value = true
    }
    if (postRes.status === 'fulfilled' && postRes.value.data.code === 0 && postRes.value.data.data) posts.value = postRes.value.data.data.records ?? []
    if (followStatus.status === 'fulfilled' && followStatus.value.data.code === 0) following.value = followStatus.value.data.data ?? false
    if (followRes.status === 'fulfilled' && followRes.value.data.code === 0) followingCount.value = followRes.value.data.data ?? 0
    if (followerRes.status === 'fulfilled' && followerRes.value.data.code === 0) followerCount.value = followerRes.value.data.data ?? 0
  } catch { error.value = true }
  finally { loading.value = false }
}

const doToggleFollow = async () => {
  if (!profile.value?.id) return
  const res = await toggleFollowUsingPost({ followeeId: profile.value.id })
  if (res.data.code === 0) following.value = res.data.data ?? false
}

const getFirstImage = (images?: string) => images?.split(',')[0] ?? null

onMounted(() => fetchData())
</script>

<style scoped>
.post-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px;
}
</style>
