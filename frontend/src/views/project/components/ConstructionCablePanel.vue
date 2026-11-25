<template>
  <div class="cable-panel">
    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="搜索段落名称"
        clearable
        style="width: 280px"
      />
      <el-select v-model="coreFilter" placeholder="全部芯数" clearable style="width: 120px">
        <el-option v-for="c in coreOptions" :key="c" :label="`${c} 芯`" :value="c" />
      </el-select>
      <el-select v-model="dupFilter" placeholder="全部重复状态" clearable style="width: 160px">
        <el-option label="起止反转" value="REVERSED" />
        <el-option label="名称近似" value="SIMILAR" />
        <el-option label="正常" value="NONE" />
      </el-select>
      <el-button type="primary" @click="onQuery">查询</el-button>
      <el-button @click="resetQuery">重置</el-button>
      <span class="note">
        共 {{ segments.length }} 段<template v-if="dupCount">，疑似重复 {{ dupCount }} 段（起止反转 / 名称近似）</template>
      </span>
    </div>

    <div class="amount-summary">
      <div
        v-for="stat in coreStats"
        :key="stat.core"
        class="core-stat"
        :class="{ disabled: !boqByCore.get(stat.core) }"
      >
        <span
          class="core-link"
          :title="boqByCore.get(stat.core) ? '点击跳转到清单对应的光缆行' : '清单无对应光缆行'"
          @click="goToCoreBoq(stat.core)"
        >
          {{ stat.core }} 芯总长：<b class="core-value">{{ qty(stat.length) }}</b> 米
        </span>
        <span class="compare-line">
          <template v-if="boqByCore.get(stat.core)">
            清单申报 <b>{{ qty(boqByCore.get(stat.core)!.quantity) }}</b> 米
            <el-tag size="small" :type="lengthCompare(stat.length, boqByCore.get(stat.core)!.quantity)" effect="plain">
              {{ lengthCompareText(stat.length, boqByCore.get(stat.core)!.quantity) }}
            </el-tag>
          </template>
          <span v-else class="muted">清单无对应光缆行</span>
        </span>
      </div>
      <div class="core-stat">
        <span>表七施工费：<b>{{ money(totalAmount) }}</b> 元</span>
        <span class="compare-line">
          清单施工费 <b>{{ money(boqTotalAmount) }}</b> 元
          <el-tag size="small" :type="amountCompare" effect="plain">
            {{ amountCompareText }}
          </el-tag>
        </span>
      </div>
    </div>

    <el-table
      ref="tableRef"
      v-loading="loading"
      :data="pageRows"
      border
      :height="tableHeight"
      empty-text="暂无施工光缆逐段数据，请在「资料与解析」重新导入结算清单 Excel"
      :row-class-name="rowClass"
    >
      <el-table-column label="序号" type="index" :index="indexMethod" width="70" align="center" />
      <el-table-column label="段落名称" min-width="320" class-name="wrap-cell">
        <template #default="{ row }">
          <span class="segment-name">
            <span>{{ row.segmentName }}</span>
            <el-tag
              v-if="row.dupGroup"
              :type="row.dupType === 'REVERSED' ? 'danger' : 'warning'"
              size="small"
              effect="plain"
              class="dup-tag-link"
              :title="`查看同组 ${groupOf(row.dupGroup).length} 段的名称差异`"
              @click="openDupGroup(row)"
            >
              {{ row.dupType === 'REVERSED' ? '起止反转重复' : '名称近似重复' }} · 查看
            </el-tag>
            <span v-else class="dup-normal">正常</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="coreCount" label="芯数" width="80" align="right" />
      <el-table-column label="长度(米)" width="130" align="right" class-name="num-cell">
        <template #default="{ row }">{{ qty(row.length) }}</template>
      </el-table-column>
      <el-table-column label="金额(元)" width="130" align="right" class-name="num-cell">
        <template #default="{ row }">{{ money(row.amount) }}</template>
      </el-table-column>
      <el-table-column label="关联图纸" width="130">
        <template #default="{ row }">
          <DrawingJump
            :ref="(el: unknown) => setJumpRef(row.id, el)"
            :sources="hitsOf(row).map((hit) => hit.source)"
            :fallback="defaultDrawing"
            :matched-pages="matchedPagesOf(row)"
            :material="highlightOf(row).highlight"
            :spec="highlightOf(row).spec"
            :highlight="highlightOf(row).highlight"
            :highlight-spec="highlightOf(row).spec"
            :highlight-fallback="highlightOf(row).fallback"
            @toggle-match="(payload) => onToggleMatch(row, payload)"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120" align="center">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openMatch(row)">
            {{ hasManualMatch(row) ? '调整' : '关联' }}
          </el-button>
          <el-tag v-if="hasManualMatch(row)" size="small" type="warning" effect="plain" class="manual-tag">手动</el-tag>
          <el-button
            v-if="hasManualMatch(row)"
            link
            size="small"
            class="restore-btn"
            @click="restoreAuto(row)"
          >恢复自动</el-button>
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

    <el-dialog
      v-model="dupVisible"
      :title="dupDialogTitle"
      width="70%"
      align-center
      destroy-on-close
    >
      <template v-if="dupCurrent">
        <el-alert
          :type="dupCurrent.dupType === 'REVERSED' ? 'error' : 'warning'"
          :closable="false"
          show-icon
          class="dup-alert"
          :title="dupCurrent.dupType === 'REVERSED' ? '起止点反转：同一段被正反各记一次，名称相同、起止互换' : '名称近似：段名只差少量字符，可能是同一段的两种写法'"
        />
        <el-table :data="dupGroupRows" border size="small" empty-text="同组段落缺失">
          <el-table-column label="序号" width="80" align="center">
            <template #default="{ row }">{{ seqOf(row) }}</template>
          </el-table-column>
          <el-table-column label="段落名称" min-width="320" class-name="wrap-cell">
            <template #default="{ row }">
              <span
                v-for="(part, i) in diffParts(row.segmentName)"
                :key="i"
                :class="{ 'diff-mark': part.diff }"
              >{{ part.text }}</span>
            </template>
          </el-table-column>
          <el-table-column label="芯数" width="80" align="right" prop="coreCount" />
          <el-table-column label="长度(米)" width="130" align="right" class-name="num-cell">
            <template #default="{ row }">{{ qty(row.length) }}</template>
          </el-table-column>
          <el-table-column label="金额(元)" width="130" align="right" class-name="num-cell">
            <template #default="{ row }">{{ money(row.amount) }}</template>
          </el-table-column>
        </el-table>
        <p class="dup-note">红色字符为与同组第一段不同的部分；重复仅作提示，不会自动核减金额，请人工复核。</p>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  boqApi,
  projectApi,
  type BoqItemVO,
  type CableDrawingMatchVO,
  type DrawingTextLabel,
  type FileVO,
  type FixedSegmentVO,
} from '@/api'
import { money } from '@/utils/format'
import { useTableHeight } from '@/utils/useTableHeight'
import DrawingJump from './DrawingJump.vue'
import { findSegmentDrawingHits, type DrawingLabelDoc, type DrawingSource, type SegmentDrawingHit } from './drawingCompare'

