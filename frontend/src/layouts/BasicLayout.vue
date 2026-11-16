<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">造价结算 AI 预审</div>
      <el-menu
        router
        :default-active="activeMenu"
        background-color="#001529"
        text-color="#cfd8e3"
        active-text-color="#409eff"
      >
        <el-menu-item v-for="item in menus" :key="item.path" :index="'/' + item.path">
          <el-icon>
            <component :is="item.iconComponent" />
          </el-icon>
          <span>{{ item.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="title">通信工程造价结算 AI 预审分析工具</span>
        <div class="right">
          <span class="env">一期试用版 · 模块化单体</span>
          <el-tag v-if="store.user" size="small" effect="plain">{{ store.user.roleName }}</el-tag>
          <el-dropdown @command="onCommand">
            <span class="user">
              {{ store.displayName }}
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMenus } from '@/composables/useMenus'
import { useAppStore } from '@/stores/app'
import {
  ArrowDown,
  Collection,
  Document,
  Folder,
  Odometer,
  Warning,
  Menu as IconMenu,
} from '@element-plus/icons-vue'

const iconMap: Record<string, any> = {
  Odometer,
  Folder,
  Warning,
  Document,
  Collection,
  Menu: IconMenu,
}

const route = useRoute()
const router = useRouter()
const store = useAppStore()

const menus = computed(() =>
  useMenus().map((m) => ({
    path: m.path.replace(/^\//, ''),
    title: m.title,
    icon: m.icon,
    iconComponent: iconMap[m.icon] ?? IconMenu,
  })),
)

const activeMenu = computed(() => {
  if (route.path.startsWith('/projects')) {
    return '/projects'
  }
  return route.path
})

function onCommand(command: string) {
  if (command === 'logout') {
    store.logout()
    router.push('/login')
  }
}
</script>

<style scoped>
.layout {
  height: 100%;
}
.aside {
  background-color: #001529;
}
.logo {
  height: 56px;
  line-height: 56px;
  color: #fff;
  text-align: center;
  font-weight: 600;
  letter-spacing: 1px;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e4e7ed;
}
.title {
  font-size: 16px;
  font-weight: 600;
}
.right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.env {
  font-size: 12px;
  color: #909399;
}
.user {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 14px;
  color: #303133;
  cursor: pointer;
}
</style>
