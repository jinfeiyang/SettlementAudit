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
            @change="onProjectChange"
          >
            <el-option v-for="p in projects" :key="p.id" :label="p.projectName" :value="p.id" />
          </el-select>
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-table
      ref="tableRef"
      v-loading="loading"
      :data="pagedReports"
      empty-text="暂无报告，可在项目作业页生成初稿"
      :height="tableHeight"
    >
      <el-table-column prop="projectName" label="项目" min-width="300" class-name="wrap-cell" />
      <el-table-column prop="title" label="报告标题" min-width="300" class-name="wrap-cell" />
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
      <el-table-column label="操作" width="300" fixed="right">
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
          <el-button
            link
            type="danger"
            :disabled="row.status === 'CONFIRMED'"
            @click="remove(row)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      :current-page="page"
      :page-size="size"
      :total="reports.length"
      layout="total, sizes, prev, pager, next, jumper"
      :page-sizes="[10, 20, 50]"
      @current-change="(p: number) => (page = p)"
      @size-change="onSizeChange"
    />

    <el-dialog
      v-model="previewVisible"
      :title="current?.title || '报告预览'"
      width="70%"
      align-center
      destroy-on-close
    >
      <div class="report-body">
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
                min-width="120"
                class-name="wrap-cell"
              />
            </el-table>
            <template v-else>
              <p
                v-for="(line, lineIndex) in reportTextLines(section.text)"
                :key="lineIndex"
                :class="['text', { 'text-list': line.list }]"
              >
                {{ line.text }}
              </p>
            </template>
          </section>
        </div>
        <el-empty v-else description="报告内容为空" />
      </div>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox, type TableInstance } from 'element-plus'
import { projectApi, reportApi, type ProjectVO, type ReportContent, type ReportSection, type ReportVO } from '@/api'
import { datetime, statusTag } from '@/utils/format'
import { reportTextLines } from '@/utils/report'
import { useAppStore } from '@/stores/app'
import { useTableHeight } from '@/utils/useTableHeight'

const store = useAppStore()
const reports = ref<ReportVO[]>([])
const projects = ref<ProjectVO[]>([])
const projectId = ref<number>()
const loading = ref(false)
/** 表格高度跟随窗口，50 条/页时也不会撑出可视区域 */
const tableRef = ref<TableInstance>()
const tableHeight = useTableHeight(tableRef)
const previewVisible = ref(false)
const current = ref<ReportVO>()
const sections = ref<ReportSection[]>([])
const page = ref(1)
const size = ref(20)

const pagedReports = computed(() =>
  reports.value.slice((page.value - 1) * size.value, page.value * size.value),
)

async function load() {
  loading.value = true
  try {
    reports.value = await reportApi.list(projectId.value)
    // 删除或筛选后当前页可能越界，回落最后一页
    page.value = Math.min(page.value, Math.max(1, Math.ceil(reports.value.length / size.value)))
  } finally {
    loading.value = false
  }
}

function onProjectChange() {
  page.value = 1
  load()
}

function onSizeChange(value: number) {
  size.value = value
  page.value = 1
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

async function remove(row: ReportVO) {
  try {
    await ElMessageBox.confirm(`确定删除报告「${row.title}」（V${row.version}）吗？此操作不可恢复。`, '删除报告', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  await reportApi.remove(row.id)
  ElMessage.success('报告已删除')
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
  align-items: center;
  gap: 8px;
}
.pager {
  margin-top: 16px;
  justify-content: flex-end;
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
.report-body {
  max-height: 70vh;
  overflow: auto;
  padding-right: 6px;
}
.text {
  margin: 4px 0;
  line-height: 1.9;
  white-space: pre-wrap;
  color: #303133;
}
/* 正文段落首行缩进两字 */
.text:not(.text-list) {
  text-indent: 2em;
}
/* 分条罗列不缩进首行，悬挂缩进让序号对齐 */
.text-list {
  padding-left: 1.6em;
  text-indent: -1.6em;
}
/* 项目名与报告标题偏长，省略号截断后看不全，改为换行完整展示 */
:deep(.wrap-cell .cell) {
  white-space: normal;
  word-break: break-all;
  line-height: 1.45;
  padding: 4px 8px;
}
</style>