/** refreshKey 由父组件在清单重新导入/核查完成后自增，Tab 不销毁也能重新取数 */
const props = defineProps<{ projectId: number; refreshKey?: number }>()
const emit = defineEmits<{
  (e: 'count', count: number): void
  (e: 'locate-boq', rowNo: number): void
  (e: 'go-boq'): void
}>()

const tableRef = ref<{ $el: HTMLElement }>()
const tableHeight = useTableHeight(tableRef)
const segments = ref<FixedSegmentVO[]>([])
const boqItems = ref<BoqItemVO[]>([])
/** 本项目已解析图纸的文字标注索引，用于按段名回查图纸页 */
const labelDocs = ref<DrawingLabelDoc[]>([])
/** 施工光缆段的人工图纸关联（后端持久化） */
const manualMatches = ref<CableDrawingMatchVO[]>([])
const loading = ref(false)
const keyword = ref('')
const coreFilter = ref<number | undefined>(undefined)
const dupFilter = ref('')
const page = ref(1)
const size = ref(50)

const dupCount = computed(() => segments.value.filter((s) => s.dupGroup > 0).length)

/** 重复组弹窗 */
const dupVisible = ref(false)
const dupCurrent = ref<FixedSegmentVO>()
const dupGroups = computed(() => {
  const map = new Map<number, FixedSegmentVO[]>()
  for (const s of segments.value) {
    if (s.dupGroup > 0) {
      const list = map.get(s.dupGroup) ?? []
      list.push(s)
      map.set(s.dupGroup, list)
    }
  }
  return map
})
function groupOf(groupNo: number) {
  return dupGroups.value.get(groupNo) ?? []
}
const dupGroupRows = computed(() => (dupCurrent.value ? groupOf(dupCurrent.value.dupGroup) : []))
const dupDialogTitle = computed(() =>
  dupCurrent.value
    ? `重复组 ${dupCurrent.value.dupGroup} · ${dupCurrent.value.dupType === 'REVERSED' ? '起止反转' : '名称近似'}（${dupGroupRows.value.length} 段）`
    : '重复组',
)

