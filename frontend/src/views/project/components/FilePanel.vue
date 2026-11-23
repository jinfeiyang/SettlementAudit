<template>
  <div>
    <div class="toolbar">
      <el-upload :show-file-list="false" :http-request="onUpload" accept=".xlsx,.xls,.dxf,.dwg">
        <el-button type="primary" :loading="uploading">上传资料</el-button>
      </el-upload>
      <el-button @click="boqApi.template(projectId)">下载清单模板</el-button>
      <el-button @click="load">刷新</el-button>
      <span class="hint">
        上传后自动解析：清单 Excel → 结构化清单行（含行号）；DXF/DWG → 线路长度与设备数量量算（依赖 AI 服务）
      </span>
    </div>

    <h4 class="section">清单文件（Excel）</h4>
    <el-table v-loading="loading" :data="boqFiles" empty-text="暂无清单文件，请输入工程量清单 Excel">
      <el-table-column prop="fileName" label="文件名" min-width="240" show-overflow-tooltip />
      <el-table-column label="类型" width="110">
        <template #default="{ row }">{{ typeLabel(row.fileType) }}</template>
      </el-table-column>
      <el-table-column label="大小" width="90">
        <template #default="{ row }">{{ fileSize(row.fileSize) }}</template>
      </el-table-column>
      <el-table-column label="解析状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.parseStatus)" size="small">{{ row.parseStatusName }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="可解析率" width="100" align="right">
        <template #default="{ row }">{{ row.parseRate === undefined || row.parseRate === null ? '-' : percent(row.parseRate) }}</template>
      </el-table-column>
      <el-table-column prop="parseMessage" label="导入摘要" min-width="280" show-overflow-tooltip />
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="showBoqResult(row)">导入结果</el-button>
          <el-button link type="primary" @click="parse(row)">重新解析</el-button>
          <el-button link type="primary" @click="projectApi.downloadFile(row.id, row.fileName)">下载</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <h4 class="section">图纸文件（DXF / DWG）</h4>
    <el-table v-loading="loading" :data="drawingFiles" empty-text="暂无图纸文件，请上传竣工图 DXF/DWG">
      <el-table-column prop="fileName" label="文件名" min-width="240" show-overflow-tooltip />
      <el-table-column label="类型" width="110">
        <template #default="{ row }">{{ typeLabel(row.fileType) }}</template>
      </el-table-column>
      <el-table-column label="大小" width="90">
        <template #default="{ row }">{{ fileSize(row.fileSize) }}</template>
      </el-table-column>
      <el-table-column label="解析状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.parseStatus)" size="small">{{ row.parseStatusName }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="parseMessage" label="解析提示" min-width="280" show-overflow-tooltip />
      <el-table-column label="操作" width="330" fixed="right">
        <template #default="{ row }">
          <el-button v-if="isDrawingFile(row)" link type="primary" @click="openDrawing(row)">查看图纸</el-button>
          <el-button v-if="isDrawing(row)" link type="primary" @click="showResult(row)">量算结果</el-button>
          <el-button link type="primary" @click="parse(row)">重新解析</el-button>
          <el-button link type="primary" @click="projectApi.downloadFile(row.id, row.fileName)">下载</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="boqResultVisible" title="清单导入结果" width="580px">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="文件">{{ boqFile?.fileName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="解析状态">{{ boqFile?.parseStatusName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="可解析率">{{ percent(boqFile?.parseRate) }}</el-descriptions-item>
        <el-descriptions-item label="导入摘要">{{ boqFile?.parseMessage || '-' }}</el-descriptions-item>
      </el-descriptions>
      <h4 class="section">当前清单类别统计</h4>
      <el-table :data="boqTypeStats" size="small" border empty-text="清单为空，请重新解析">
        <el-table-column prop="label" label="类别" />
        <el-table-column prop="count" label="条数" width="100" align="right" />
      </el-table>
      <template #footer>
        <el-button @click="boqResultVisible = false">关闭</el-button>
        <el-button type="primary" @click="gotoBoq">去「清单」查看明细</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resultVisible" title="图纸解析与量算结果" width="72%" top="6vh">
      <div class="result-dialog">
        <el-tabs v-model="resultTab" class="result-tabs">
        <el-tab-pane label="量算概览" name="overview">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="解析摘要" :span="2">
              {{ result.summary || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="图元数量">{{ measure.totalEntities ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="图纸单位">{{ result.unit || '-' }}</el-descriptions-item>
            <el-descriptions-item label="线路总长（米）">{{ measure.totalLengthMeters ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="线缆图层长度（米）">{{ measure.cableLengthMeters ?? '-' }}</el-descriptions-item>
            <el-descriptions-item v-if="measure.deviceCount" label="设备数量（个）">
              {{ measure.deviceCount }}
            </el-descriptions-item>
            <el-descriptions-item label="材料表 / 条目">
              {{ measure.materialTableCount ?? 0 }} 张 / {{ measure.materialItemCount ?? 0 }} 条
            </el-descriptions-item>
            <el-descriptions-item label="表格识别方式">{{ tableSourceLabel() }}</el-descriptions-item>
            <el-descriptions-item label="说明">{{ result.hint || '-' }}</el-descriptions-item>
          </el-descriptions>

          <template v-if="projectInfo.length">
            <h4 class="section-title">项目情况</h4>
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item
                v-for="item in projectInfo"
                :key="item.label"
                :label="item.label"
                :span="item.value.length > 24 ? 2 : 1"
              >{{ item.value }}</el-descriptions-item>
            </el-descriptions>
          </template>

          <template v-if="blockRows.length">
            <h4 class="section-title">设备块统计</h4>
            <el-table :data="blockRows" size="small" border>
              <el-table-column prop="block" label="块名称" />
              <el-table-column prop="count" label="数量" width="120" />
            </el-table>
          </template>
        </el-tab-pane>

        <el-tab-pane :label="`材料归纳统计 (${materialSummary.length})`" name="summary">
          <el-table
            v-if="materialSummary.length"
            :data="materialSummary"
            size="small"
            border
            stripe
            max-height="56vh"
            class="summary-table"
            :header-cell-style="{ background: '#fafafa', color: '#606266', fontWeight: 600 }"
          >
            <el-table-column type="index" label="序号" width="60" />
            <el-table-column prop="name" label="名称" min-width="170" show-overflow-tooltip />
            <el-table-column prop="spec" label="规格程式" min-width="260" show-overflow-tooltip />
            <el-table-column prop="unit" label="单位" width="70" />
            <el-table-column prop="quantity" label="合计数量" width="130" class-name="num-cell">
              <template #default="{ row }">{{ formatQuantity(row.quantity) }}</template>
            </el-table-column>
            <el-table-column prop="tableCount" label="来源表数" width="100" class-name="num-cell" />
          </el-table>
          <el-empty v-else description="未识别到可统计的材料数量" :image-size="60" />
        </el-tab-pane>

        <el-tab-pane :label="`材料清单明细 (${materialTables.length})`" name="detail">
          <div v-if="materialTables.length" class="material-scroll">
            <div v-for="table in materialTables" :key="table.table" class="material-table">
              <div class="material-title">
                表 {{ table.frameIndex ?? table.table }}
                <span v-if="table.frame" class="material-block">{{ frameLabel(table) }}</span>
              </div>
              <el-table :data="table.rows" size="small" border>
                <el-table-column
                  v-for="(column, index) in table.columns"
                  :key="index"
                  :label="column"
                  min-width="90"
                >
                  <template #default="{ row }">{{ row[index] }}</template>
                </el-table-column>
              </el-table>
            </div>
          </div>
          <el-empty
            v-else
            description="未识别到材料设备清单表格，可确认该图纸是否含工程量/材料明细表"
            :image-size="60"
          />
        </el-tab-pane>

        <el-tab-pane label="光缆长度核查" name="cable">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="图纸计算长度">
              {{ formatQuantity(cableCheck.computed) }} 米
            </el-descriptions-item>
            <el-descriptions-item label="清单申报长度">
              {{ cableCheck.reported === null ? '-' : `${formatQuantity(cableCheck.reported)} 米` }}
            </el-descriptions-item>
            <el-descriptions-item label="差异">
              <span v-if="cableCheck.diff === null || cableCheck.status === 'NO_BASIS'" class="muted">
                口径不同，不计差异
              </span>
              <span v-else :class="cableCheck.diff < -0.01 ? 'diff-less' : 'diff-ok'">
                {{ cableCheck.diff > 0 ? '+' : '' }}{{ formatQuantity(cableCheck.diff) }} 米
                <template v-if="cableCheck.diffRate !== null">（{{ formatQuantity(cableCheck.diffRate) }}%）</template>
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="核查结论">
              <el-tag :type="cableTagType" size="small">
                {{ cableOverall === 'LESS' ? '清单小于图纸，疑似少计' : cableStatusText(cableCheck) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="数据来源">
              {{ cableSourceText(drawingCable.source) }}
            </el-descriptions-item>
            <el-descriptions-item label="长度来源">
              <span v-if="measure.annotationLengthMeters">
                {{ annotationBasisText(measure.annotationBasis) }}，
                合计 {{ formatQuantity(measure.annotationLengthMeters) }} 米
                （共 {{ measure.annotationCount ?? lengthAnnotations.length }} 条标注参与核对）
              </span>
              <span v-else class="muted">未识别到长度标注</span>
            </el-descriptions-item>
          </el-descriptions>
          <el-alert
            v-if="cableOverall === 'LESS'"
            class="cable-alert"
            type="error"
            :closable="false"
            show-icon
            :title="`清单申报长度小于图纸计算长度 ${formatQuantity(cableShortfall)} 米，工程量疑似少计，请核对清单与图纸`"
          />
          <el-alert
            v-else-if="cableOverall === 'NO_BOQ'"
            class="cable-alert"
            type="warning"
            :closable="false"
            show-icon
            title="清单中没有找到光缆类工程量行（长度单位），无法比对"
          />
          <el-alert
            v-else-if="cableOverall === 'NO_BASIS'"
            class="cable-alert"
            type="info"
            :closable="false"
            show-icon
            title="清单里的光缆按芯数分行（4芯/8芯/12芯/24芯）是甲供领用量，与图纸路由长度不是同一个量，不做少计判定"
          />

          <template v-if="annotationSheetRows.length">
            <h4 class="section-title">
              按张核对（图纸计算长度）
              <span class="muted">
                共 {{ annotationSheetRows.length }} 张，合计
                {{ formatQuantity(measure.annotationLengthMeters) }} 米；同一张分幅画的几个图框合起来算，
                「组成」就是材料表里那几行光缆，可以拿图纸逐行对
              </span>
            </h4>
            <el-table :data="annotationSheetRows" size="small" border class="cable-table">
              <el-table-column prop="sheet" label="图签张号" width="100" />
              <el-table-column prop="sourceLabel" label="长度来源" width="100" />
              <el-table-column label="光缆长度（米）" width="130">
                <template #default="{ row }">{{ formatQuantity(row.meters) }}</template>
              </el-table-column>
              <el-table-column label="逐段累加（米）" min-width="200" class-name="wrap-cell">
                <template #default="{ row }">
                  <template v-if="row.segmentText">
                    {{ row.segmentText }} <b class="seg-total">= {{ formatQuantity(row.segmentTotal) }}</b>
                  </template>
                  <span v-else class="muted">无逐段标注</span>
                </template>
              </el-table-column>
              <el-table-column prop="composition" label="组成（规格 米数）" min-width="240" class-name="wrap-cell">
                <template #default="{ row }">
                  {{ row.composition || '无材料表，按逐段标注取量' }}
                </template>
              </el-table-column>
              <el-table-column prop="count" label="标注条数" width="100" />
            </el-table>
          </template>

          <template v-if="cableLines.length">
            <h4 class="section-title">按敷设方式比对</h4>
            <el-table :data="cableLines" size="small" border class="cable-table">
              <el-table-column prop="label" label="敷设方式" width="130" />
              <el-table-column label="图纸计算（米）" width="140">
                <template #default="{ row }">{{ formatQuantity(row.computed) }}</template>
              </el-table-column>
              <el-table-column label="清单申报（米）" width="140">
                <template #default="{ row }">
                  {{ row.reported === null ? '-' : formatQuantity(row.reported) }}
                </template>
              </el-table-column>
              <el-table-column label="结论" min-width="180">
                <template #default="{ row }">
                  <el-tag
                    :type="row.status === 'LESS' ? 'danger' : row.status === 'OK' ? 'success' : 'warning'"
                    size="small"
                    effect="plain"
                  >
                    {{ row.status === 'LESS' ? '清单小于图纸' : row.status === 'OK' ? '通过' : row.status === 'NO_BOQ' ? '清单无此类' : '图纸无此图层' }}
                  </el-tag>
                </template>
              </el-table-column>
            </el-table>
          </template>

          <template v-if="cableCheck.rows.length">
            <h4 class="section-title">参与比对的清单行</h4>
            <el-table :data="cableCheck.rows" size="small" border class="cable-table" empty-text="无">
              <el-table-column prop="itemName" label="名称" min-width="200" class-name="wrap-cell" />
              <el-table-column prop="spec" label="规格" min-width="220" class-name="wrap-cell">
                <template #default="{ row }">{{ row.spec || '-' }}</template>
              </el-table-column>
              <el-table-column prop="quantity" label="数量" width="110">
                <template #default="{ row }">{{ formatQuantity(row.quantity) }}</template>
              </el-table-column>
              <el-table-column prop="unit" label="单位" width="70" />
              <el-table-column label="折算长度（米）" width="130">
                <template #default="{ row }">{{ formatQuantity(row.meters) }}</template>
              </el-table-column>
            </el-table>
          </template>
        </el-tab-pane>
        </el-tabs>
      </div>
      <template #footer>
        <el-button @click="resultVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="drawingVisible"
      :title="`图纸预览 - ${drawingFile?.fileName || ''}`"
      width="80%"
      top="5vh"
      destroy-on-close
    >
      <DrawingViewer v-if="drawingFile" :file-id="drawingFile.id" :file-name="drawingFile.fileName" show-pages />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox, type UploadRequestOptions } from 'element-plus'
import { boqApi, projectApi, type BoqItemVO, type FileVO } from '@/api'
import { fileSize, percent, statusTag } from '@/utils/format'
import {
  annotationBasisText,
  annotationSheets,
  cableCategoryLines,
  cableSourceText,
  cableStatusText,
  checkCableLength,
  toDrawingCable,
} from '@/utils/cableCheck'
import DrawingViewer from './DrawingViewer.vue'

const props = defineProps<{ projectId: number }>()
const emit = defineEmits<{ (e: 'changed'): void; (e: 'go', tab: string): void }>()

const files = ref<FileVO[]>([])
const loading = ref(false)
const uploading = ref(false)
const resultVisible = ref(false)
const drawingVisible = ref(false)
const drawingFile = ref<FileVO | null>(null)
const boqResultVisible = ref(false)
const boqFile = ref<FileVO | null>(null)
const boqTypeStats = ref<{ label: string; count: number }[]>([])
const result = ref<Record<string, any>>({})
const measure = ref<Record<string, any>>({})
/** 量算结果弹窗的页签：量算概览 / 材料归纳统计 / 材料清单明细 / 光缆长度核查 */
const resultTab = ref('overview')
const projectInfo = ref<{ label: string; value: string }[]>([])
const blockRows = ref<{ block: string; count: number }[]>([])
const materialTables = ref<{
  table: number
  frameIndex: number | null
  frame: number | null
  frameSheet: string | null
  frameTitle: string | null
  columns: string[]
  rows: string[][]
}[]>([])
const materialSummary = ref<{ name: string; spec: string; unit: string; quantity: number; tableCount: number }[]>([])
/** 清单行：光缆长度核查要拿图纸算出来的量跟清单申报量比 */
const boqItems = ref<BoqItemVO[]>([])
let timer: number | undefined

/** 图上的段长标注（5.7、管453.7 这类），图纸没画线缆图层时就是长度来源 */
const lengthAnnotations = computed(
  () => (measure.value.lengthAnnotations ?? []) as { text: string; value: number; layer: string }[],
)
/** 当前图纸算出来的光缆长度 */
const drawingCable = computed(() => toDrawingCable(measure.value))
/** 当前图纸的光缆长度 vs 清单申报长度 */
const cableCheck = computed(() => checkCableLength(drawingCable.value, boqItems.value))
/** 分类比对：管道、架空分开看，合计通过但某一类少计也要报出来 */
const cableLines = computed(() => cableCategoryLines(drawingCable.value, boqItems.value))
const cableOverall = computed(() =>
  cableLines.value.some((line) => line.status === 'LESS') ? 'LESS' : cableCheck.value.status,
)
/** 少计的缺口：取分类里最大的那一类，没有分类就按合计差 */
const cableShortfall = computed(() => {
  const less = cableLines.value.filter((line) => line.status === 'LESS')
  if (!less.length) {
    return Math.abs(cableCheck.value.diff ?? 0)
  }
  return Math.max(...less.map((line) => Math.abs((line.reported ?? 0) - line.computed)))
})

const cableTagType = computed(() => {
  switch (cableOverall.value) {
    case 'LESS':
      return 'danger'
    case 'OK':
      return 'success'
    case 'NO_BASIS':
      return 'info'
    default:
      return 'warning'
  }
})

/** 按张核对：每张图的光缆长度来源、米数与组成 */
const annotationSheetRows = computed(() => annotationSheets(measure.value))

/** 清单与图纸分两张表展示，避免混在一起看不清解析目标 */
const boqFiles = computed(() => files.value.filter((f) => f.fileType === 'BOQ_EXCEL'))
const drawingFiles = computed(() => files.value.filter((f) => isDrawingFile(f)))

function isDrawingFile(row: FileVO) {
  return row.fileType === 'DXF' || row.fileType === 'DWG'
}

function isDrawing(row: FileVO) {
  return isDrawingFile(row) && row.parseStatus === 'SUCCESS'
}

/** 图纸预览：交互渲染 DXF，超大或渲染失败时自动降级为服务端快照 */
function openDrawing(row: FileVO) {
  drawingFile.value = row
  drawingVisible.value = true
}

/** 清单导入结果：文件侧解析情况 + 当前清单各类别条数，便于判断是否需要重新解析 */
async function showBoqResult(row: FileVO) {
  boqFile.value = row
  boqTypeStats.value = []
  boqResultVisible.value = true
  const items = await boqApi.all(props.projectId)
  const counter = new Map<string, number>()
  items.forEach((item) => {
    const label = item.itemTypeName || '工程量清单'
    counter.set(label, (counter.get(label) ?? 0) + 1)
  })
  boqTypeStats.value = [...counter.entries()].map(([label, count]) => ({ label, count }))
}

/** 跳到「工程量清单」页看明细 */
function gotoBoq() {
  boqResultVisible.value = false
  emit('go', 'boq')
}

/** 查看图纸解析与量算结果（A1：线路长度、设备数量），并做光缆长度核查 */
async function showResult(row: FileVO) {
  result.value = await projectApi.parseResult(row.id)
  measure.value = result.value.measure ?? {}
  try {
    boqItems.value = await boqApi.all(props.projectId)
  } catch {
    boqItems.value = []
  }
  const blocks: Record<string, number> = measure.value.deviceBlocks ?? {}
  blockRows.value = Object.keys(blocks).map((block) => ({ block, count: blocks[block] }))
  projectInfo.value = result.value.projectInfo ?? []
  materialTables.value = result.value.materials ?? []
  materialSummary.value = result.value.materialSummary ?? []
  resultTab.value = 'overview'
  resultVisible.value = true
}

/** 表格识别方式：后端会说明这张图是用哪种画法抽出表的，0 张时提示未识别 */
function tableSourceLabel() {
  const sources: { key: string; label: string; count: number }[] =
    result.value.materialDiagnostics?.sources ?? []
  const hit = sources.filter((item) => item.count > 0)
  if (!hit.length) return '未识别到表格'
  return hit.map((item) => `${item.label} ${item.count} 张`).join(' / ')
}

/** 归纳表的数量：整数不带小数点，长数字加千分位，便于肉眼核对量级 */
function formatQuantity(value: number) {
  if (value === null || value === undefined || Number.isNaN(value)) return '-'
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 3 })
}

/** 材料表来源图框：优先用图签印的「第 X 张」+ 图名，取不到再退回图框序号 */
function frameLabel(table: { frame: number | null; frameSheet: string | null; frameTitle: string | null }) {
  if (!table.frameSheet) return `图框 ${table.frame}`
  const title = table.frameTitle ? ` · ${table.frameTitle}` : ''
  return `第 ${table.frameSheet} 张${title}`
}

function typeLabel(type: string) {
  switch (type) {
    case 'BOQ_EXCEL':
      return '工程量清单'
    case 'DXF':
      return 'DXF 图纸'
    case 'DWG':
      return 'DWG 图纸'
    case 'RULE_EXCEL':
      return '规则表'
    default:
      return '其他资料'
  }
}

async function load() {
  loading.value = true
  try {
    files.value = await projectApi.files(props.projectId)
    schedulePoll()
  } finally {
    loading.value = false
  }
}

/** 解析中/待解析时轮询状态 */
function schedulePoll() {
  const running = files.value.some((f) => f.parseStatus === 'PARSING' || f.parseStatus === 'PENDING')
  if (running && !timer) {
    timer = window.setInterval(async () => {
      files.value = await projectApi.files(props.projectId)
      if (!files.value.some((f) => f.parseStatus === 'PARSING' || f.parseStatus === 'PENDING')) {
        stopPoll()
        emit('changed')
      }
    }, 2000)
  }
}

function stopPoll() {
  if (timer) {
    window.clearInterval(timer)
    timer = undefined
  }
}

async function onUpload(options: UploadRequestOptions) {
  uploading.value = true
  try {
    const file = await projectApi.upload(props.projectId, options.file as File)
    ElMessage.success(`已上传：${file.fileName}，开始解析`)
    await projectApi.parseFile(file.id)
    await load()
    emit('changed')
  } finally {
    uploading.value = false
  }
}

async function parse(row: FileVO) {
  await projectApi.parseFile(row.id)
  ElMessage.info('已提交解析，请稍候刷新状态')
  await load()
}

async function remove(row: FileVO) {
  await ElMessageBox.confirm(`确认删除「${row.fileName}」？`, '删除确认', { type: 'warning' })
  await projectApi.deleteFile(row.id)
  ElMessage.success('已删除')
  await load()
  emit('changed')
}

onMounted(load)
onBeforeUnmount(stopPoll)
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
.summary-table {
  width: 100%;
}

/* 归纳表：单元格收紧一点，长规格被省略号截断后靠 tooltip 看全 */
.summary-table :deep(.el-table__cell) {
  padding: 5px 8px;
}

.summary-table :deep(.cell) {
  line-height: 22px;
}

/* 数量、来源表数按等宽数字对齐，10838 与 2969.4 的小数位不会左右抖 */
.summary-table :deep(.num-cell .cell) {
  font-variant-numeric: tabular-nums;
}

.material-scroll {
  max-height: 46vh;
  overflow: auto;
  padding-right: 4px;
}
.material-table {
  margin-bottom: 12px;
}
.material-title {
  font-size: 12px;
  color: #606266;
  margin: 0 0 4px;
}
.material-block {
  margin-left: 6px;
  color: #a8abb2;
}

/* 光缆长度核查：差异为负（清单少计）标红，通过标绿 */
.cable-alert {
  margin: 8px 0 0;
}
.cable-table {
  margin-top: 8px;
}
.seg-total {
  color: #409eff;
  font-variant-numeric: tabular-nums;
}
.diff-less {
  color: #f56c6c;
  font-weight: 600;
}
.diff-ok {
  color: #67c23a;
}
.cable-table :deep(.wrap-cell .cell) {
  white-space: normal;
  word-break: break-all;
  line-height: 1.45;
}

/* 量算结果弹窗：按页签分区，各页的小标题统一留白 */
.result-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}
.section-title {
  margin: 16px 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}
.section-title:first-child {
  margin-top: 0;
}

/* 表格数据统一居中（表头 + 单元格），量算结果里的数字都是一个量级的对照，居中最整齐 */
.result-dialog :deep(.el-table .cell) {
  text-align: center;
}
.result-dialog :deep(.el-descriptions__label) {
  text-align: center;
}
</style>
