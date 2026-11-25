import router from '@/router'

export interface RouteMetaItem {
  path: string
  title: string
  icon: string
  managerOnly?: boolean
}

export function useRouteMeta(): RouteMetaItem[] {
  const root = router.options.routes.find((r) => r.path === '/')
  const children = root?.children ?? []
  return children
    .filter((c) => c.meta && 'title' in c.meta && !c.meta.hide)
    .map((c) => ({
      path: String(c.path),
      title: String(c.meta!.title),
      icon: String(c.meta!.icon ?? 'Menu'),
      managerOnly: Boolean(c.meta!.managerOnly),
    }))
}
