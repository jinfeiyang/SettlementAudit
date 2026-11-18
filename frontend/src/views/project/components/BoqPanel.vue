<template>
  <div class="compare">
    <section class="pane">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="搜索清单名称 / 编号 / 特征"
          clearable
          style="width: 240px"
        />
        <el-select v-model="typeFilter" placeholder="全部类别" clearable style="width: 150px">
          <el-option label="工程量" value="QUOTA" />
          <el-option label="甲供材料" value="SUPPLY_MATERIAL" />
          <el-option label="甲供设备" value="SUPPLY_EQUIPMENT" />
          <el-option label="其他费" value="OTHER" />
        </el-select>
        <el-button @click="refreshAll">刷新</el-button>
        <el-button @click="boqApi.template(projectId)">下载清单模板</el-button>
        <el-button @click="$emit('go', 'files')">上传/解析清单</el-button>
        <el-button type="danger" plain @click="clearAll">清空清单</el-button>
        <el-checkbox v-model="drawingUnmatchedOnly" size="small">只看图纸未匹配</el-checkbox>
        <span v-if="highlightRow" class="hint">
          已定位第 {{ highlightRow }} 行<template v-if="locatedRow">：{{ locatedRow.itemName }}</template>
          <template v-else>（当前清单中没有这一行）</template>
          <el-button link type="primary" @click="clearHighlight">取消高亮</el-button>
        </span>
      </div>

      <div class="amount-summary">
        <span>定额直接费：<b>{{ money(quotaTotal) }}</b> 元</span>
        <span>固定单价法施工费：<b>{{ money(fixedTotal) }}</b> 元</span>
        <span>其他费及补差：<b>{{ money(otherTotal) }}</b> 元</span>
        <span>取费合计：<b>{{ money(feeTotal) }}</b> 元</span>
        <span class="grand">清单合计：<b>{{ money(grandTotal) }}</b> 元</span>
        <span class="hint">
          甲供器材由建设单位提供、不计入结算；表八光缆行按表七固定单价法计施工费；
          取费行由规则库生成，随清单重新导入更新
        </span>
      </div>

      <el-table
        ref="tableRef"
        v-loading="loading"
        :data="pageRows"
        :row-class-name="rowClass"
        empty-text="暂无清单数据，请先在「资料与解析」上传清单 Excel"
        max-height="520"
      >
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
            <span v-if="isPricedExtraRow(row)" class="muted">-</span>
            <template v-else-if="row.drawing">
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
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type TableInstance } from 'element-plus'
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
const emit = defineEmits<{
  (e: 'changed'): void
  (e: 'go', tab: string): void
  (e: 'clear-highlight'): void
}>()

const tableRef = ref<TableInstance>()
/** 当前定位到的清单行，用于提示条展示名称（找不到时说明行已不存在） */
const locatedRow = ref<BoqItemVO>()
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
const drawingUnmatchedOnly = ref(false)

/** 清单行详情：页面太挤，图纸匹配与光缆长度核查都收到详情弹窗里看 */
const detailVisible = ref(false)
const detailRow = ref<BoqRow | null>(null)

const comparisons = computed(() => compareDrawingWithBoq(drawingMaterials.value, all.value))

/**
 * 金额汇总分四段展示，避免混成一个「清单直接费」：
 * 定额直接费、固定单价法施工费、其他费及补差、取费合计。
 * 甲供材料/设备属建设单位提供、决算送审不含，不计；按表七固定单价法重算的光缆行是施工费，计入。
 */
const quotaTotal = computed(() =>
  all.value
    .filter((item) => item.itemType === 'QUOTA' || !item.itemType)
    .reduce((sum, item) => sum + Number(item.totalPrice ?? 0), 0),
)
/** 表八甲供材料段的光缆行，金额已被表七施工费替换，属施工费而非器材费 */
const fixedTotal = computed(() =>
  all.value
    .filter(
      (item) =>
        (item.itemType === 'SUPPLY_MATERIAL' || item.itemType === 'SUPPLY_EQUIPMENT') &&
        (item.featureDesc ?? '').includes('固定单价法施工费'),
    )
    .reduce((sum, item) => sum + Number(item.totalPrice ?? 0), 0),
)
/** 表五工程建设其他费 + 表二定额法造价补差 */
const otherTotal = computed(() =>
  all.value
    .filter((item) => item.itemType === 'OTHER')
    .reduce((sum, item) => sum + Number(item.totalPrice ?? 0), 0),
)
const directTotal = computed(() => quotaTotal.value + fixedTotal.value + otherTotal.value)
const feeTotal = computed(() =>
  all.value
    .filter((item) => item.itemType === 'FEE')
    .reduce((sum, item) => sum + Number(item.totalPrice ?? 0), 0),
)
const grandTotal = computed(() => directTotal.value + feeTotal.value)