function openDupGroup(row: FixedSegmentVO) {
  dupCurrent.value = row
  dupVisible.value = true
}

/**
 * 逐字符对比段名与组内第一段，把不同的字符标出来（供弹窗红色高亮）。
 * 起止反转的两段长度相同、只是顺序不同，逐位对比会把整段标红，意义不大；
 * 那种情况直接按「整段不同」显示，用户看行号就明白是正反两条。
 */
function diffParts(name?: string): { text: string; diff: boolean }[] {
  const base = dupGroupRows.value[0]?.segmentName ?? ''
  const target = name ?? ''
  if (dupCurrent.value?.dupType === 'REVERSED' || base === target) {
    return [{ text: target, diff: false }]
  }
  const parts: { text: string; diff: boolean }[] = []
  const len = Math.max(base.length, target.length)
  let buffer = ''
  let bufferDiff = false
  const flush = () => {
    if (buffer) {
      parts.push({ text: buffer, diff: bufferDiff })
      buffer = ''
    }
  }
  for (let i = 0; i < len; i += 1) {
    const same = i < base.length && i < target.length && base[i] === target[i]
    const ch = i < target.length ? target[i] : ''
    if (ch && same === bufferDiff) {
      buffer += ch
    } else {
      flush()
      if (ch) {
        buffer = ch
        bufferDiff = !same
      }
    }
  }
  flush()
  return parts
}

/** 表八光缆行在导入时被改写为「固定单价法施工费（N芯，X段汇总）」，按芯数建立关联 */
const boqByCore = computed(() => {
  const map = new Map<number, BoqItemVO>()
  for (const item of boqItems.value) {
    const m = /(?:固定单价法施工费)[^0-9]*(\d+)\s*芯/.exec(item.featureDesc ?? '')
    if (m) {
      const core = Number(m[1])
      if (!map.has(core)) {
        map.set(core, item)
      }
    }
  }
  return map
})

/**
 * 施工光缆段看图时用的标红参数：按芯数找到清单对应的光缆行，
 * 用材料名（如「光缆」）+ 完整规格型号（如「GYTS-24B1」）在 DXF 里锁定同一行标红。
 * 规格必须取全：GYTS-24B1 若截成 GYTS-24B，图上按规格匹配就会落空，
 * 导致同一张图里 12 芯 / 24 芯两种光缆一起被标红。
 */
