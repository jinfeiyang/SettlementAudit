import { createRouter, createWebHistory } from 'vue-router'
import BasicLayout from '@/layouts/BasicLayout.vue'
import { TOKEN_KEY, USER_KEY } from '@/api/request'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/login/LoginView.vue'),
      meta: { title: '登录', public: true },
    },
    {
      path: '/',
      component: BasicLayout,
      redirect: '/dashboard',
      children: [
        {
          path: 'dashboard',
          name: 'Dashboard',
          component: () => import('@/views/dashboard/DashboardView.vue'),
          meta: { title: '工作台', icon: 'Odometer' },
        },
        {
          path: 'projects',
          name: 'Projects',
          component: () => import('@/views/project/ProjectListView.vue'),
          meta: { title: '项目管理', icon: 'Folder' },
        },
        {
          path: 'projects/:id',
          name: 'ProjectDetail',
          component: () => import('@/views/project/ProjectDetailView.vue'),
          meta: { title: '预审作业', hide: true },
        },
        {
          path: 'issues',
          name: 'Issues',
          component: () => import('@/views/issue/IssueReviewView.vue'),
          meta: { title: '疑点复核', icon: 'Warning' },
        },
        {
          path: 'reports',
          name: 'Reports',
          component: () => import('@/views/report/ReportListView.vue'),
          meta: { title: '报告管理', icon: 'Document' },
        },
        {
          path: 'rules',
          name: 'Rules',
          component: () => import('@/views/rule/RuleLibraryView.vue'),
          meta: { title: '规则库', icon: 'Collection' },
        },
        {
          path: 'users',
          name: 'Users',
          component: () => import('@/views/system/UserManageView.vue'),
          meta: { title: '用户管理', icon: 'User', managerOnly: true },
        },
      ],
    },
  ],
})

router.beforeEach((to) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (!to.meta.public && !token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && token) {
    return { path: '/dashboard' }
  }
  if (to.meta.managerOnly) {
    let role = ''
    try {
      role = (JSON.parse(localStorage.getItem(USER_KEY) || '{}').roleCode as string) || ''
    } catch {
      role = ''
    }
    if (role !== 'MANAGER') {
      return { path: '/dashboard' }
    }
  }
  return true
})

export default router