const filtered = computed(() => {
  const kw = keyword.value.trim()
  return all.value.filter((item) => {
    // 取费行是导入时按规则生成的计算结果，只在金额汇总条体现，不作为清单行展示
    if (item.itemType === 'FEE') {
      return false
    }
    if (typeFilter.value && (item.itemType || 'QUOTA') !== typeFilter.value) {
      return false
    }
    if (drawingUnmatchedOnly.value) {
      // 取费/其他费行本就不参与图纸匹配，勾选「只看图纸未匹配」时不该混进来
      if (isPricedExtraRow(item) || comparisons.value.byBoqId.get(item.id)) {
        return false
      }
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

/**
 * 回溯定位（D2）：翻到目标行所在页并滚动到行、高亮。
 * 用「滚动 + 高亮」而不是「筛选出一行」：核对疑点要看相邻行和同类项目，
 * 只留一行会把上下文打散；但筛选状态会把目标行藏起来，所以定位前先清掉。
 */
function focusHighlight() {
  const rowNo = props.highlightRow
  if (!rowNo) {
    locatedRow.value = undefined
    return
  }
  keyword.value = ''
  typeFilter.value = ''
  drawingUnmatchedOnly.value = false
  // 在「可见行」里找：取费行之类的已计价行不在清单表格里，提示要说清楚是没有这行
  const index = filtered.value.findIndex((item) => item.rowNo === rowNo)
  locatedRow.value = index >= 0 ? filtered.value[index] : undefined
  if (index < 0) {
    return
  }
  // 清筛选会触发「page 归 1」的 watcher，等它跑完再设目标页，否则刚算好的页码被覆盖
  nextTick(() => {
    page.value = Math.floor(index / size.value) + 1
    nextTick(() => scrollToRowInPage(index % size.value))
  })
}

/** 表格内部是 el-scrollbar，直接按目标行的 offsetTop 设置滚动容器位置 */
function scrollToRowInPage(indexInPage: number) {
  const root = tableRef.value?.$el as HTMLElement | undefined
  const wrap = root?.querySelector('.el-table__body-wrapper .el-scrollbar__wrap') as HTMLElement | null
  if (!wrap) {
    return
  }
  const rowEl = wrap.querySelectorAll('.el-table__row')[indexInPage] as HTMLElement | undefined
  if (!rowEl) {
    return
  }
  // 用相对可视区的偏移算滚动量，不依赖 offsetParent 是谁
  const delta = rowEl.getBoundingClientRect().top - wrap.getBoundingClientRect().top
  wrap.scrollTop += delta - 60
}

function rowClass({ row }: { row: BoqItemVO }) {
  return props.highlightRow && row.rowNo === props.highlightRow ? 'highlight-row' : ''
}

function clearHighlight() {
  page.value = 1
  locatedRow.value = undefined
  emit('clear-highlight')
}

/** 取费行、其他费行是规则/结算口径行，没有图纸材料可对，避免刷出一片「图纸无对应」 */
function isPricedExtraRow(row: BoqItemVO) {
  return row.itemType === 'FEE' || row.itemType === 'OTHER'
}

function typeTag(itemType?: string): 'primary' | 'success' | 'warning' | 'info' | 'danger' {
  switch (itemType) {
    case 'MATERIAL':
      return 'success'
    case 'SUPPLY_MATERIAL':
      return 'warning'
    case 'SUPPLY_EQUIPMENT':
      return 'info'
    case 'FEE':
      return 'danger'
    case 'FIXED':
      return 'primary'
    case 'OTHER':
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
  min-width: 0;
}
.pane {
  min-width: 0;
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
.amount-summary {
  display: flex;
  align-items: center;
  gap: 20px;
  flex-wrap: wrap;
  padding: 10px 14px;
  margin-bottom: 12px;
  background: #f5f7fa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  font-size: 13px;
  color: #606266;
}
.amount-summary b {
  font-variant-numeric: tabular-nums;
  color: #303133;
  font-size: 14px;
}
.amount-summary .grand {
  font-size: 14px;
  color: #303133;
}
.amount-summary .grand b {
  color: #f56c6c;
  font-size: 16px;
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
/* 定位行闪两下，避免只翻页时用户找不到目标行 */
:deep(.highlight-row) {
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

</style>
