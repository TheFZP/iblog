import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getUserInfo, login as loginApi, logout as logoutApi, type LoginParams } from '@/api/auth'
import { clearToken, getToken, setToken } from '@/api/request'

export const useUserStore = defineStore('user', () => {
  const token = ref<string>(getToken())
  const nickname = ref<string>('')
  const avatar = ref<string>('')

  async function login(params: LoginParams) {
    const data = await loginApi(params)
    token.value = data.token
    nickname.value = data.nickname || data.username
    avatar.value = data.avatar || ''
    setToken(data.token)
    return data
  }

  async function fetchUserInfo() {
    const info = await getUserInfo()
    nickname.value = info.nickname || info.username
    avatar.value = info.avatar || ''
    return info
  }

  async function logout() {
    try {
      await logoutApi()
    } finally {
      token.value = ''
      nickname.value = ''
      avatar.value = ''
      clearToken()
    }
  }

  return { token, nickname, avatar, login, fetchUserInfo, logout }
})