function highlightOf(row: FixedSegmentVO): { highlight: string; spec: string; fallback: string } {
  const segmentFallback = { highlight: row.segmentName || '', spec: '', fallback: '' }
  if (row.coreCount == null) {
    return segmentFallback
  }
  const boq = boqByCore.value.get(row.coreCount)
  if (!boq) {
    return segmentFallback
  }
  const name = boq.itemName || ''
  // 提取完整规格型号：GYTS-12B1 / GYTS-24B1 / GYXTW-12-9/125 等字母数字与连字符组合
  const spec = boq.featureDesc?.match(/[A-Z]{2,}[A-Z0-9]*-[A-Z0-9/-]+/i)?.[0]?.trim() || ''
  const fallback = name.replace(/\s*固定单价法施工费.*$/, '').trim() || name
  return { highlight: name, spec, fallback }
}

const coreOptions = computed(() =>
  [...new Set(segments.value.map((s) => s.coreCount).filter((c): c is number => c != null))]
    .sort((a, b) => a - b))

/** 各芯数总长度：取自表七逐段明细，按芯数分组求和 */
const coreStats = computed(() => {
  const map = new Map<number, number>()
  for (const s of segments.value) {
    if (s.coreCount == null) {
      continue
    }
    map.set(s.coreCount, (map.get(s.coreCount) ?? 0) + Number(s.length ?? 0))
  }
  return [...map.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([core, length]) => ({ core, length }))
})

const totalAmount = computed(() =>
  segments.value.reduce((sum, s) => sum + Number(s.amount ?? 0), 0))

/** 工程量清单里对应光缆行的施工费合计（去重后按芯数各取一条汇总行） */
const boqTotalAmount = computed(() =>
  [...boqByCore.value.values()].reduce((sum, item) => sum + Number(item.totalPrice ?? 0), 0))

const LENGTH_TOLERANCE = 0.5
const AMOUNT_TOLERANCE = 0.01

function lengthCompare(tableLen?: number, boqLen?: number): 'success' | 'danger' | 'info' {
  if (tableLen == null || boqLen == null) {
    return 'info'
  }
  return Math.abs(Number(tableLen) - Number(boqLen)) <= LENGTH_TOLERANCE ? 'success' : 'danger'
}

function lengthCompareText(tableLen?: number, boqLen?: number) {
  if (tableLen == null || boqLen == null) {
    return '无数据'
  }
  const diff = Number(tableLen) - Number(boqLen)
  return Math.abs(diff) <= LENGTH_TOLERANCE ? '一致' : `差 ${diff > 0 ? '+' : ''}${qty(diff)} 米`
}

const amountCompare = computed<'success' | 'danger' | 'info'>(() =>
  Math.abs(totalAmount.value - boqTotalAmount.value) <= AMOUNT_TOLERANCE ? 'success' : 'danger')

const amountCompareText = computed(() => {
  const diff = totalAmount.value - boqTotalAmount.value
  return Math.abs(diff) <= AMOUNT_TOLERANCE ? '一致' : `差 ${diff > 0 ? '+' : ''}${money(diff)} 元`
})

const filtered = computed(() => {
  const kw = keyword.value.trim()
  return segments.value.filter((s) => {
    if (kw && !(s.segmentName ?? '').includes(kw)) {
      return false
    }
    if (coreFilter.value != null && s.coreCount !== coreFilter.value) {
      return false
    }
    if (dupFilter.value === 'NONE' && s.dupGroup > 0) {
      return false
    }
    if (dupFilter.value && dupFilter.value !== 'NONE' && s.dupType !== dupFilter.value) {
      return false
    }
    return true
  })
})

const pageRows = computed(() => {
  const start = (page.value - 1) * size.value
  return filtered.value.slice(start, start + size.value)
})

function rowClass({ row }: { row: FixedSegmentVO }) {
  return row.dupGroup > 0 ? 'dup-row' : ''
}

/** 序号按当前筛选结果的次序跨页连续，而不是每页都从 1 开始 */
function indexMethod(index: number) {
  return (page.value - 1) * size.value + index + 1
}

/** 重复详情展示整组，序号按全量段落次序取，和表格首列在「无筛选」时一致 */
function seqOf(row: FixedSegmentVO) {
  const index = segments.value.findIndex((s) => s.id === row.id)
  return index >= 0 ? index + 1 : (row.rowNo ?? '-')
}

