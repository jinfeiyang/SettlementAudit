<template>
  <div v-loading="loading">
    <el-page-header :content="project?.projectName || '预审作业'" @back="router.push('/projects')">
      <template #extra>
        <div class="actions">
          <el-button @click="loadAll">刷新</el-button>
          <el-button
            type="primary"
            :loading="checking"
            :disabled="!overview || overview.boqCount === 0 || project?.status === 'ARCHIVED'"
            @click="startCheck"
          >
            {{ checking ? `核查中 ${progress}%` : '发起自动核查' }}
          </el-button>
          <el-button
            :disabled="!overview || overview.issueTotal === 0 || project?.status === 'ARCHIVED'"
            @click="generateReport"
          >
            生成报告初稿
          </el-button>
          <el-button v-if="project?.status === 'REPORTED'" @click="archive">归档项目</el-button>
        </div>
      </template>
    </el-page-header>

    <el-descriptions :column="4" border class="meta">
      <el-descriptions-item label="项目编号">{{ project?.projectNo || '-' }}</el-descriptions-item>
      <el-descriptions-item label="地市">{{ project?.city || '-' }}</el-descriptions-item>
      <el-descriptions-item label="状态">
        <el-tag :type="statusTag(project?.status)" size="small">{{ project?.statusName }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="结算送审金额（元）">{{ money(overview?.settleAmount) }}</el-descriptions-item>
      <el-descriptions-item label="清单项数">{{ overview?.boqCount ?? 0 }}</el-descriptions-item>
      <el-descriptions-item label="清单合计（元）">{{ money(overview?.boqTotal) }}</el-descriptions-item>
      <el-descriptions-item label="最近核查">{{ datetime(overview?.lastCheckTime) }}</el-descriptions-item>
    </el-descriptions>

    <el-tabs v-model="tab" class="tabs">
      <el-tab-pane label="核查概览" name="overview">
        <OverviewPanel :overview="overview" @go="tab = $event" />
      </el-tab-pane>
      <el-tab-pane :label="`资料与解析 (${overview?.fileCount ?? 0})`" name="files">
        <FilePanel :project-id="numberId" @changed="refreshOverview" @go="tab = $event" />
      </el-tab-pane>
      <el-tab-pane :label="`工程量清单 (${overview?.boqCount ?? 0})`" name="boq">
        <BoqPanel
          :project-id="numberId"
          :highlight-row="highlightRow"
          :refresh-key="dataVersion"
          @changed="refreshOverview"
          @go="tab = $event"
        />
      </el-tab-pane>
      <el-tab-pane :label="`疑点复核 (${overview?.issueTotal ?? 0})`" name="issues">
        <IssuePanel
          :project-id="numberId"
          :refresh-key="dataVersion"
          @locate="onLocate"
          @changed="refreshOverview"
        />
      </el-tab-pane>
      <el-tab-pane label="预审报告" name="report">
        <ReportPanel :project-id="numberId" :refresh-key="dataVersion" @changed="refreshOverview" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { checkApi, projectApi, reportApi, taskApi, type Overview, type ProjectVO } from '@/api'
import { datetime, money, statusTag } from '@/utils/format'
import OverviewPanel from './components/OverviewPanel.vue'
import FilePanel from './components/FilePanel.vue'
import BoqPanel from './components/BoqPanel.vue'
import IssuePanel from './components/IssuePanel.vue'
import ReportPanel from './components/ReportPanel.vue'

const route = useRoute()
const router = useRouter()
const id = route.params.id
const numberId = Number(id ?? 0)

const loading = ref(false)
const project = ref<ProjectVO>()
const overview = ref<Overview>()
const tab = ref<string>('overview')
const highlightRow = ref<number>()
/** 核查/生成报告完成后自增，通知已挂载的 Tab 面板重新取数（Tab 不销毁，不会重新 onMounted） */
const dataVersion = ref(0)

const checking = ref(false)
const progress = ref(0)
let timer: number | undefined

async function loadAll() {
  if (!numberId) {
    return
  }
  loading.value = true
  try {
    const [p, o] = await Promise.all([projectApi.get(numberId), projectApi.overview(numberId)])
    project.value = p
    overview.value = o
  } finally {
    loading.value = false
  }
}

async function refreshOverview() {
  if (!numberId) {
    return
  }
  overview.value = await projectApi.overview(numberId)
  project.value = await projectApi.get(numberId)
}

async function startCheck() {
  if (!numberId) {
    return
  }
  checking.value = true
  progress.value = 0
  try {
    const { taskId } = await checkApi.start(numberId)
    pollTask(taskId)
  } catch {
    checking.value = false
  }
}

function pollTask(taskId: number) {
  timer = window.setInterval(async () => {
    try {
      const task = await taskApi.get(taskId)
      progress.value = task?.progress ?? 0
      if (task?.status === 'SUCCESS') {
        stopPoll()
        ElMessage.success(task.message || '核查完成')
        await refreshOverview()
        dataVersion.value += 1
        tab.value = 'issues'
      } else if (task?.status === 'FAILED') {
        stopPoll()
        ElMessage.error(task.message || '核查失败')
      }
    } catch {
      stopPoll()
    }
  }, 1500)
}

function stopPoll() {
  checking.value = false
  if (timer) {
    window.clearInterval(timer)
    timer = undefined
  }
}

async function generateReport() {
  if (!numberId) {
    return
  }
  const report = await reportApi.generate(numberId)
  ElMessage.success('报告初稿已生成')
  await refreshOverview()
  dataVersion.value += 1
  tab.value = 'report'
  return report
}

function onLocate(rowNo?: number) {
  highlightRow.value = rowNo
  tab.value = 'boq'
}

/** 归档（F1 流程终点）：报告确认后归档留痕 */
async function archive() {
  if (!numberId) {
    return
  }
  await projectApi.transit(numberId, 'ARCHIVED')
  ElMessage.success('项目已归档')
  await refreshOverview()
}

onMounted(async () => {
  await loadAll()
  const queryTab = route.query.tab as string | undefined
  if (queryTab) {
    tab.value = queryTab
  }
  const row = route.query.row as string | undefined
  if (row) {
    highlightRow.value = Number(row)
    tab.value = 'boq'
  }
})

onBeforeUnmount(stopPoll)
</script>

<style scoped>
.actions {
  display: flex;
  gap: 8px;
}
.meta {
  margin: 16px 0;
}
.tabs {
  background: #fff;
  padding: 0 16px 16px;
  border-radius: 4px;
}
</style>
