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
      <el-button type="primary" plain :disabled="selectedRows.length < 2" @click="openMerge">
        合并为一项
      </el-button>
      <el-button
        :disabled="selectedRows.length !== 1 || !selectedRows[0]?.aliases?.length"
        @click="unmerge"
      >
        取消合并
      </el-button>
      <span class="summary">{{ drawingSummaryText }}</span>
      <el-button v-if="!drawingMaterials.length && !drawingLoading" @click="emit('go', 'files')">
        上传图纸
      </el-button>
    </div>

    <el-table
      ref="tableRef"
      v-loading="drawingLoading"
      :data="pagedRows"
      row-key="key"
      :row-class-name="rowClass"
      empty-text="暂无图纸清单：请先在「资料与解析」上传并解析竣工图 DXF/DWG"
      :height="tableHeight"
      @selection-change="onSelectionChange"
    >
      <el-table-column type="selection" width="42" />
      <el-table-column prop="name" label="图纸名称" width="220" class-name="wrap-cell">
        <template #default="{ row }">
          {{ row.name }}
          <el-tag
            v-if="row.aliases?.length"
            type="info"
            size="small"
            effect="plain"
            class="merge-tag"
            :title="`已合并：${row.aliases.join('、')}`"
          >合并 {{ row.aliases.length }} 项</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="规格" width="160" align="center" class-name="wrap-cell">
        <template #default="{ row }">{{ row.spec || '-' }}</template>
      </el-table-column>
      <el-table-column label="图纸数量" width="180" align="center" class-name="num-cell">
        <template #default="{ row }">
          {{ qty(row.quantity) }}
          <el-tag
            v-if="hasFix(row.key)"
            type="warning"
            size="small"
            effect="plain"
            class="qty-fixed-tag"
            :title="fixedTip(row.key)"
          >已改</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="unit" label="单位" width="90" align="center" />
      <el-table-column label="所在图页" width="180" align="center" class-name="wrap-cell">
        <template #default="{ row }">
          <DrawingJump
            :sources="row.sources"
            :fallback="fallbackOf(row)"
            :material="row.name"
            :spec="row.spec"
            :formula="quantityFormula(row)"
            :formula-data="quantityFormulaData(row, quantityFixes[row.key])"
            :material-key="row.key"
            @fix-saved="reloadAfterFix"
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
      <el-table-column label="数量对比" width="210" align="center" class-name="wrap-cell">
        <template #default="{ row }">
          <template v-if="row.match">
            <div class="cmp-line">清单 {{ qty(row.match.quantity) }}{{ row.match.unit || '' }}</div>
            <div
              v-if="row.diffRate !== null && row.diffRate !== undefined"
              :class="['cmp-line', 'diff', 'diff-clickable', row.diffRate > 0 ? 'diff-more' : 'diff-less']"
              title="点击跳转到工程量清单并定位对应行"
              @click="locateBoq(row.match)"
            >
              {{ diffLabel(row.diffRate, row.diff) }}
            </div>
          </template>
          <span v-else class="muted">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right" class-name="action-cell">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.match" link type="danger" @click="unmatchRow(row)">取消匹配</el-button>
          <el-button v-else link type="primary" @click="openMatch(row)">手动匹配</el-button>
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
      @confirm="confirmMatch"
      @unmatch="matchRow && unmatchRow(matchRow)"
    />

    <el-dialog v-model="mergeVisible" title="合并图纸材料" width="560px" align-center>
      <p class="merge-hint">
        同一种材料在图纸里可能写成不同名称（如「安装ODF」「新装ODF」）。
        合并后按一行参与清单匹配，数量按各分项相加。
      </p>
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item v-for="row in selectedRows" :key="row.key" :label="row.name">
          {{ row.spec || '无规格' }} · {{ qty(row.quantity) }}{{ row.unit || '' }}
        </el-descriptions-item>
      </el-descriptions>
      <div class="merge-form">
        <span class="merge-label">合并后名称</span>
        <el-input v-model="mergeName" placeholder="如：ODF" clearable />
      </div>
      <div v-if="mergeUnitMixed" class="merge-warn">
        选中的材料单位不一致，合并后数量仍会直接相加，请先确认是同一件材料。
      </div>
      <template #footer>
        <el-button @click="mergeVisible = false">取消</el-button>
        <el-button type="primary" :loading="merging" @click="confirmMerge">合并</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="编辑图纸材料" width="540px" align-center>
      <el-form label-width="90px">
        <el-form-item label="名称" required>
          <el-input v-model="editForm.name" maxlength="200" />
        </el-form-item>
        <el-form-item label="规格 / 型号">
          <el-input v-model="editForm.spec" maxlength="200" />
        </el-form-item>
        <el-form-item label="单位">
          <el-input v-model="editForm.unit" maxlength="20" style="width: 160px" />
        </el-form-item>
        <el-form-item label="图纸数量">
          <el-input-number
            v-model="editForm.quantity"
            :precision="6"
            :step="1"
            :min="0"
            controls-position="right"
            style="width: 200px"
          />
        </el-form-item>
      </el-form>
      <p class="edit-tip">材料标识不变，编辑后与清单的匹配关系保留，数量对比按新数据重算。</p>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="detailVisible" title="图纸材料详情" width="720px" align-center>
      <template v-if="detailRow">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="图纸名称" :span="2">{{ detailRow.name }}</el-descriptions-item>
          <el-descriptions-item v-if="detailRow.aliases?.length" label="包含名称" :span="2">
            {{ detailRow.aliases.join('、') }}
          </el-descriptions-item>
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
              :formula="quantityFormula(detailRow)"
              :formula-data="quantityFormulaData(detailRow, quantityFixes[detailRow.key])"
              :material-key="detailRow.key"
              @fix-saved="reloadAfterFix"
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
              {{ diffLabel(detailRow.diffRate, detailRow.diff) }}
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
import { ElMessage, ElMessageBox, type TableInstance } from 'element-plus'
import { boqApi, projectApi, type BoqItemVO, type FileVO } from '@/api'
import { useTableHeight } from '@/utils/useTableHeight'
import DrawingJump from './DrawingJump.vue'
import {
  applyMatchOverrides,
  applyMaterialEdits,
  applyMaterialMerges,
  applyQuantityFixes,
  compareDrawingWithBoq,
  diffState,
  matchScore,
  mergeDrawingMaterials,
  mergeQuantityFixes,
  quantityFormula,
  quantityFormulaData,
  toMergeGroups,
  type QuantityFixes,
  type DrawingFrameItem,
  type DrawingMaterial,
  type DrawingMaterialItem,
  type DrawingRow,
  type DrawingTableItem,
  type MatchOverride,
  type MaterialEdit,
  type MaterialMergeGroup,
} from './drawingCompare'
import MatchDialog from './MatchDialog.vue'