function qty(value?: number | null) {
  if (value === undefined || value === null || Number.isNaN(Number(value))) {
    return '-'
  }
  return Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 4 })
}

/** 该段的人工图纸关联 */
function manualMatchOf(row: FixedSegmentVO): CableDrawingMatchVO[] {
  return manualMatches.value.filter((m) => m.segmentId === row.id)
}

/** 没有自动命中时，「关联」按钮默认打开的图纸（第一张已解析图纸） */
const defaultDrawing = computed(
  () => labelDocs.value[0] && { fileId: labelDocs.value[0].fileId, fileName: labelDocs.value[0].fileName },
)

/** 把一条人工关联转成可直接交给 DrawingJump 的命中项 */
function manualHit(match: CableDrawingMatchVO): SegmentDrawingHit {
  const doc = labelDocs.value.find((d) => d.fileId === match.fileId)
  const frame = match.frame != null ? doc?.frames?.find((f) => f.no === match.frame) : null
  const source: DrawingSource = {
    fileId: match.fileId,
    fileName: doc?.fileName ?? `图纸 ${match.fileId}`,
    frame: match.frame,
    sheet: frame?.sheet ?? null,
    title: frame?.title ?? null,
    bbox: match.bbox
      ? { minX: Number(match.bbox.minX), minY: Number(match.bbox.minY),
          maxX: Number(match.bbox.maxX), maxY: Number(match.bbox.maxY) }
      : frame
        ? { minX: frame.minX, minY: frame.minY, maxX: frame.maxX, maxY: frame.maxY }
        : null,
    tableBox: null,
    tables: [],
  }
  return { source, matched: [] }
}

/** 段名在图纸标注里命中的页；已人工干预时以人工结果为准（含清空），否则按段名+规格自动匹配 */
const hitCache = new Map<string, ReturnType<typeof findSegmentDrawingHits>>()
function hitsOf(row: FixedSegmentVO) {
  if (hasManualMatch(row)) {
    return manualMatchOf(row)
      .filter((m) => m.frame !== MANUAL_EMPTY_FRAME)
      .map(manualHit)
  }
  const name = row.segmentName ?? ''
  const spec = highlightOf(row).spec
  const cacheKey = `${name}|${spec}`
  let hits = hitCache.get(cacheKey)
  if (!hits) {
    // 传入规格：段名在多张分幅图重复出现时，只保留印有该芯数光缆材料表的页
    hits = findSegmentDrawingHits(name, labelDocs.value, spec)
    hitCache.set(cacheKey, hits)
  }
  return hits
}

/** 人工确认「不关联任何图纸」的哨兵图页号，需与后端 BoqService.MANUAL_EMPTY_FRAME 一致 */
const MANUAL_EMPTY_FRAME = -1

/** 该段是否已被人工干预（含人工清空哨兵） */
function hasManualMatch(row: FixedSegmentVO) {
  return manualMatches.value.some((m) => m.segmentId === row.id)
}

/**
 * 当前生效的关联页：有人工干预就用人工结果（可能是空的），否则用自动命中。
 * 自动命中同样算「已关联」。
 */
function effectiveMatchPages(row: FixedSegmentVO): Array<{ fileId: number; frame: number | null }> {
  if (hasManualMatch(row)) {
    return manualMatchOf(row)
      .filter((m) => m.frame !== MANUAL_EMPTY_FRAME)
      .map((m) => ({ fileId: m.fileId, frame: m.frame }))
  }
  return hitsOf(row).map((hit) => ({ fileId: hit.source.fileId, frame: hit.source.frame }))
}

/** 传给预览的已关联页，用于回显「已关联此页 / 未关联」 */
function matchedPagesOf(row: FixedSegmentVO) {
  return effectiveMatchPages(row)
}

