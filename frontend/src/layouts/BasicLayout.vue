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
          <el-tooltip content="开启后，核查时用 AI 为疑点生成归因说明（不影响判定与金额）" placement="bottom">
            <div class="ai-switch">
              <span>AI 归因</span>
              <el-switch v-model="aiAttribution" size="small" :loading="aiSwitchLoading" @change="onAttributionChange" />
            </div>
          </el-tooltip>
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
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { useMenus } from '@/composables/useMenus'
import { useAppStore } from '@/stores/app'
import { settingsApi } from '@/api'
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

const aiAttribution = ref(false)
const aiSwitchLoading = ref(false)
// 报告 AI 开关已移到报告页，这里仅保留其值以免切换归因开关时覆盖对方状态
const reportAiEnabled = ref(false)

onMounted(async () => {
  try {
    const settings = await settingsApi.get()
    aiAttribution.value = settings.llmAttributionEnabled
    reportAiEnabled.value = settings.reportAiEnabled
  } catch {
    // 设置读取失败时保持默认关闭，不阻断页面
  }
})

async function onAttributionChange() {
  aiSwitchLoading.value = true
  try {
    const settings = await settingsApi.update({
      llmAttributionEnabled: aiAttribution.value,
      reportAiEnabled: reportAiEnabled.value,
    })
    aiAttribution.value = settings.llmAttributionEnabled
    reportAiEnabled.value = settings.reportAiEnabled
    ElMessage.success(aiAttribution.value ? '已开启 AI 归因' : '已关闭 AI 归因')
  } catch {
    aiAttribution.value = !aiAttribution.value
    ElMessage.error('AI 设置失败')
  } finally {
    aiSwitchLoading.value = false
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
.user {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 14px;
  color: #303133;
  cursor: pointer;
}
.ai-switch {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #606266;
  cursor: default;
}
</style>
