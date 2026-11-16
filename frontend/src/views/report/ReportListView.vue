<template>
  <el-card shadow="never">
    <template #header>
      <div class="header-row">
        <span>预审报告管理（初稿成文 / 复核确认 / Word 导出）</span>
        <div class="right">
          <el-select
            v-model="projectId"
            placeholder="全部项目"
            clearable
            filterable
            style="width: 240px"
            @change="load"
          >
            <el-option v-for="p in projects" :key="p.id" :label="p.projectName" :value="p.id" />
          </el-select>
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-table v-loading="loading" :data="reports" empty-text="暂无报告，可在项目作业页生成初稿">
      <el-table-column prop="projectName" label="项目" width="220" show-overflow-tooltip />
      <el-table-column prop="title" label="报告标题" min-width="240" show-overflow-tooltip />
      <el-table-column label="版本" width="80" align="center">
        <template #default="{ row }">V{{ row.version }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ row.statusName }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="成文方式" width="150">
        <template #default="{ row }">{{ row.aiModel ? `AI（${row.aiModel}）` : '模板成文' }}</template>
      </el-table-column>
      <el-table-column label="生成时间" width="170">
        <template #default="{ row }">{{ datetime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="preview(row)">预览</el-button>
          <el-button link type="primary" @click="exportWord(row)">导出 Word</el-button>
          <el-button
            link
            type="success"
            :disabled="!store.isManager || row.status === 'CONFIRMED'"
            @click="confirm(row)"
          >
            确认
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="previewVisible" :title="current?.title || '报告预览'" size="52%">
      <div v-if="sections.length" class="report">
        <h2 class="report-title">{{ current?.title }}</h2>
        <div class="report-meta">
          <span>项目：{{ current?.projectName }}</span>
          <span>版本 V{{ current?.version }}</span>
          <span>状态：{{ current?.statusName }}</span>
        </div>
        <section v-for="(section, index) in sections" :key="index">
          <h3>{{ section.title }}</h3>
          <el-table v-if="section.type === 'table'" :data="toTableData(section)" size="small" border>
            <el-table-column
              v-for="(col, colIndex) in section.columns || []"
              :key="colIndex"
              :prop="`c${colIndex}`"
              :label="col"
              min-width="110"
              show-overflow-tooltip
            />
          </el-table>
          <p v-else class="text">{{ section.text }}</p>
        </section>
      </div>
      <el-empty v-else description="报告内容为空" />
    </el-drawer>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { projectApi, reportApi, type ProjectVO, type ReportContent, type ReportSection, type ReportVO } from '@/api'
import { datetime, statusTag } from '@/utils/format'
import { useAppStore } from '@/stores/app'

const store = useAppStore()
const reports = ref<ReportVO[]>([])
const projects = ref<ProjectVO[]>([])
const projectId = ref<number>()
const loading = ref(false)
const previewVisible = ref(false)
const current = ref<ReportVO>()
const sections = ref<ReportSection[]>([])

async function load() {
  loading.value = true
  try {
    reports.value = await reportApi.list(projectId.value)
  } finally {
    loading.value = false
  }
}

function preview(row: ReportVO) {
  current.value = row
  sections.value = parseContent(row.content)
  previewVisible.value = true
}

function parseContent(raw?: string): ReportSection[] {
  if (!raw) {
    return []
  }
  try {
    return (JSON.parse(raw) as ReportContent).sections ?? []
  } catch {
    return []
  }
}

function toTableData(section: ReportSection) {
  return (section.rows ?? []).map((row) => {
    const record: Record<string, string> = {}
    row.forEach((cell, index) => {
      record[`c${index}`] = cell
    })
    return record
  })
}

async function confirm(row: ReportVO) {
  await reportApi.confirm(row.id)
  ElMessage.success('报告已确认')
  await load()
}

function exportWord(row: ReportVO) {
  reportApi.exportWord(row.id, row.title)
}

onMounted(async () => {
  const res = await projectApi.page({ page: 1, size: 100 })
  projects.value = res.records
  await load()
})
</script>

<style scoped>
.header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.right {
  display: flex;
  gap: 8px;
}
.report-title {
  text-align: center;
  margin: 0 0 8px;
}
.report-meta {
  display: flex;
  gap: 16px;
  justify-content: center;
  color: #909399;
  font-size: 12px;
  margin-bottom: 16px;
}
h3 {
  margin: 18px 0 8px;
}
.text {
  line-height: 1.9;
  white-space: pre-wrap;
  text-indent: 2em;
  color: #303133;
}
</style>