/** 每行的图纸预览组件，供「操作」列的关联按钮打开对应预览 */
const jumpRefs = new Map<number, { openForMatch: () => void }>()
function setJumpRef(id: number, el: unknown) {
  if (el) {
    jumpRefs.set(id, el as { openForMatch: () => void })
  } else {
    jumpRefs.delete(id)
  }
}

/** 操作列「关联」：打开该行的图纸预览，在预览里点「关联此页」完成关联 */
function openMatch(row: FixedSegmentVO) {
  jumpRefs.get(row.id)?.openForMatch()
}

/** 图纸预览里点「关联此页 / 取消关联此页」时增量保存该段人工关联 */
function pageMatchedKey(fileId: number, frame: number | null) {
  return `${fileId}::${frame ?? 'out'}`
}

async function onToggleMatch(
  row: FixedSegmentVO,
  payload: { fileId: number; frame: number | null },
) {
  const key = pageMatchedKey(payload.fileId, payload.frame)
  // 基线取「当前生效的关联页」：首次操作会把自动命中的页一并固化下来，
  // 之后取消其中某一页才能真正生效（否则自动命中会把它又加回来）。
  const baseline = effectiveMatchPages(row)
  const exists = baseline.some((m) => pageMatchedKey(m.fileId, m.frame) === key)
  const next = exists
    ? baseline.filter((m) => pageMatchedKey(m.fileId, m.frame) !== key)
    : [...baseline, { fileId: payload.fileId, frame: payload.frame }]
  const matches = next.length
    ? next.map((m) => {
      const doc = labelDocs.value.find((d) => d.fileId === m.fileId)
      const frameBox = doc?.frames?.find((f) => f.no === m.frame)
      return {
        fileId: m.fileId,
        frame: m.frame,
        bbox: frameBox
          ? { minX: frameBox.minX, minY: frameBox.minY, maxX: frameBox.maxX, maxY: frameBox.maxY }
          : null,
      }
    })
    // 全部取消时写一条哨兵，表达「人工确认不关联」，避免又回落到自动匹配
    : [{ fileId: 0, frame: MANUAL_EMPTY_FRAME, bbox: null }]
  try {
    await boqApi.saveCableDrawingMatches(props.projectId, row.id, matches)
    manualMatches.value = await boqApi.cableDrawingMatches(props.projectId)
    hitCache.clear()
    ElMessage.success(exists ? '已取消关联此页' : '已关联此页')
  } catch {
    ElMessage.error('保存关联失败')
  }
}

/** 恢复自动匹配：删掉该段全部人工记录（含清空哨兵） */
async function restoreAuto(row: FixedSegmentVO) {
  try {
    await boqApi.saveCableDrawingMatches(props.projectId, row.id, [])
    manualMatches.value = await boqApi.cableDrawingMatches(props.projectId)
    hitCache.clear()
    ElMessage.success('已恢复自动匹配')
  } catch {
    ElMessage.error('恢复自动匹配失败')
  }
}

function onQuery() {
  page.value = 1
}

function resetQuery() {
  keyword.value = ''
  coreFilter.value = undefined
  dupFilter.value = ''
  page.value = 1
}

function onSizeChange(next: number) {
  size.value = next
  page.value = 1
}

/** 点击段落名跳转到清单对应芯数的光缆行 */
function goToCoreBoq(core?: number) {
  const target = core != null ? boqByCore.value.get(core) : undefined
  if (target?.rowNo != null) {
    emit('locate-boq', target.rowNo)
  } else {
    emit('go-boq')
  }
}

async function reload() {
  loading.value = true
  try {
    const [segmentRows, boqRows, files, matches] = await Promise.all([
      boqApi.fixedSegments(props.projectId),
      boqApi.all(props.projectId),
      projectApi.files(props.projectId),
      boqApi.cableDrawingMatches(props.projectId),
    ])
    segments.value = segmentRows
    boqItems.value = boqRows
    manualMatches.value = matches
    labelDocs.value = await loadLabelDocs(files)
    hitCache.clear()
    emit('count', segments.value.length)
  } finally {
    loading.value = false
  }
}