/** 匹配弹窗选项：value 为清单行 id */
interface MatchOption {
  value: string
  label: string
  /** 规格 / 项目特征：同一名称下（如各芯数光缆）差别全在这里 */
  meta?: string
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
/** 图纸材料的人工合并组：同物异名的多条材料合成一行后再参与匹配 */
const mergeGroups = ref<MaterialMergeGroup[]>([])
/** 图纸材料的人工编辑：材料键不变，覆盖名称/规格/单位/数量 */
const materialEdits = ref<MaterialEdit[]>([])
/** 表格勾选的行，用于合并/取消合并 */
const selectedRows = ref<DrawingRow[]>([])
const mergeVisible = ref(false)
const mergeName = ref('')
const merging = ref(false)
/** 人工修正的数量分项：材料 key → 分项键 → 数值 */
const quantityFixes = ref<QuantityFixes>({})
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

/** 编辑图纸材料：材料键不变，改完匹配关联与清单侧对比同步刷新 */
const editVisible = ref(false)
const editSaving = ref(false)
const editForm = ref({ key: '', name: '', spec: '', unit: '', quantity: 0 })

function openEdit(row: DrawingRow) {
  editForm.value = {
    key: row.key,
    name: row.name ?? '',
    spec: row.spec ?? '',
    unit: row.unit ?? '',
    quantity: Number(row.quantity ?? 0),
  }
  editVisible.value = true
}

async function saveEdit() {
  if (!editForm.value.name.trim()) {
    ElMessage.warning('请填写材料名称')
    return
  }
  editSaving.value = true
  try {
    await boqApi.saveDrawingEdit(props.projectId, {
      materialKey: editForm.value.key,
      name: editForm.value.name.trim(),
      spec: editForm.value.spec,
      unit: editForm.value.unit,
      quantity: editForm.value.quantity,
    })
    editVisible.value = false
    // 两侧同时重算：清单侧的数量对比与匹配结论都按编辑后的材料数据刷新
    await refreshAll()
    emit('changed')
    ElMessage.success('已保存，清单侧匹配与数量对比已同步')
  } finally {
    editSaving.value = false
  }
}

/** 合并行不能再参与合并，避免套娃；拆分只需选中合并行即可 */
function onSelectionChange(rows: DrawingRow[]) {
  selectedRows.value = rows
}

/** 单位不一致时提示：合并只按同一种材料判断，系统不替人改口径 */
const mergeUnitMixed = computed(
  () => new Set(selectedRows.value.map((row) => (row.unit || '').trim())).size > 1,
)

/** 默认显示名取最短的那个名称（「安装ODF子框」「安装ODF」「新装ODF」→ ODF 最短的是「安装ODF」） */
function openMerge() {
  if (selectedRows.value.length < 2) {
    return
  }
  if (selectedRows.value.some((row) => row.aliases?.length)) {
    ElMessage.warning('已是合并项的材料不能再参与合并，请先取消合并')
    return
  }
  const shortest = [...selectedRows.value].sort((a, b) => a.name.length - b.name.length)[0]
  mergeName.value = shortest.name
  mergeVisible.value = true
}

async function confirmMerge() {
  if (selectedRows.value.length < 2) {
    return
  }
  merging.value = true
  try {
    const groupKey = `merge:${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`
    const name = mergeName.value.trim() || selectedRows.value[0].name
    await boqApi.saveDrawingMerges(
      props.projectId,
      groupKey,
      name,
      selectedRows.value.map((row) => row.key),
    )
    mergeVisible.value = false
    tableRef.value?.clearSelection()
    await refreshAll()
    emit('changed')
    ElMessage.success('已合并，数量与清单匹配按合并结果重算')
  } finally {
    merging.value = false
  }
}

/** 拆分合并组：成员恢复为独立材料，匹配结果随之重算 */
async function unmerge() {
  const row = selectedRows.value[0]
  if (!row?.aliases?.length) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `取消「${row.name}」的合并？拆分后 ${row.aliases.length} 条材料恢复为独立行，数量不再相加。`,
      '取消合并',
      { type: 'warning', confirmButtonText: '取消合并', cancelButtonText: '返回' },
    )
  } catch {
    return
  }
  await boqApi.resetDrawingMerges(props.projectId, row.key)
  tableRef.value?.clearSelection()
  await refreshAll()
  emit('changed')
  ElMessage.success('已拆分合并项')
}

