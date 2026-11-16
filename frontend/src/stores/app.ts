import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi, type UserVO } from '@/api'
import { TOKEN_KEY, USER_KEY } from '@/api/request'

/**
 * 应用状态：登录用户、角色（AUDITOR 造价人员 / MANAGER 项目经理）
 */
export const useAppStore = defineStore('app', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) ?? '')
  const user = ref<UserVO | null>(parseUser(localStorage.getItem(USER_KEY)))

  const loggedIn = computed(() => Boolean(token.value))
  const isManager = computed(() => user.value?.roleCode === 'MANAGER')
  const displayName = computed(() => user.value?.realName || user.value?.username || '未登录')

  async function login(username: string, password: string) {
    const res = await authApi.login({ username, password })
    token.value = res.token
    user.value = res.user
    localStorage.setItem(TOKEN_KEY, res.token)
    localStorage.setItem(USER_KEY, JSON.stringify(res.user))
    return res.user
  }

  async function refreshMe() {
    if (!token.value) {
      return
    }
    try {
      const me = await authApi.me()
      user.value = me
      localStorage.setItem(USER_KEY, JSON.stringify(me))
    } catch {
      logout()
    }
  }

  function logout() {
    token.value = ''
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }

  return { token, user, loggedIn, isManager, displayName, login, logout, refreshMe }
})

function parseUser(raw: string | null): UserVO | null {
  if (!raw) {
    return null
  }
  try {
    return JSON.parse(raw) as UserVO
  } catch {
    return null
  }
}
