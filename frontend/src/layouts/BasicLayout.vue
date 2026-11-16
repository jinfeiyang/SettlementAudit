<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">造价结算 AI 预审</div>
      <el-menu router :default-active="route.path" background-color="#001529" text-color="#cfd8e3" active-text-color="#409eff">
        <el-menu-item v-for="item in menus" :key="item.path" :index="item.path">
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
        <span class="env">一期试用版 · 模块化单体</span>
      </el-header>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router'
import { computed } from 'vue'
import { useMenus } from '@/composables/useMenus'
import {
  Odometer,
  Folder,
  Warning,
  Document,
  Menu as IconMenu,
} from '@element-plus/icons-vue'

const iconMap: Record<string, any> = {
  Odometer,
  Folder,
  Warning,
  Document,
  Menu: IconMenu,
}

const route = useRoute()
const menus = computed(() =>
  useMenus().map((m) => ({
    path: '/' + m.path,
    title: m.title,
    icon: m.icon,
    iconComponent: iconMap[m.icon] ?? IconMenu,
  })),
)
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
.env {
  font-size: 12px;
  color: #909399;
}
</style>
