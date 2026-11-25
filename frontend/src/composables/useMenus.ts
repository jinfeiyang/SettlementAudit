import { useRouteMeta } from '@/composables/useRouteMeta'

export interface MenuItem {
  path: string
  title: string
  icon: string
  managerOnly?: boolean
}

/**
 * 菜单源：与 router 的 meta 保持一致（骨架阶段简单同步，后续接 RBAC 权限过滤）。
 */
export function useMenus(): MenuItem[] {
  return useRouteMeta()
}