/** 该材料是否有人工修正过的数量分项，供列表直接提示 */
function hasFix(key?: string): boolean {
  return !!key && Object.keys(quantityFixes.value[key] ?? {}).length > 0
}

/** 列表修正标记的提示文案：列出被改的分项及其数值 */
function fixedTip(key?: string): string {
  if (!key) {
    return ''
  }
  const parts = quantityFixes.value[key] ?? {}
  const items = Object.entries(parts).map(([label, value]) => `${label}：${qty(value)}`)
  return items.length ? `数量已人工修正\n${items.join('\n')}` : ''
}

/** 修正数量后行对象会重建，详情弹窗按 key 换到新对象，避免顶部材料显示旧数据/串位 */
async function reloadAfterFix() {
  const key = detailRow.value?.key
  await loadDrawings()
  // 通知父组件自增版本号，让工程量清单面板同步刷新数量修正
  emit('changed')
  if (!detailVisible.value || !key) {
    return
  }
  detailRow.value = comparisons.value.rows.find((row) => row.key === key) ?? detailRow.value
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
    meta: item.featureDesc ? `规格 / 特征：${item.featureDesc}` : '',
    desc: [
      `${qty(item.quantity)}${item.unit || ''}`,
      item.itemTypeName,
      item.sourceSheet,
    ]
      .filter(Boolean)
      .join(' · '),
  }))
})
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
  try {
    await ElMessageBox.confirm(
      `确认取消「${row.match.itemName || `第 ${row.match.rowNo} 行`}」与该图纸材料的匹配？取消后自动匹配结果会被人工结论覆盖。`,
      '取消匹配确认',
      { type: 'warning', confirmButtonText: '取消匹配', cancelButtonText: '返回' },
    )
  } catch {
    return
  }
  await boqApi.saveDrawingMatch(props.projectId, row.match.id, row.key, 'UNMATCH')
  matchVisible.value = false
  await reloadOverrides()
  emit('changed')
  ElMessage.success('已取消该图纸材料的清单匹配')
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
    rows = rows.filter((row) => row.match && diffState(row.diffRate, row.diff) === diffFilter.value)
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
    const fixes: QuantityFixes = {}
    const results = await Promise.all(
      drawings.map(async (file) => {
        try {
          const data = await projectApi.parseResult(file.id)
          mergeQuantityFixes(fixes, data?.manualQuantityFixes as QuantityFixes | undefined)
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
    quantityFixes.value = fixes
    drawingMaterials.value = mergeDrawingMaterials(results)
    applyQuantityFixes(drawingMaterials.value, fixes)
    applyMaterialEdits(drawingMaterials.value, materialEdits.value)
    drawingMaterials.value = applyMaterialMerges(drawingMaterials.value, mergeGroups.value, fixes)
    applyMaterialEdits(drawingMaterials.value, materialEdits.value)
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
  return refreshWithMerges()
}

/** 合并组与人工编辑要先取到，图纸材料汇总后按它们覆盖，再算匹配 */
async function refreshWithMerges() {
  const [merges, edits] = await Promise.all([
    boqApi.drawingMerges(props.projectId),
    boqApi.drawingEdits(props.projectId),
  ])
  mergeGroups.value = toMergeGroups(merges)
  materialEdits.value = edits
  await Promise.all([load(), loadDrawings(), reloadOverrides()])
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

/** 数量差异文案：偏差与绝对差都在容差内才算一致，否则把方向和百分比写出来 */
function diffLabel(rate: number, diff?: number | null) {
  if (diffState(rate, diff) === 'CONSISTENT') {
    return '数量一致'
  }
  const pct = Math.abs(rate) < 0.05 ? '<0.1' : Math.abs(rate).toFixed(1)
  return `${rate > 0 ? '图纸偏大' : '图纸偏小'} ${pct}%`
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
  font-size: 13px;
  line-height: 1.5;
  word-break: break-all;
}
.diff {
  margin-left: 4px;
  font-size: 13px;
}
.diff-more {
  color: #f56c6c;
}
.diff-less {
  color: #e6a23c;
}
.diff-clickable {
  cursor: pointer;
}
.diff-clickable:hover {
  text-decoration: underline;
}
.score {
  margin-left: 4px;
  color: #909399;
  font-size: 12px;
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
.qty-fixed-tag {
  margin-left: 6px;
  vertical-align: middle;
  white-space: pre-line;
}
.merge-tag {
  margin-left: 6px;
}
.merge-hint {
  margin: 0 0 10px;
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
}
.merge-form {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
}
.merge-label {
  flex: 0 0 auto;
  color: #606266;
  font-size: 13px;
}
.merge-warn {
  margin-top: 8px;
  color: #e6a23c;
  font-size: 12px;
}
.edit-tip {
  margin: 0;
  color: #909399;
  font-size: 12px;
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
  white-space: normal;
  padding: 4px 8px;
}
:deep(.action-cell .el-button + .el-button) {
  margin-left: 0;
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
