import { createRouter, createWebHistory } from 'vue-router'
import BasicLayout from '@/layouts/BasicLayout.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
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
      ],
    },
  ],
})

export default router
