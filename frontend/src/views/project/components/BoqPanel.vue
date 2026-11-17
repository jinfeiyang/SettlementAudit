<template>
  <div class="compare">
    <aside class="pane pane-drawing">
      <div class="pane-head">
        <span class="pane-title">图纸清单</span>
        <span class="pane-sub">{{ drawingSummaryText }}</span>
        <el-button
          v-if="!drawingMaterials.length && !drawingLoading"
          link
          type="primary"
          @click="$emit('go', 'files')"
        >
          上传图纸
        </el-button>
        <el-button link type="primary" :loading="drawingLoading" @click="loadDrawings">刷新</el-button>
      </div>
      <div class="pane-filter">
        <el-checkbox v-model="unmatchedOnly" size="small">只看清单未匹配</el-checkbox>
      </div>
      <el-table
        v-loading="drawingLoading"
        :data="drawingView"
        size="small"
        height="520"
        empty-text="暂无图纸清单：请先在「资料与解析」上传并解析竣工图 DXF/DWG"
      >
        <el-table-column label="图纸名称 / 规格" min-width="130">
          <template #default="{ row }">
            <div class="mat-name">{{ row.name }}</div>
            <div v-if="row.spec" class="mat-spec">{{ row.spec }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="unit" label="单位" width="52" align="center" />
        <el-table-column label="图纸数量" width="86" align="right" class-name="num-cell">
          <template #default="{ row }">{{ qty(row.quantity) }}</template>
        </el-table-column>
        <el-table-column label="清单匹配" width="170">
          <template #default="{ row }">
            <template v-if="row.match">
              <el-tag :type="row.level === 1 ? 'success' : 'warning'" size="small" effect="plain">
                {{ row.level === 1 ? '名称一致' : '相似关联' }}
              </el-tag>
              <span v-if="row.level === 2" class="score">{{ row.score?.toFixed(2) }}</span>
              <div class="cmp-line">第 {{ row.match.rowNo }} 行 {{ row.match.itemName }}</div>
              <div class="cmp-line">
                清单 {{ qty(row.match.quantity) }}{{ row.match.unit || '' }}
                <span v-if="row.diffRate !== null && row.diffRate !== undefined" :class="['diff', row.diffRate > 0 ? 'diff-more' : 'diff-less']">
                  {{ diffLabel(row.diffRate) }}
                </span>
              </div>
            </template>
            <el-tag v-else type="danger" size="small" effect="plain">清单无此项</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </aside>

    <section class="pane pane-boq">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="搜索清单名称 / 编号 / 特征"
          clearable
          style="width: 240px"
        />
        <el-select v-model="typeFilter" placeholder="全部类别" clearable style="width: 140px">
          <el-option label="工程量" value="QUOTA" />
          <el-option label="甲供材料" value="SUPPLY_MATERIAL" />
          <el-option label="甲供设备" value="SUPPLY_EQUIPMENT" />
        </el-select>
        <el-button @click="refreshAll">刷新</el-button>
        <el-button @click="boqApi.template(projectId)">下载清单模板</el-button>
        <el-button @click="$emit('go', 'files')">上传/解析清单</el-button>
        <el-button type="danger" plain @click="clearAll">清空清单</el-button>
        <el-checkbox v-model="drawingUnmatchedOnly" size="small">只看图纸未匹配</el-checkbox>
        <span v-if="highlightRow" class="hint">
          已定位清单第 {{ highlightRow }} 行
          <el-button link type="primary" @click="clearHighlight">取消高亮</el-button>
        </span>
      </div>

      <el-table
        v-loading="loading"
        :data="pageRows"
        :row-class-name="rowClass"
        empty-text="暂无清单数据，请先在「资料与解析」上传清单 Excel"
        max-height="520"
      >
        <el-table-column prop="rowNo" label="行号" width="66" align="center" />
        <el-table-column label="类别" width="96">
          <template #default="{ row }">
            <el-tag :type="typeTag(row.itemType)" size="small" effect="plain">
              {{ row.itemTypeName || '工程量' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="定额编号" width="112">
          <template #default="{ row }">{{ quotaNo(row) }}</template>
        </el-table-column>
        <el-table-column prop="itemName" label="名称" min-width="180" class-name="wrap-cell" />
        <el-table-column prop="featureDesc" label="规格 / 特征" min-width="180" class-name="wrap-cell" />
        <el-table-column prop="unit" label="单位" width="66" />
        <el-table-column label="清单工程量" width="104" align="right" class-name="num-cell">
          <template #default="{ row }">{{ qty(row.quantity) }}</template>
        </el-table-column>
        <el-table-column label="综合单价" width="104" align="right">
          <template #default="{ row }">{{ money(row.unitPrice) }}</template>
        </el-table-column>
        <el-table-column label="合价" width="112" align="right">
          <template #default="{ row }">{{ money(row.totalPrice) }}</template>
        </el-table-column>
        <el-table-column label="来源表" width="120">
          <template #default="{ row }">{{ row.sourceSheet || '-' }}</template>
        </el-table-column>
        <el-table-column label="图纸匹配" width="230">
          <template #default="{ row }">
            <template v-if="row.drawing">
              <el-tag :type="row.drawing.level === 1 ? 'success' : 'warning'" size="small" effect="plain">
                {{ row.drawing.level === 1 ? '名称一致' : '相似关联' }}
              </el-tag>
              <span v-if="row.drawing.level === 2" class="score">{{ row.drawing.score?.toFixed(2) }}</span>
              <div class="cmp-line">
                {{ row.drawing.name }}
                <span v-if="row.drawing.spec" class="mat-spec">· {{ row.drawing.spec }}</span>
              </div>
              <div class="cmp-line">
                图纸 {{ qty(row.drawing.quantity) }}{{ row.drawing.unit }}
                <span
                  v-if="row.drawing.diffRate !== null && row.drawing.diffRate !== undefined"
                  :class="['diff', row.drawing.diffRate > 0 ? 'diff-more' : 'diff-less']"
                >
                  {{ diffLabel(row.drawing.diffRate) }}
                </span>
              </div>
            </template>
            <span v-else-if="!drawingMaterials.length" class="muted">图纸清单为空</span>
            <template v-else>
              <el-tag type="danger" size="small" effect="plain">图纸无对应</el-tag>
              <div v-if="row.nearest" class="cmp-line muted">
                最近候选 {{ row.nearest.row.name }}（{{ row.nearest.score.toFixed(2) }}）
              </div>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="70" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="pager"
        :current-page="page"
        :page-size="size"
        :total="filtered.length"
        layout="total, sizes, prev, pager, next"
        :page-sizes="[20, 50, 100]"
        @current-change="(p: number) => (page = p)"
        @size-change="onSizeChange"
      />
    </section>

    <el-dialog v-model="detailVisible" title="清单行详情" width="760px" align-center>
      <template v-if="detailRow">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="行号">{{ detailRow.rowNo }}</el-descriptions-item>
          <el-descriptions-item label="类别">
            <el-tag :type="typeTag(detailRow.itemType)" size="small" effect="plain">
              {{ detailRow.itemTypeName || '工程量' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="定额编号">{{ quotaNo(detailRow) }}</el-descriptions-item>
          <el-descriptions-item label="单位">{{ detailRow.unit || '-' }}</el-descriptions-item>
          <el-descriptions-item label="名称" :span="2">{{ detailRow.itemName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="规格 / 特征" :span="2">
            {{ detailRow.featureDesc || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="清单工程量">{{ qty(detailRow.quantity) }}</el-descriptions-item>
          <el-descriptions-item label="来源表">{{ detailRow.sourceSheet || '-' }}</el-descriptions-item>
          <el-descriptions-item label="综合单价">{{ money(detailRow.unitPrice) }}</el-descriptions-item>
          <el-descriptions-item label="合价">{{ money(detailRow.totalPrice) }}</el-descriptions-item>
        </el-descriptions>

        <h4 class="section">图纸匹配</h4>
        <el-descriptions v-if="detailRow.drawing" :column="2" border size="small">
          <el-descriptions-item label="匹配方式">
            <el-tag :type="detailRow.drawing.level === 1 ? 'success' : 'warning'" size="small" effect="plain">
              {{ detailRow.drawing.level === 1 ? '名称一致' : '相似关联' }}
            </el-tag>
            <span v-if="detailRow.drawing.level === 2" class="score">
              {{ detailRow.drawing.score?.toFixed(2) }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="图纸材料">{{ detailRow.drawing.name || '-' }}</el-descriptions-item>
          <el-descriptions-item label="规格程式">{{ detailRow.drawing.spec || '-' }}</el-descriptions-item>
          <el-descriptions-item label="图纸数量">
            {{ qty(detailRow.drawing.quantity) }}{{ detailRow.drawing.unit }}
          </el-descriptions-item>
          <el-descriptions-item label="数量差异" :span="2">
            <span
              v-if="detailRow.drawing.diffRate !== null && detailRow.drawing.diffRate !== undefined"
              :class="['diff', detailRow.drawing.diffRate > 0 ? 'diff-more' : 'diff-less']"
            >
              {{ diffLabel(detailRow.drawing.diffRate) }}
            </span>
            <span v-else class="muted">单位不可换算，无法比较数量</span>
          </el-descriptions-item>
        </el-descriptions>
        <el-empty v-else description="图纸材料表中没有找到对应项" :image-size="56" />

        <h4 class="section">光缆长度核查（逐张图纸 vs 清单）</h4>
        <el-table
          :data="detailCableRows"
          size="small"
          border
          max-height="300"
          empty-text="暂无已解析图纸，请先在「资料与解析」解析竣工图"
        >
          <el-table-column prop="fileName" label="图纸" min-width="200" class-name="wrap-cell" />
          <el-table-column label="数据来源" width="180">
            <template #default="{ row }">
              <span class="muted">{{ cableSourceText(row.source) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="图纸计算（米）" width="130" align="right">
            <template #default="{ row }">{{ qty(row.check.computed) }}</template>
          </el-table-column>
          <el-table-column label="清单（米）" width="110" align="right">
            <template #default="{ row }">
              {{ row.check.reported === null ? '-' : qty(row.check.reported) }}
            </template>
          </el-table-column>
          <el-table-column label="差异（米）" width="120" align="right">
            <template #default="{ row }">
              <span v-if="row.check.diff === null || row.check.status === 'NO_BASIS'" class="muted">-</span>
              <span v-else :class="row.check.diff < -0.01 ? 'cable-less' : 'diff-ok'">
                {{ row.check.diff > 0 ? '+' : '' }}{{ qty(row.check.diff) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="结论" width="140">
            <template #default="{ row }">
              <el-tag
                :type="row.check.status === 'LESS'
                  ? 'danger'
                  : row.check.status === 'OK'
                    ? 'success'
                    : row.check.status === 'NO_BASIS'
                      ? 'info'
                      : 'warning'"
                size="small"
                effect="plain"
              >
                {{
                  row.check.status === 'LESS'
                    ? '少于图纸'
                    : row.check.status === 'OK'
                      ? '通过'
                      : row.check.status === 'NO_BASIS'
                        ? '口径不同'
                        : row.check.status === 'NO_BOQ'
                          ? '清单无光缆行'
                          : '图纸无量'
                }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <p class="cable-note">
          清单数量是整个项目的申报量，逐图核查用来看哪张图算出来的量对不上；
          比对口径：{{ detailCableRows[0]?.check.scope || '全部' }}。
        </p>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { boqApi, projectApi, type BoqItemVO, type FileVO } from '@/api'
import { money } from '@/utils/format'
import {
  cableCategory,
  cableSourceText,
  checkCableLength,
  toDrawingCable,
  type DrawingCable,
} from '@/utils/cableCheck'
import {
  compareDrawingWithBoq,
  mergeDrawingMaterials,
  type DrawingMaterial,
  type DrawingMaterialItem,
  type DrawingRow,
  type NearestMatch,
} from './drawingCompare'

interface BoqRow extends BoqItemVO {
  /** 与清单行最贴近的图纸材料（无匹配时为 undefined） */
  drawing?: DrawingRow
  /** 未匹配时给出分数最高的图纸候选，方便判断差在哪 */
  nearest?: NearestMatch
}

/** refreshKey 由父组件在核查完成后自增，Tab 已挂载时也能重新拉取清单与图纸匹配 */
const props = defineProps<{ projectId: number; highlightRow?: number; refreshKey?: number }>()
const emit = defineEmits<{ (e: 'changed'): void; (e: 'go', tab: string): void }>()

const all = ref<BoqItemVO[]>([])
const loading = ref(false)
const keyword = ref('')
/** 类别筛选：联通结算模板一张表会同时导入工程量、乙供材料、甲供物资 */
const typeFilter = ref('')
const page = ref(1)
const size = ref(50)

/** 图纸清单：竣工图材料表跨图纸汇总，作为清单的对照侧 */
const drawingMaterials = ref<DrawingMaterial[]>([])
/** 每张图纸各自的线缆长度：光缆长度核查必须逐图比，不能把多张图合成一个总量 */
const drawingCableFiles = ref<{ fileName: string; cable: DrawingCable }[]>([])
const drawingLoading = ref(false)
const unmatchedOnly = ref(false)
const drawingUnmatchedOnly = ref(false)

/** 清单行详情：页面太挤，图纸匹配与光缆长度核查都收到详情弹窗里看 */
const detailVisible = ref(false)
const detailRow = ref<BoqRow | null>(null)

const comparisons = computed(() => compareDrawingWithBoq(drawingMaterials.value, all.value))

const filtered = computed(() => {
  const kw = keyword.value.trim()
  return all.value.filter((item) => {
    if (typeFilter.value && (item.itemType || 'QUOTA') !== typeFilter.value) {
      return false
    }
    if (drawingUnmatchedOnly.value && comparisons.value.byBoqId.get(item.id)) {
      return false
    }
    if (!kw) {
      return true
    }
    return (
      item.itemName?.includes(kw) ||
      item.itemCode?.includes(kw) ||
      item.featureDesc?.includes(kw)
    )
  })
})

/** 清单行带上对应图纸材料，模板里就能直接判断「图纸匹配」是否命中 */
const pageRows = computed<BoqRow[]>(() => {
  const start = (page.value - 1) * size.value
  return filtered.value.slice(start, start + size.value).map((item) => ({
    ...item,
    drawing: comparisons.value.byBoqId.get(item.id),
    nearest: comparisons.value.byBoqId.get(item.id) ? undefined : nearestCandidate(item.id),
  }))
})

/** 候选分太低就不展示，免得满屏「最近候选」反而干扰 */
function nearestCandidate(boqId: number): NearestMatch | undefined {
  const nearest = comparisons.value.nearestByBoqId.get(boqId)
  return nearest && nearest.score >= 0.35 ? nearest : undefined
}

const drawingView = computed(() =>
  unmatchedOnly.value ? comparisons.value.rows.filter((row) => !row.match) : comparisons.value.rows,
)

/**
 * 详情弹窗里的光缆长度核查：逐张图纸各算一次，
 * 按该行的敷设方式取图纸对应类别的长度，取不到就按全部。
 */
const detailCableRows = computed(() => {
  const row = detailRow.value
  const scope = row ? cableCategory(`${row.itemName} ${row.featureDesc ?? ''}`) : undefined
  return drawingCableFiles.value.map((file) => ({
    fileName: file.fileName,
    source: file.cable.source,
    check: checkCableLength(file.cable, all.value, scope),
  }))
})

function openDetail(row: BoqRow) {
  detailRow.value = row
  detailVisible.value = true
}

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
  loading.value = true
  try {
    all.value = await boqApi.all(props.projectId)
    focusHighlight()
  } finally {
    loading.value = false
  }
}

/** 读取本项目所有已解析竣工图，汇总材料表（名称+规格+单位相同视为同一种材料） */
async function loadDrawings() {
  drawingLoading.value = true
  try {
    const files: FileVO[] = await projectApi.files(props.projectId)
    const drawings = files.filter(
      (file) => (file.fileType === 'DXF' || file.fileType === 'DWG') && file.parseStatus === 'SUCCESS',
    )
    const results = await Promise.all(
      drawings.map(async (file) => {
        try {
          const data = await projectApi.parseResult(file.id)
          return {
            fileName: file.fileName,
            summary: data?.materialSummary as DrawingMaterialItem[] | undefined,
            measure: (data?.measure ?? null) as Record<string, any> | null,
          }
        } catch {
          return { fileName: file.fileName, measure: null }
        }
      }),
    )
    drawingMaterials.value = mergeDrawingMaterials(results)
    // 逐张图保留各自的长度，核查时一张图对一次清单，不做跨图合计
    drawingCableFiles.value = results.map((item) => ({
      fileName: item.fileName,
      cable: toDrawingCable(item.measure),
    }))
  } finally {
    drawingLoading.value = false
  }
}

async function refreshAll() {
  await Promise.all([load(), loadDrawings()])
}

/** 回溯定位（D2）：定位到疑点对应的清单行并高亮 */
function focusHighlight() {
  const rowNo = props.highlightRow
  if (!rowNo) {
    return
  }
  const index = all.value.findIndex((item) => item.rowNo === rowNo)
  if (index >= 0) {
    page.value = Math.floor(index / size.value) + 1
  }
}

function rowClass({ row }: { row: BoqItemVO }) {
  return props.highlightRow && row.rowNo === props.highlightRow ? 'highlight-row' : ''
}

function clearHighlight() {
  page.value = 1
}

function typeTag(itemType?: string): 'primary' | 'success' | 'warning' | 'info' {
  switch (itemType) {
    case 'MATERIAL':
      return 'success'
    case 'SUPPLY_MATERIAL':
      return 'warning'
    case 'SUPPLY_EQUIPMENT':
      return 'info'
    default:
      return 'primary'
  }
}

/**
 * 定额编号：清单里带的编号（TXL4-033 这种字母+短横线的形态）优先，
 * 否则退回匹配到的定额编号；纯数字的是清单编码，不当定额编号展示。
 */
function quotaNo(row: BoqItemVO) {
  const code = (row.itemCode || '').trim()
  if (code && /[a-zA-Z]/.test(code) && /[-–—_]/.test(code)) {
    return code
  }
  return row.stdItemCode || row.matchQuotaCode || '-'
}

/** 数量统一按千分位展示，最多保留 3 位小数 */
function qty(value?: number | null) {
  if (value === undefined || value === null || Number.isNaN(Number(value))) {
    return '-'
  }
  return Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 3 })
}

/** 图纸数量相对清单数量的偏差：±0.5% 以内视为一致 */
function diffLabel(rate: number) {
  if (Math.abs(rate) < 0.5) {
    return '数量一致'
  }
  return `${rate > 0 ? '图纸偏大' : '图纸偏小'} ${Math.abs(rate).toFixed(1)}%`
}

function onSizeChange(next: number) {
  size.value = next
  page.value = 1
}

async function clearAll() {
  await ElMessageBox.confirm('清空后需重新导入清单，已有疑点将一并清除，确认继续？', '清空确认', {
    type: 'warning',
  })
  await boqApi.clear(props.projectId)
  ElMessage.success('已清空')
  await load()
  emit('changed')
}

watch(() => props.highlightRow, focusHighlight)
watch(() => props.refreshKey, () => refreshAll())
watch([keyword, typeFilter, drawingUnmatchedOnly], () => {
  page.value = 1
})

onMounted(() => {
  load()
  loadDrawings()
})
</script>

<style scoped>
.compare {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.pane {
  min-width: 0;
}
.pane-drawing {
  flex: 0 0 460px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px 10px 6px;
  background: #fcfcfd;
}
.pane-boq {
  flex: 1 1 auto;
}
.pane-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.pane-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}
.pane-sub {
  flex: 1 1 auto;
  color: #909399;
  font-size: 12px;
}
.pane-filter {
  margin-bottom: 4px;
}
.mat-name {
  color: #303133;
  line-height: 1.35;
}
.mat-spec {
  color: #a8abb2;
  font-size: 11px;
  line-height: 1.3;
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
/* 光缆长度：清单少于图纸用红色强调，通过用绿色 */
.cable-less {
  color: #f56c6c;
  font-weight: 600;
}
.diff-ok {
  color: #67c23a;
}
.cable-alert {
  margin-top: 8px;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.score {
  margin-left: 4px;
  color: #909399;
  font-size: 11px;
}
.section {
  margin: 14px 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}
.muted {
  color: #a8abb2;
  font-size: 12px;
}
.hint {
  color: #e6a23c;
  font-size: 12px;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
:deep(.highlight-row) {
  background-color: #fdf6ec !important;
}
.cable-note {
  margin: 8px 0 0;
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
}
:deep(.num-cell .cell) {
  font-variant-numeric: tabular-nums;
}

/* 表格单元格统一换行：清单名称、项目特征又长又多是中文，省略号截断后核对时看不全 */
:deep(.el-table .cell) {
  white-space: normal;
  word-break: break-all;
  line-height: 1.45;
}
:deep(.wrap-cell .cell) {
  padding: 4px 8px;
}

@media (max-width: 1400px) {
  .compare {
    flex-wrap: wrap;
  }
  .pane-drawing {
    flex: 1 1 100%;
  }
}
</style>
