<template>
  <div>
    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="搜索图纸名称 / 规格"
        clearable
        style="width: 220px"
        @keyup.enter="refreshAll"
      />
      <el-select v-model="matchFilter" placeholder="全部匹配结果" clearable style="width: 150px">
        <el-option label="名称一致" value="LEVEL1" />
        <el-option label="相似关联" value="LEVEL2" />
        <el-option label="清单无此项" value="UNMATCHED" />
      </el-select>
      <el-select v-model="diffFilter" placeholder="全部数量对比" clearable style="width: 150px">
        <el-option label="数量一致" value="CONSISTENT" />
        <el-option label="图纸偏大" value="MORE" />
        <el-option label="图纸偏小" value="LESS" />
        <el-option label="无法比较" value="NA" />
      </el-select>
      <el-checkbox v-model="unmatchedOnly" size="small">只看清单未匹配</el-checkbox>
      <el-button type="primary" :loading="drawingLoading" @click="refreshAll">查询</el-button>
      <el-button @click="resetQuery">重置</el-button>
      <span class="summary">{{ drawingSummaryText }}</span>
      <el-button v-if="!drawingMaterials.length && !drawingLoading" @click="emit('go', 'files')">
        上传图纸
      </el-button>
    </div>

    <el-table
      ref="tableRef"
      v-loading="drawingLoading"
      :data="pagedRows"
      :row-class-name="rowClass"
      empty-text="暂无图纸清单：请先在「资料与解析」上传并解析竣工图 DXF/DWG"
      :height="tableHeight"
    >
      <el-table-column prop="name" label="图纸名称" width="220" class-name="wrap-cell" />
      <el-table-column label="规格" width="160" align="center" class-name="wrap-cell">
        <template #default="{ row }">{{ row.spec || '-' }}</template>
      </el-table-column>
      <el-table-column label="图纸数量" width="140" align="center" class-name="num-cell">
        <template #default="{ row }">{{ qty(row.quantity) }}</template>
      </el-table-column>
      <el-table-column prop="unit" label="单位" width="90" align="center" />
      <el-table-column label="所在图页" width="180" align="center" class-name="wrap-cell">
        <template #default="{ row }">
          <DrawingJump
            :sources="row.sources"
            :fallback="fallbackOf(row)"
            :material="row.name"
            :spec="row.spec"
          />
        </template>
      </el-table-column>
      <el-table-column label="匹配清单" min-width="220" align="center" class-name="wrap-cell">
        <template #default="{ row }">
          <template v-if="row.match">
            <el-tag v-if="row.manual" type="primary" size="small" effect="dark">手动</el-tag>
            <el-tag :type="row.level === 1 ? 'success' : 'warning'" size="small" effect="plain">
              {{ row.level === 1 ? '名称一致' : '相似关联' }}
            </el-tag>
            <span v-if="row.level === 2" class="score">{{ row.score?.toFixed(2) }}</span>
            <div class="cmp-line">第 {{ row.match.rowNo }} 行 {{ row.match.itemName }}</div>
          </template>
          <template v-else>
            <el-tag type="danger" size="small" effect="plain">清单无此项</el-tag>
          </template>
        </template>
      </el-table-column>
      <el-table-column label="匹配" width="140" align="center">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openMatch(row)">
            {{ row.match ? '匹配' : '手动匹配' }}
          </el-button>
          <el-button v-if="row.match" link type="danger" size="small" @click="unmatchRow(row)">
            取消匹配
          </el-button>
          <el-button v-if="overrideByKey.has(row.key)" link size="small" @click="autoMatchRow(row)">
            恢复自动
          </el-button>
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
      <el-table-column label="操作" width="160" fixed="right" class-name="action-cell">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button v-if="row.match" link type="primary" @click="locateBoq(row.match)">
            工程量清单
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      :current-page="page"
      :page-size="size"
      :total="drawingView.length"
      layout="total, sizes, prev, pager, next"
      :page-sizes="[20, 50, 100]"
      @current-change="(p: number) => (page = p)"
      @size-change="onSizeChange"
    />

    <MatchDialog
      v-model="matchVisible"
      :title="matchRow ? `为「${matchRow.name}」选择清单行` : '选择清单行'"
      :options="matchOptions"
      :current="matchRow?.match ? String(matchRow.match.id) : undefined"
      :can-unmatch="!!matchRow?.match"
      :can-auto="!!currentOverride"
      @confirm="confirmMatch"
      @unmatch="matchRow && unmatchRow(matchRow)"
      @auto="matchRow && autoMatchRow(matchRow)"
    />

    <el-dialog v-model="detailVisible" title="图纸材料详情" width="720px" align-center>
      <template v-if="detailRow">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="图纸名称" :span="2">{{ detailRow.name }}</el-descriptions-item>
          <el-descriptions-item label="规格">{{ detailRow.spec || '-' }}</el-descriptions-item>
          <el-descriptions-item label="单位">{{ detailRow.unit || '-' }}</el-descriptions-item>
          <el-descriptions-item label="图纸数量">
            {{ qty(detailRow.quantity) }}{{ detailRow.unit || '' }}
          </el-descriptions-item>
          <el-descriptions-item label="出现图纸">{{ detailRow.fileCount }} 张</el-descriptions-item>
          <el-descriptions-item label="图纸文件" :span="2">
            {{ detailRow.files.join('、') || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="所在图页" :span="2">
            <DrawingJump
              :sources="detailRow.sources"
              :fallback="fallbackOf(detailRow)"
              :material="detailRow.name"
              :spec="detailRow.spec"
              show-title
            />
          </el-descriptions-item>
        </el-descriptions>

        <h4 class="section">匹配清单</h4>
        <el-descriptions v-if="detailRow.match" :column="2" border size="small">
          <el-descriptions-item label="匹配方式">
            <el-tag v-if="detailRow.manual" type="primary" size="small" effect="dark">手动</el-tag>
            <el-tag :type="detailRow.level === 1 ? 'success' : 'warning'" size="small" effect="plain">
              {{ detailRow.level === 1 ? '名称一致' : '相似关联' }}
            </el-tag>
            <span v-if="detailRow.level === 2" class="score">
              {{ detailRow.score?.toFixed(2) }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="清单行号">第 {{ detailRow.match.rowNo }} 行</el-descriptions-item>
          <el-descriptions-item label="清单名称" :span="2">
            {{ detailRow.match.itemName }}
          </el-descriptions-item>
          <el-descriptions-item label="规格 / 特征">
            {{ detailRow.match.featureDesc || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="类别">{{ detailRow.match.itemTypeName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="清单数量">
            {{ qty(detailRow.match.quantity) }}{{ detailRow.match.unit || '' }}
          </el-descriptions-item>
          <el-descriptions-item label="数量差异">
            <span
              v-if="detailRow.diffRate !== null && detailRow.diffRate !== undefined"
              :class="['diff', detailRow.diffRate > 0 ? 'diff-more' : 'diff-less']"
            >
              {{ diffLabel(detailRow.diffRate) }}
            </span>
            <span v-else class="muted">单位不可换算，无法比较数量</span>
          </el-descriptions-item>
        </el-descriptions>
        <el-empty v-else description="工程量清单中没有找到对应项" :image-size="56" />
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage, type TableInstance } from 'element-plus'
import { boqApi, projectApi, type BoqItemVO, type FileVO } from '@/api'
import { useTableHeight } from '@/utils/useTableHeight'
import DrawingJump from './DrawingJump.vue'
import {
  applyMatchOverrides,
  compareDrawingWithBoq,
  diffState,
  matchScore,
  mergeDrawingMaterials,
  type DrawingFrameItem,
  type DrawingMaterial,
  type DrawingMaterialItem,
  type DrawingRow,
  type DrawingTableItem,
  type MatchOverride,
} from './drawingCompare'
import MatchDialog from './MatchDialog.vue'

/** 匹配弹窗选项：value 为清单行 id */
interface MatchOption {
  value: string
  label: string
  desc?: string
}

/** refreshKey 由父组件在核查完成后自增，Tab 已挂载时也能重新拉取图纸与清单匹配 */
const props = defineProps<{
  projectId: number
  refreshKey?: number
  /** 从工程量清单跳转过来时要定位的图纸材料（seq 变化即重新定位同一行） */
  focus?: { key: string; seq: number }
}>()
const emit = defineEmits<{
  (e: 'go', tab: string): void
  (e: 'count', value: number): void
  (e: 'changed'): void
  /** 跳到工程量清单并定位到这图纸材料匹配的清单行 */
  (e: 'locate-boq', rowNo: number): void
}>()

const all = ref<BoqItemVO[]>([])
const drawingMaterials = ref<DrawingMaterial[]>([])
const drawingLoading = ref(false)
const unmatchedOnly = ref(false)
/** 匹配结果筛选：名称一致 / 相似关联 / 清单无此项 */
const matchFilter = ref('')
/** 数量对比结果筛选：一致 / 图纸偏大 / 图纸偏小 / 无法比较 */
const diffFilter = ref('')
/** 按图纸名称 / 规格搜索 */
const keyword = ref('')
/** 图纸文件名 → 文件 id：旧解析结果没有图框信息时，至少能按文件名打开整张图 */
const fileIdsByName = ref<Record<string, number>>({})

const tableRef = ref<TableInstance>()
/** 表格高度跟随窗口变化，避免固定高度在大屏留白、小屏双重滚动 */
const tableHeight = useTableHeight(tableRef)
const page = ref(1)
const size = ref(50)

/** 人工匹配结论：手工指定 / 手工取消，覆盖自动匹配 */
const drawingOverrides = ref<MatchOverride[]>([])
/** 图纸材料 key → 人工结论：图纸清单侧靠它判断这一行是否被手工改过 */
const overrideByKey = computed(
  () =>
    new Map(
      drawingOverrides.value
        .filter((item) => item.drawingKey)
        .map((item) => [item.drawingKey as string, item]),
    ),
)

const comparisons = computed(() =>
  applyMatchOverrides(
    compareDrawingWithBoq(drawingMaterials.value, all.value),
    all.value,
    drawingOverrides.value,
  ),
)

/** 手工匹配弹窗：为当前图纸材料挑选清单行 */
const matchVisible = ref(false)
const matchRow = ref<DrawingRow | null>(null)

/** 图纸材料详情弹窗 */
const detailVisible = ref(false)
const detailRow = ref<DrawingRow | null>(null)

function openDetail(row: DrawingRow) {
  detailRow.value = row
  detailVisible.value = true
}
const matchOptions = computed<MatchOption[]>(() => {
  const material = matchRow.value
  const candidates = all.value.filter((item) => item.itemType !== 'FEE' && item.itemType !== 'OTHER')
  // 与当前图纸材料最符合的清单行排在最前面
  if (material) {
    candidates.sort((a, b) => matchScore(material, b) - matchScore(material, a))
  }
  return candidates.map((item) => ({
    value: String(item.id),
    label: `第 ${item.rowNo ?? '-'} 行 ${item.itemName}`,
    desc: `${qty(item.quantity)}${item.unit || ''}${item.itemTypeName ? ' · ' + item.itemTypeName : ''}`,
  }))
})
/** 当前行的人工结论（用于「恢复自动匹配」） */
const currentOverride = computed(() =>
  matchRow.value ? overrideByKey.value.get(matchRow.value.key) : undefined,
)

function openMatch(row: DrawingRow) {
  matchRow.value = row
  matchVisible.value = true
}

async function reloadOverrides() {
  drawingOverrides.value = await boqApi.drawingMatches(props.projectId)
}

/** 跳到工程量清单并定位到这条图纸材料匹配的清单行 */
function locateBoq(item?: BoqItemVO) {
  if (item?.rowNo !== undefined) {
    emit('locate-boq', item.rowNo)
  }
}

async function confirmMatch(value: string) {
  if (!matchRow.value) {
    return
  }
  await boqApi.saveDrawingMatch(props.projectId, Number(value), matchRow.value.key, 'MATCH')
  matchVisible.value = false
  await reloadOverrides()
  emit('changed')
  ElMessage.success('已保存人工匹配')
}

/** 取消这条图纸材料的清单匹配 */
async function unmatchRow(row: { key: string; match?: BoqItemVO }) {
  if (!row.match) {
    return
  }
  await boqApi.saveDrawingMatch(props.projectId, row.match.id, row.key, 'UNMATCH')
  matchVisible.value = false
  await reloadOverrides()
  emit('changed')
  ElMessage.success('已取消该图纸材料的清单匹配')
}

async function autoMatchRow(row: { key: string }) {
  const override = overrideByKey.value.get(row.key)
  if (!override) {
    return
  }
  await boqApi.resetDrawingMatch(props.projectId, override.boqItemId)
  matchVisible.value = false
  await reloadOverrides()
  emit('changed')
  ElMessage.success('已恢复自动匹配')
}

const drawingView = computed(() => {
  let rows = comparisons.value.rows
  const kw = keyword.value.trim()
  if (kw) {
    rows = rows.filter((row) => row.name.includes(kw) || row.spec.includes(kw))
  }
  if (unmatchedOnly.value) {
    rows = rows.filter((row) => !row.match)
  }
  if (matchFilter.value === 'LEVEL1') {
    rows = rows.filter((row) => row.level === 1)
  } else if (matchFilter.value === 'LEVEL2') {
    rows = rows.filter((row) => row.level === 2)
  } else if (matchFilter.value === 'UNMATCHED') {
    rows = rows.filter((row) => !row.match)
  }
  if (diffFilter.value) {
    // 未匹配清单的行没有数量对比结论
    rows = rows.filter((row) => row.match && diffState(row.diffRate) === diffFilter.value)
  }
  return rows
})

const pagedRows = computed(() =>
  drawingView.value.slice((page.value - 1) * size.value, page.value * size.value),
)

/** 当前定位到的图纸材料 key，用于滚动与高亮 */
const focusRowKey = ref('')

function onSizeChange(next: number) {
  size.value = next
  page.value = 1
}

function rowClass({ row }: { row: { key: string } }) {
  return focusRowKey.value && row.key === focusRowKey.value ? 'focus-row' : ''
}

/** 从工程量清单跳过来：清掉筛选、翻到所在页、滚动并高亮这一行 */
function locateFocus() {
  const key = props.focus?.key
  if (!key) {
    return
  }
  unmatchedOnly.value = false
  matchFilter.value = ''
  diffFilter.value = ''
  keyword.value = ''
  nextTick(() => {
    const index = drawingView.value.findIndex((row) => row.key === key)
    if (index < 0) {
      return
    }
    focusRowKey.value = key
    page.value = Math.floor(index / size.value) + 1
    nextTick(() => scrollToRowInPage(index % size.value))
  })
}

/** 表格内部是 el-scrollbar，按目标行的相对偏移设置滚动位置 */
function scrollToRowInPage(indexInPage: number) {
  const root = tableRef.value?.$el as HTMLElement | undefined
  const wrap = root?.querySelector('.el-table__body-wrapper .el-scrollbar__wrap') as HTMLElement | null
  const rowEl = wrap?.querySelectorAll('.el-table__row')[indexInPage] as HTMLElement | undefined
  if (!wrap || !rowEl) {
    return
  }
  const delta = rowEl.getBoundingClientRect().top - wrap.getBoundingClientRect().top
  wrap.scrollTop += delta - 60
}

watch(() => props.focus, locateFocus, { flush: 'post' })

watch([unmatchedOnly, matchFilter, diffFilter, keyword], () => {
  page.value = 1
})

/** 行数与清单对照后才确定，同步给父级 Tab 标题展示总数 */
watch(
  () => comparisons.value.rows.length,
  (value) => emit('count', value),
  { immediate: true },
)

watch(drawingView, (rows) => {
  // 筛选或重新解析后当前页可能越界，回落最后一页
  page.value = Math.min(page.value, Math.max(1, Math.ceil(rows.length / size.value)))
})

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
    locateFocus()
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
  return Promise.all([load(), loadDrawings(), reloadOverrides()])
}

/** 重置查询条件并重新取数 */
async function resetQuery() {
  keyword.value = ''
  matchFilter.value = ''
  diffFilter.value = ''
  unmatchedOnly.value = false
  page.value = 1
  await refreshAll()
}

/** 与工程量清单一致：按实际值展示（最多 6 位小数），不改写数值 */
function qty(value?: number | null) {
  if (value === undefined || value === null || Number.isNaN(Number(value))) {
    return '-'
  }
  return Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 6 })
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
.section {
  margin: 14px 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}
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
.pager {
  margin-top: 12px;
  justify-content: flex-end;
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
:deep(.action-cell .cell) {
  white-space: nowrap;
  padding: 4px 8px;
}
/* 从工程量清单跳转过来时闪两下，避免只翻页时用户找不到目标行 */
:deep(.focus-row) {
  background-color: #fdf6ec !important;
  animation: locate-flash 1.1s ease-in-out 2;
}
@keyframes locate-flash {
  0%,
  100% {
    background-color: #fdf6ec;
  }
  50% {
    background-color: #fad9a8;
  }
}
</style>
