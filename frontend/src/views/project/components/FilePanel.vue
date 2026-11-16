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

    <el-table v-loading="loading" :data="files" empty-text="暂无资料，请上传清单或图纸">
      <el-table-column prop="fileName" label="文件名" min-width="220" show-overflow-tooltip />
      <el-table-column label="类型" width="110">
        <template #default="{ row }">{{ typeLabel(row.fileType) }}</template>
      </el-table-column>
      <el-table-column label="大小" width="100">
        <template #default="{ row }">{{ fileSize(row.fileSize) }}</template>
      </el-table-column>
      <el-table-column label="解析状态" width="120">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.parseStatus)" size="small">{{ row.parseStatusName }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="可解析率" width="110" align="right">
        <template #default="{ row }">{{ row.parseRate === undefined || row.parseRate === null ? '-' : percent(row.parseRate) }}</template>
      </el-table-column>
      <el-table-column prop="parseMessage" label="解析提示" min-width="260" show-overflow-tooltip />
      <el-table-column label="操作" width="340" fixed="right">
        <template #default="{ row }">
          <el-button v-if="isDrawingFile(row)" link type="primary" @click="openDrawing(row)">查看图纸</el-button>
          <el-button v-if="isDrawing(row)" link type="primary" @click="showResult(row)">量算结果</el-button>
          <el-button link type="primary" @click="parse(row)">重新解析</el-button>
          <el-button link type="primary" @click="projectApi.downloadFile(row.id, row.fileName)">下载</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="resultVisible" title="图纸解析与量算结果（A1）" width="70%">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="解析摘要" :span="2">
          {{ result.summary || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="图元数量">{{ measure.totalEntities ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="图纸单位">{{ result.unit || '-' }}</el-descriptions-item>
        <el-descriptions-item label="线路总长（米）">{{ measure.totalLengthMeters ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="线缆图层长度（米）">{{ measure.cableLengthMeters ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="设备数量（个）">{{ measure.deviceCount ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="材料表 / 条目">
          {{ measure.materialTableCount ?? 0 }} 张 / {{ measure.materialItemCount ?? 0 }} 条
        </el-descriptions-item>
        <el-descriptions-item label="表格识别方式">{{ tableSourceLabel() }}</el-descriptions-item>
        <el-descriptions-item label="说明" :span="2">{{ result.hint || '-' }}</el-descriptions-item>
      </el-descriptions>

      <template v-if="projectInfo.length">
        <h4>项目情况</h4>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item
            v-for="item in projectInfo"
            :key="item.label"
            :label="item.label"
            :span="item.value.length > 24 ? 2 : 1"
          >{{ item.value }}</el-descriptions-item>
        </el-descriptions>
      </template>

      <h4>材料设备清单归纳统计</h4>
      <el-table
        v-if="materialSummary.length"
        :data="materialSummary"
        size="small"
        border
        stripe
        max-height="320"
        class="summary-table"
        :header-cell-style="{ background: '#fafafa', color: '#606266', fontWeight: 600 }"
      >
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column prop="name" label="名称" min-width="170" show-overflow-tooltip />
        <el-table-column prop="spec" label="规格程式" min-width="260" show-overflow-tooltip />
        <el-table-column prop="unit" label="单位" width="70" align="center" />
        <el-table-column prop="quantity" label="合计数量" width="130" align="right" class-name="num-cell">
          <template #default="{ row }">{{ formatQuantity(row.quantity) }}</template>
        </el-table-column>
        <el-table-column prop="tableCount" label="来源表数" width="100" align="right" class-name="num-cell" />
      </el-table>
      <el-empty v-else description="未识别到可统计的材料数量" :image-size="60" />

      <h4>材料设备清单明细</h4>
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
      <el-empty v-else description="未识别到材料设备清单表格，可确认该图纸是否含工程量/材料明细表" :image-size="60" />

      <h4>线缆图层长度（米）</h4>
      <el-table :data="layerRows" size="small" border empty-text="未识别到线缆图层">
        <el-table-column prop="layer" label="图层" />
        <el-table-column prop="length" label="长度（米）" width="160" align="right" />
      </el-table>

      <h4>设备块统计</h4>
      <el-table :data="blockRows" size="small" border empty-text="未识别到设备块">
        <el-table-column prop="block" label="块名称" />
        <el-table-column prop="count" label="数量" width="120" align="right" />
      </el-table>
    </el-dialog>

    <el-dialog
      v-model="drawingVisible"
      :title="`图纸预览 - ${drawingFile?.fileName || ''}`"
      width="80%"
      top="5vh"
      destroy-on-close
    >
      <DrawingViewer v-if="drawingFile" :file-id="drawingFile.id" :file-name="drawingFile.fileName" />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox, type UploadRequestOptions } from 'element-plus'
import { boqApi, projectApi, type FileVO } from '@/api'
import { fileSize, percent, statusTag } from '@/utils/format'
import DrawingViewer from './DrawingViewer.vue'

const props = defineProps<{ projectId: number }>()
const emit = defineEmits<{ (e: 'changed'): void }>()

const files = ref<FileVO[]>([])
const loading = ref(false)
const uploading = ref(false)
const resultVisible = ref(false)
const drawingVisible = ref(false)
const drawingFile = ref<FileVO | null>(null)
const result = ref<Record<string, any>>({})
const measure = ref<Record<string, any>>({})
const projectInfo = ref<{ label: string; value: string }[]>([])
const layerRows = ref<{ layer: string; length: number }[]>([])
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
let timer: number | undefined

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

/** 查看图纸解析与量算结果（A1：线路长度、设备数量） */
async function showResult(row: FileVO) {
  result.value = await projectApi.parseResult(row.id)
  measure.value = result.value.measure ?? {}
  const layers: Record<string, number> = measure.value.cableLayers ?? {}
  layerRows.value = Object.keys(layers).map((layer) => ({ layer, length: layers[layer] }))
  const blocks: Record<string, number> = measure.value.deviceBlocks ?? {}
  blockRows.value = Object.keys(blocks).map((block) => ({ block, count: blocks[block] }))
  projectInfo.value = result.value.projectInfo ?? []
  materialTables.value = result.value.materials ?? []
  materialSummary.value = result.value.materialSummary ?? []
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
</style>
