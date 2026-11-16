import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 应用状态 Store（预留）
 * 后续接入 RBAC：当前用户、角色权限、项目列表缓存等。
 */
export const useAppStore = defineStore('app', () => {
  const user = ref('guest')
  const role = ref<string | null>(null)

  function setUser(name: string, roleCode?: string) {
    user.value = name
    role.value = roleCode ?? null
  }

  return { user, role, setUser }
})
