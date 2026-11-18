<template>
  <div>
    <div class="toolbar">
      <span class="summary">{{ drawingSummaryText }}</span>
      <el-button v-if="!drawingMaterials.length && !drawingLoading" @click="emit('go', 'files')">
        上传图纸
      </el-button>
      <el-button :loading="drawingLoading" @click="refreshAll">刷新</el-button>
      <el-checkbox v-model="unmatchedOnly" size="small">只看清单未匹配</el-checkbox>
    </div>

    <el-table
      v-loading="drawingLoading"
      :data="drawingView"
      empty-text="暂无图纸清单：请先在「资料与解析」上传并解析竣工图 DXF/DWG"
      max-height="520"
    >
      <el-table-column prop="name" label="图纸名称" width="300" class-name="wrap-cell" />
      <el-table-column label="规格" width="200" align="center" class-name="wrap-cell">
        <template #default="{ row }">{{ row.spec || '-' }}</template>
      </el-table-column>
      <el-table-column label="图纸数量" width="200" align="center" class-name="num-cell">
        <template #default="{ row }">{{ qty(row.quantity) }}</template>
      </el-table-column>
      <el-table-column prop="unit" label="单位" width="200" align="center" />
      <el-table-column label="所在图页" width="180" align="center" class-name="wrap-cell">
        <template #default="{ row }">
          <DrawingJump :sources="row.sources" :fallback="fallbackOf(row)" show-title />
        </template>
      </el-table-column>
      <el-table-column label="匹配清单" min-width="220" align="center" class-name="wrap-cell">
        <template #default="{ row }">
          <template v-if="row.match">
            <el-tag :type="row.level === 1 ? 'success' : 'warning'" size="small" effect="plain">
              {{ row.level === 1 ? '名称一致' : '相似关联' }}
            </el-tag>
            <span v-if="row.level === 2" class="score">{{ row.score?.toFixed(2) }}</span>
            <div class="cmp-line">第 {{ row.match.rowNo }} 行 {{ row.match.itemName }}</div>
          </template>
          <el-tag v-else type="danger" size="small" effect="plain">清单无此项</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="数量对比" width="180" align="center" class-name="wrap-cell">
        <template #default="{ row }">
          <template v-if="row.match">
            <div class="cmp-line">清单 {{ qty(row.match.quantity) }}{{ row.match.unit || '' }}</div>
            <div
              v-if="row.diffRate !== null && row.diffRate !== undefined"
              :class="['cmp-line', 'diff', row.diffRate > 0 ? 'diff-more' : 'diff-less']"
            >
              {{ diffLabel(row.diffRate) }}
            </div>
          </template>
          <span v-else class="muted">-</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { boqApi, projectApi, type BoqItemVO, type FileVO } from '@/api'
import DrawingJump from './DrawingJump.vue'
import {
  compareDrawingWithBoq,
  mergeDrawingMaterials,
  type DrawingFrameItem,
  type DrawingMaterial,
  type DrawingMaterialItem,
  type DrawingTableItem,
} from './drawingCompare'

/** refreshKey 由父组件在核查完成后自增，Tab 已挂载时也能重新拉取图纸与清单匹配 */
const props = defineProps<{ projectId: number; refreshKey?: number }>()
const emit = defineEmits<{ (e: 'go', tab: string): void }>()

const all = ref<BoqItemVO[]>([])
const drawingMaterials = ref<DrawingMaterial[]>([])
const drawingLoading = ref(false)
const unmatchedOnly = ref(false)
/** 图纸文件名 → 文件 id：旧解析结果没有图框信息时，至少能按文件名打开整张图 */
const fileIdsByName = ref<Record<string, number>>({})

const comparisons = computed(() => compareDrawingWithBoq(drawingMaterials.value, all.value))

const drawingView = computed(() =>
  unmatchedOnly.value ? comparisons.value.rows.filter((row) => !row.match) : comparisons.value.rows,
)

const drawingSummaryText = computed(() => {
  if (drawingLoading.value && !drawingMaterials.value.length) {
    return '正在读取图纸材料表…'
  }
  if (!drawingMaterials.value.length) {
    return '取自图纸材料表汇总'
  }
  return `共 ${drawingMaterials.value.length} 项 · 已对比 ${comparisons.value.matched} · 未匹配 ${comparisons.value.unmatched}`
})

async function load() {
  all.value = await boqApi.all(props.projectId)
}

/** 读取本项目所有已解析竣工图，汇总材料表（名称+规格+单位相同视为同一种材料） */
async function loadDrawings() {
  drawingLoading.value = true
  try {
    const files: FileVO[] = await projectApi.files(props.projectId)
    const drawings = files.filter(
      (file) => (file.fileType === 'DXF' || file.fileType === 'DWG') && file.parseStatus === 'SUCCESS',
    )
    fileIdsByName.value = Object.fromEntries(drawings.map((file) => [file.fileName, file.id]))
    const results = await Promise.all(
      drawings.map(async (file) => {
        try {
          const data = await projectApi.parseResult(file.id)
          return {
            fileId: file.id,
            fileName: file.fileName,
            summary: data?.materialSummary as DrawingMaterialItem[] | undefined,
            // 材料明细带图框号，图框带张号/图名/坐标：两者一起才能标出「这条材料画在哪一页」
            tables: (data?.materials ?? []) as DrawingTableItem[],
            frames: (data?.frames ?? []) as DrawingFrameItem[],
          }
        } catch {
          return { fileId: file.id, fileName: file.fileName }
        }
      }),
    )
    drawingMaterials.value = mergeDrawingMaterials(results)
  } finally {
    drawingLoading.value = false
  }
}

/** 旧解析结果（没有图页信息）时按文件名找回图纸，至少能打开整张图 */
function fallbackOf(row: DrawingMaterial) {
  const fileName = row.files[0]
  const fileId = fileName ? fileIdsByName.value[fileName] : undefined
  return fileId ? { fileId, fileName } : null
}

function refreshAll() {
  return Promise.all([load(), loadDrawings()])
}

function qty(value?: number | null) {
  if (value === undefined || value === null || Number.isNaN(Number(value))) {
    return '-'
  }
  return Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 3 })
}

function diffLabel(rate: number) {
  if (Math.abs(rate) < 0.5) {
    return '数量一致'
  }
  return `${rate > 0 ? '图纸偏大' : '图纸偏小'} ${Math.abs(rate).toFixed(1)}%`
}

watch(() => props.refreshKey, () => refreshAll())
onMounted(refreshAll)
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.summary {
  flex: 1 1 auto;
  color: #606266;
  font-size: 13px;
}
.cmp-line {
  color: #606266;
  font-size: 11px;
  line-height: 1.4;
  word-break: break-all;
}
.diff {
  margin-left: 4px;
  font-size: 11px;
}
.diff-more {
  color: #f56c6c;
}
.diff-less {
  color: #e6a23c;
}
.score {
  margin-left: 4px;
  color: #909399;
  font-size: 11px;
}
.muted {
  color: #a8abb2;
  font-size: 12px;
}
:deep(.num-cell .cell) {
  font-variant-numeric: tabular-nums;
}
/* 与工程量清单一致：单元格统一换行，名称列留出内边距 */
:deep(.el-table .cell) {
  white-space: normal;
  word-break: break-all;
  line-height: 1.45;
}
:deep(.wrap-cell .cell) {
  padding: 4px 8px;
}
</style>