/** 读取本项目已解析图纸的文字标注，供段落名回查图纸页 */
async function loadLabelDocs(files: FileVO[]): Promise<DrawingLabelDoc[]> {
  const drawings = files.filter(
    (file) => (file.fileType === 'DXF' || file.fileType === 'DWG') && file.parseStatus === 'SUCCESS',
  )
  const results = await Promise.all(
    drawings.map(async (file) => {
      try {
        const data = await projectApi.parseResult(file.id)
        const labels = (data?.textLabels ?? []) as DrawingTextLabel[]
        // 各图框页上的材料规格：段名命中多页时据此收敛到印有对应光缆规格的页
        const frameSpecs = new Map<number, string[]>()
        ;(data?.materials ?? []).forEach((table: { frame?: number | null; rows?: string[][] }) => {
          if (table.frame == null || !table.rows) {
            return
          }
          const specs = frameSpecs.get(table.frame) ?? []
          table.rows.forEach((row) => {
            row.forEach((cell) => {
              if (/^[A-Z]{2,}[A-Z0-9]*-[A-Z0-9/-]+$/i.test(cell.trim()) && !specs.includes(cell.trim())) {
                specs.push(cell.trim())
              }
            })
          })
          frameSpecs.set(table.frame, specs)
        })
        const frames = ((data?.frames ?? []) as DrawingLabelDoc['frames'])
        return { fileId: file.id, fileName: file.fileName, labels, frameSpecs, frames }
      } catch {
        return { fileId: file.id, fileName: file.fileName, labels: [] as DrawingTextLabel[],
          frameSpecs: new Map(), frames: [] }
      }
    }),
  )
  return results.filter((doc) => doc.labels.length > 0)
}

watch(() => props.refreshKey, reload)
watch([keyword, coreFilter, dupFilter], () => {
  page.value = 1
})
onMounted(reload)
</script>

<style scoped>
.cable-panel {
  min-width: 0;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.note {
  font-size: 12px;
  color: #909399;
}
.amount-summary {
  display: flex;
  align-items: flex-start;
  gap: 18px;
  flex-wrap: wrap;
  padding: 10px 14px;
  margin-bottom: 12px;
  background: #f5f7fa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  font-size: 13px;
  color: #606266;
}
.core-stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.5;
}
.amount-summary b {
  font-variant-numeric: tabular-nums;
  color: #303133;
  font-size: 14px;
}
.core-link {
  cursor: pointer;
}
.amount-summary b.core-value {
  color: #409eff;
}
.core-link:hover .core-value {
  text-decoration: underline;
}
.core-stat.disabled .core-link {
  cursor: default;
}
.core-stat.disabled .core-value {
  color: #606266;
}
.core-stat.disabled .core-link:hover .core-value {
  text-decoration: none;
}
.compare-line {
  font-size: 12px;
  color: #909399;
  display: flex;
  align-items: center;
  gap: 4px;
}
.compare-line b {
  color: #606266;
  font-size: 12px;
}
.segment-name {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.dup-normal {
  color: #c0c4cc;
  font-size: 11px;
}
.dup-tag-link {
  cursor: pointer;
}
.dup-alert {
  margin-bottom: 12px;
}
.diff-mark {
  color: #f56c6c;
  font-weight: 600;
  background: #fef0f0;
}
.dup-note {
  margin: 10px 0 0;
  color: #909399;
  font-size: 12px;
}
.manual-tag {
  margin-left: 2px;
  height: 16px;
  padding: 0 3px;
  font-size: 10px;
  line-height: 14px;
}
.restore-btn {
  margin-left: 4px;
  color: #909399;
}
.muted {
  color: #a8abb2;
  font-size: 12px;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
:deep(.dup-row) {
  background-color: #fdf6ec !important;
}
:deep(.num-cell .cell) {
  font-variant-numeric: tabular-nums;
}
:deep(.el-table .cell) {
  white-space: normal;
  word-break: break-all;
  line-height: 1.45;
}
</style>
