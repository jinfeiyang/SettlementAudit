<template>
  <div>
    <div class="toolbar">
      <el-button type="primary" :loading="generating" @click="generate">生成报告初稿</el-button>
      <el-button @click="load">刷新</el-button>
      <span class="hint">
        报告由确定性核查结果装配，AI 仅成文建议（不修改金额、不下结论）；确认需项目经理账号
      </span>
    </div>

    <el-table v-loading="loading" :data="reports" empty-text="暂无报告，核查完成后可生成初稿">
      <el-table-column prop="title" label="报告标题" min-width="260" show-overflow-tooltip />
      <el-table-column label="版本" width="90" align="center">
        <template #default="{ row }">V{{ row.version }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ row.statusName }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="成文方式" width="140">
        <template #default="{ row }">{{ row.aiModel ? `AI（${row.aiModel}）` : '模板成文' }}</template>
      </el-table-column>
      <el-table-column label="生成时间" width="170">
        <template #default="{ row }">{{ datetime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="确认时间" width="170">
        <template #default="{ row }">{{ datetime(row.confirmTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="preview(row)">预览</el-button>
          <el-button link type="primary" :disabled="row.status !== 'DRAFT'" @click="openEdit(row)">
            编辑
          </el-button>
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
            <span>版本 V{{ current?.version }}</span>
            <span>状态：{{ current?.statusName }}</span>
            <span v-if="current?.aiModel">成文模型：{{ current.aiModel }}</span>
          </div>
          <section v-for="(section, index) in sections" :key="index">
            <h3>{{ section.title }}</h3>
            <template v-if="section.type === 'table'">
              <el-table :data="toTableData(section)" size="small" border>
                <el-table-column
                  v-for="(col, colIndex) in section.columns || []"
                  :key="colIndex"
                  :prop="`c${colIndex}`"
                  :label="col"
                  min-width="110"
                  show-overflow-tooltip
                />
              </el-table>
            </template>
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

    <el-dialog v-model="editVisible" title="编辑报告初稿" width="760px">
      <el-form label-width="90px">
        <el-form-item label="报告标题">
          <el-input v-model="editTitle" />
        </el-form-item>
        <el-form-item v-for="(item, index) in editTexts" :key="index" :label="item.title">
          <el-input v-model="item.text" type="textarea" :rows="4" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { reportApi, type ReportContent, type ReportSection, type ReportVO } from '@/api'
import { datetime, statusTag } from '@/utils/format'
import { reportTextLines } from '@/utils/report'
import { useAppStore } from '@/stores/app'

/** refreshKey 由父组件在生成报告后自增，Tab 已挂载时也能刷新列表 */
const props = defineProps<{ projectId: number; refreshKey?: number }>()
const emit = defineEmits<{ (e: 'changed'): void }>()

const store = useAppStore()
const reports = ref<ReportVO[]>([])
const loading = ref(false)
const generating = ref(false)
const saving = ref(false)
const previewVisible = ref(false)
const editVisible = ref(false)
const current = ref<ReportVO>()
const editTitle = ref('')
const editTexts = ref<{ title: string; text: string; index: number }[]>([])

const sections = computed<ReportSection[]>(() => parseContent(current.value?.content))

function parseContent(raw?: string): ReportSection[] {
  if (!raw) {
    return []
  }
  try {
    const parsed = JSON.parse(raw) as ReportContent
    return parsed.sections ?? []
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

async function load() {
  loading.value = true
  try {
    reports.value = await reportApi.list(props.projectId)
  } finally {
    loading.value = false
  }
}

async function generate() {
  generating.value = true
  try {
    const report = await reportApi.generate(props.projectId)
    ElMessage.success(`报告初稿已生成（V${report.version}）`)
    await load()
    emit('changed')
  } finally {
    generating.value = false
  }
}

function preview(row: ReportVO) {
  current.value = row
  previewVisible.value = true
}

function openEdit(row: ReportVO) {
  current.value = row
  editTitle.value = row.title
  const list = parseContent(row.content)
  editTexts.value = list
    .map((section, index) => ({ title: section.title, text: section.text || '', index }))
    .filter((item) => item.text !== '')
  editVisible.value = true
}

async function save() {
  if (!current.value) {
    return
  }
  saving.value = true
  try {
    const list = parseContent(current.value.content)
    editTexts.value.forEach((item) => {
      const section = list[item.index]
      if (section) {
        section.text = item.text
      }
    })
    const content: ReportContent = { sections: list }
    await reportApi.update(current.value.id, { title: editTitle.value, content: JSON.stringify(content) })
    ElMessage.success('已保存')
    editVisible.value = false
    await load()
    emit('changed')
  } finally {
    saving.value = false
  }
}

async function confirm(row: ReportVO) {
  await reportApi.confirm(row.id)
  ElMessage.success('报告已确认')
  await load()
  emit('changed')
}

function exportWord(row: ReportVO) {
  reportApi.exportWord(row.id, row.title)
}

onMounted(load)
watch(() => props.refreshKey, () => load())
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.hint {
  color: #909399;
  font-size: 12px;
}
.report-body {
  max-height: 70vh;
  overflow: auto;
  padding-right: 6px;
}
.report {
  padding: 0 8px;
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
  margin: 4px 0;
  line-height: 1.9;
  white-space: pre-wrap;
  color: #303133;
}
/* 正文段落首行缩进两字 */
.text:not(.text-list) {
  text-indent: 2em;
}
/* 分条罗列不缩进首行，用悬挂缩进让序号对齐，别出现「　　1. 依据」这种空洞排版 */
.text-list {
  padding-left: 1.6em;
  text-indent: -1.6em;
}
</style>
