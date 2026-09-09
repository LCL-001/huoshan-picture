import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { listMyTeamSpaceUsingPost } from '@/api/spaceUserController.ts'

const REMOVED_TEAM_SPACE_ID_STORAGE_KEY = 'removedTeamSpaceIdList'

const getSavedRemovedTeamSpaceIdList = () => {
  if (typeof window === 'undefined') {
    return []
  }
  try {
    return JSON.parse(window.sessionStorage.getItem(REMOVED_TEAM_SPACE_ID_STORAGE_KEY) || '[]')
  } catch (e) {
    return []
  }
}

const saveRemovedTeamSpaceIdList = (spaceIdList: string[]) => {
  if (typeof window === 'undefined') {
    return
  }
  window.sessionStorage.setItem(REMOVED_TEAM_SPACE_ID_STORAGE_KEY, JSON.stringify(spaceIdList))
}

export const useTeamSpaceStore = defineStore('teamSpace', () => {
  const teamSpaceList = ref<API.SpaceUserVO[]>([])
  const removedTeamSpaceIdList = ref<string[]>(getSavedRemovedTeamSpaceIdList())

  const removedTeamSpaceIdSet = computed(() => new Set(removedTeamSpaceIdList.value))

  const filterRemovedTeamSpaces = (spaceList: API.SpaceUserVO[]) => {
    return spaceList.filter((spaceUser) => {
      if (!spaceUser.space) return false
      return !removedTeamSpaceIdSet.value.has(String(spaceUser.spaceId))
    })
  }

  const fetchTeamSpaceList = async () => {
    const res = await listMyTeamSpaceUsingPost()
    if (res.data.code === 0 && res.data.data) {
      teamSpaceList.value = filterRemovedTeamSpaces(res.data.data)
    }
    return res
  }

  const removeTeamSpace = (spaceId: string | number) => {
    const spaceIdText = String(spaceId)
    if (!removedTeamSpaceIdSet.value.has(spaceIdText)) {
      removedTeamSpaceIdList.value = [...removedTeamSpaceIdList.value, spaceIdText]
      saveRemovedTeamSpaceIdList(removedTeamSpaceIdList.value)
    }
    teamSpaceList.value = teamSpaceList.value.filter((spaceUser) => {
      return String(spaceUser.spaceId) !== spaceIdText
    })
  }

  return {
    teamSpaceList,
    fetchTeamSpaceList,
    removeTeamSpace,
  }
})
