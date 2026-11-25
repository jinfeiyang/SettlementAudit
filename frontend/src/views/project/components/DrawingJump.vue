<template>
  <span class="drawing-jump">
    <template v-if="sources.length">
      <el-button link type="primary" size="small" @click="openForView(sources[0])">
        {{ sourceLabel(sources[0]) }}
      </el-button>
      <el-popover
        v-if="sources.length > 1"
        placement="bottom"
        :width="320"
        :hide-after="0"
        trigger="click"
      >
        <template #reference>
          <el-button link size="small">等 {{ sources.length }} 页</el-button>
        </template>
        <div v-for="src in sources" :key="`${src.fileId}-${src.frame}`" class="src-row">
          <el-button link type="primary" size="small" @click="openForView(src)">
            {{ sourceLabel(src) }}
          </el-button>
          <span class="src-file">{{ src.title || src.fileName }}</span>
        </div>
      </el-popover>
      <div v-if="showTitle && sources[0].title" class="src-title">{{ sources[0].title }}</div>
    </template>
    <el-button v-else-if="fallback" link type="primary" size="small" @click="openFile">
      查看图纸
    </el-button>
    <span v-else class="muted">-</span>

    <el-dialog
      v-model="visible"
      :title="`图纸预览 - ${file?.fileName || ''}${currentLabel ? ' · ' + currentLabel : ''}`"
      width="80%"
      top="5vh"
      destroy-on-close
      append-to-body
    >
      <div v-if="file" class="preview-bar">
        <div v-if="current || material" class="preview-head">
          <div v-if="current" class="head-title">
            {{ sourceLabel(current) }}<template v-if="current.title"> · {{ current.title }}</template>
          </div>
          <!-- 对应的具体材料名称标红：如「通信光缆」 -->
          <div v-if="material" class="current-material">
            材料：{{ material }}<span v-if="spec" class="material-spec"> {{ spec }}</span>
          </div>
          <div v-if="formulaData?.parts.length" class="material-formula">
            <template v-for="(part, index) in formulaData.parts" :key="part.label">
              <span v-if="index" class="formula-op"> + </span>
              <el-input
                v-if="editing"
                v-model="editValues[part.label]"
                size="small"
                class="formula-input"
                :title="`${part.label} 数量`"
              />
              <el-button
                v-else-if="part.source"
                link
                class="formula-part"
                :type="isActiveSource(part.source) ? 'danger' : 'primary'"
                :title="`${part.label} · 点击定位到该页`"
                @click="open(part.source)"
              >{{ part.text }}</el-button>
              <span v-else class="formula-part-static" :title="part.label">{{ part.text }}</span>
              <el-tag v-if="!editing && part.fixed" type="warning" size="small" effect="plain" class="formula-fixed">改</el-tag>
            </template>
            <span class="formula-total"> = {{ formulaData.total }}</span>
            <template v-if="materialKey">
              <el-button v-if="!editing" link size="small" class="formula-action" @click="startEdit">编辑</el-button>
              <template v-else>
                <el-button link type="success" size="small" class="formula-action" @click="saveEdit">保存</el-button>
                <el-button link size="small" class="formula-action" @click="cancelEdit">取消</el-button>
                <el-button link size="small" class="formula-action" @click="resetAll" title="恢复全部解析原值">恢复</el-button>
              </template>
            </template>
          </div>
          <div v-else-if="formula" class="material-formula" :title="formula">{{ formula }}</div>
        </div>
        <div class="related-line">
          <el-button size="small" :type="overview ? 'primary' : 'default'" @click="showWhole">
            全图
          </el-button>
          <template v-if="matchMode">
            <el-button
              size="small"
              :type="currentPageMatched ? 'danger' : 'success'"
              :plain="currentPageMatched"
              :disabled="!matchTarget"
              @click="toggleMatchCurrentPage"
            >
              {{ currentPageMatched ? '取消关联此页' : '关联此页' }}
            </el-button>
            <span class="match-hint">
              {{ matchTarget ? `${matchTarget.label} · ${matchTargetDesc}` : '请先定位到某一页' }}
            </span>
          </template>
          <template v-if="relatedSources.length > 1">
            <span class="related-label">关联图纸（共 {{ relatedSources.length }} 页）</span>
            <el-button
              v-for="src in relatedSources"
              :key="`${src.fileId}-${src.frame}`"
              size="small"
              :type="isActiveSource(src) ? 'danger' : 'default'"
              :plain="isActiveSource(src)"
              :title="src.title || src.fileName"
              @click="open(src)"
            >
              {{ sourceLabel(src) }}
            </el-button>
          </template>
        </div>
      </div>
      <DrawingViewer
        v-if="file"
        ref="viewerRef"
        :key="file.id"
        :file-id="file.id"
        :file-name="file.fileName"
        hide-page-bar
        @page-change="onPageChange"
        :focus="focus"
        :highlight="highlight || material"
        :highlight-spec="spec"
        :highlight-fallback="highlightFallback || (highlight ? material : '')"
        :highlight-bounds="highlightBounds"
        show-pages
        :related-pages="relatedPageNos"
      />
    </el-dialog>
  </span>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { projectApi } from '@/api'
import DrawingViewer from './DrawingViewer.vue'
import {
  focusBoundsOf,
  type Bounds,
  type DrawingSource,
  type QuantityFormulaData,
} from './drawingCompare'

/**
 * 「在哪一页 + 点开看图」的通用入口：图纸清单、工程量清单两处共用。
 * 点击直接放大到材料表所在的那一页（有图框按整页，没有图框按表格范围）。
 */
const props = withDefaults(
  defineProps<{
    /** 这条材料出现在哪些图上 */
    sources?: DrawingSource[]
    /** 旧解析结果没有图页信息时，至少能打开整张图 */
    fallback?: { fileId: number; fileName: string } | null
    /** 是否在图页下方再显示图名 */
    showTitle?: boolean
    /** 当前查看的具体材料名称（如「通信光缆」），在预览顶部标红 */
    material?: string
    /** 材料规格，跟在名称后面一起展示 */
    spec?: string
    /**
     * 图纸里优先标红的文字（清单行名）：安装类清单行按同名行标红，
     * 图上找不到时再退回 material（材料表名称）
     */
    highlight?: string
    /** highlight 在图上找不到时的备用文字（如段落名找不到就退回材料表名称） */
    highlightFallback?: string
    /** 关联图纸上该材料的数量计算式，如「第 2 张 100 + 第 4 张 200 = 300 米」 */
    formula?: string
    /** 计算式的分项与出处：数值可点击定位，当前页数值标红 */
    formulaData?: QuantityFormulaData | null
    /** 材料标识，传了才允许修改分项数值 */
    materialKey?: string
    /** 已关联的图页（人工关联，或未人工时按自动命中），用于回显状态 */
    matchedPages?: Array<{ fileId: number; frame: number | null }>
  }>(),
  {
    sources: () => [],
    fallback: null,
    showTitle: false,
    material: '',
    spec: '',
    highlight: '',
    highlightFallback: '',
    formula: '',
    formulaData: null,
    materialKey: '',
    matchedPages: () => [],
  },
)

const emit = defineEmits<{
  (event: 'fix-saved'): void
  (event: 'toggle-match', payload: { fileId: number; frame: number | null }): void
}>()

const editing = ref(false)
const editValues = ref<Record<string, string>>({})

function startEdit() {
  editValues.value = Object.fromEntries(
    (props.formulaData?.parts ?? []).map((part) => [part.label, part.text.replace(/,/g, '')]),
  )
  editing.value = true
}

function cancelEdit() {
  editing.value = false
}

async function saveEdit() {
  if (!props.materialKey) {
    return
  }
  const parts = props.formulaData?.parts ?? []
  for (const part of parts) {
    const value = Number(editValues.value[part.label])
    if (!Number.isFinite(value) || value < 0) {
      ElMessage.warning(`「${part.label}」的数量无效`)
      return
    }
  }
  const changed = parts.filter(
    (part) => part.source && Number(editValues.value[part.label]) !== Number(part.text.replace(/,/g, '')),
  )
  if (!changed.length) {
    editing.value = false
    return
  }
  try {
    await Promise.all(changed.map((part) =>
      projectApi.saveQuantityFix(
        part.source!.fileId,
        props.materialKey,
        part.label,
        Number(editValues.value[part.label]),
      ),
    ))
    editing.value = false
    ElMessage.success('数量已修正')
    emit('fix-saved')
  } catch {
    ElMessage.error('数量修正保存失败')
  }
}

async function resetAll() {
  if (!props.materialKey) {
    return
  }
  const parts = (props.formulaData?.parts ?? []).filter((part) => part.source)
  if (!parts.length) {
    return
  }
  try {
    await Promise.all(parts.map((part) =>
      projectApi.deleteQuantityFix(part.source!.fileId, props.materialKey, part.label),
    ))
    editing.value = false
    ElMessage.success('已恢复解析原值')
    emit('fix-saved')
  } catch {
    ElMessage.error('恢复失败')
  }
}

const visible = ref(false)
const file = ref<{ id: number; fileName: string } | null>(null)
const focus = ref<Bounds | null>(null)
/** 当前定位的关联页，用于预览顶部高亮与标题 */
const current = ref<DrawingSource | null>(null)

/** 这条材料/清单项出现的所有图页，预览顶部可直接切换定位 */
const viewerRef = ref<InstanceType<typeof DrawingViewer>>()
/** 预览当前显示的图页（null = 全图概览）；页码高亮统一按它判断，避免和全图状态不一致 */
const activePage = ref<number | null>(null)
const overview = computed(() => activePage.value === null)

/**
 * 标红范围取所有关联页，而不是只看当前放大的那一页。
 * 工程量清单/图纸清单的一条材料常分布在多张分幅图上，只按 focus 过滤会漏标其它关联页。
 */
const highlightBounds = computed(() =>
  props.sources
    .map((src) => focusBoundsOf(src))
    .filter((box): box is Bounds => box != null),
)

/** 打开预览时目标页；查看器加载中补发的空页码不能把它清掉，否则「已关联」会被误判 */
let openedPage: number | null = null

function showWhole() {
  openedPage = null
  viewerRef.value?.showWhole()
}

function onPageChange(page: number | null) {
  if (page === null && openedPage !== null) {
    // 刚打开定位到某页时，忽略查看器初始化阶段补发的空页码
    activePage.value = openedPage
    return
  }
  if (page !== null) {
    openedPage = page
  }
  activePage.value = page
}

/** 关联图纸按钮是否就是当前显示的那一页 */
function isActiveSource(src: DrawingSource) {
  if (overview.value || src.fileId !== file.value?.id) {
    return false
  }
  if (src.frame != null) {
    return src.frame === activePage.value
  }
  return isCurrent(src)
}

const relatedSources = computed(() => props.sources)

/** 当前文件里与这条材料相关的图页序号：页码标橙、全图中描边 */
const relatedPageNos = computed(() =>
  relatedSources.value
    .filter((src) => src.frame !== null && (!file.value || src.fileId === file.value.id))
    .map((src) => src.frame as number),
)

const currentLabel = computed(() =>
  current.value ? sourceLabel(current.value) : file.value && !props.sources.length ? '整张图' : '',
)

function isCurrent(source: DrawingSource) {
  return current.value?.fileId === source.fileId && current.value?.frame === source.frame
}

/**
 * 页码标签：图框序号优先，因为图签印刷的张号可能重复/缺失（实测同一张图里
 * 「第8张」「第13张」各出现多次），用它当页码会出现重复且对不上定位页。
 * 只有没有图框序号时才退回张号；再没有就写「材料表」，绝不拼出「图框 undefined」。
 */
function sourceLabel(source: DrawingSource) {
  if (source.frame != null) {
    return `第 ${source.frame} 张`
  }
  if (source.sheet) {
    return `第 ${source.sheet} 张`
  }
  return '材料表'
}

/** 查看入口：从关联图纸列点页码，只查看，不带关联/取消按钮 */
function openForView(source: DrawingSource) {
  matchMode.value = false
  open(source)
}

function open(source: DrawingSource) {
  // 同一文件内切换图页不重建查看器，由查看器监听 focus 直接定位，避免重复解析 DXF
  if (file.value?.id !== source.fileId) {
    file.value = { id: source.fileId, fileName: source.fileName }
  }
  current.value = source
  // 必须新建对象：focusBoundsOf 返回的是 sources 里同一个 bbox，
  // 从全图/别的页切回这一页时引用没变，查看器就不会重新定位
  const box = focusBoundsOf(source)
  focus.value = box ? { ...box } : null
  // 页号按关联页直接设置：即便没有定位范围（图框外文字/整文件关联），
  // 也要能正确回显「已关联」状态，否则会被误判成未关联而显示「关联此页」。
  openedPage = source.frame ?? null
  activePage.value = openedPage
  visible.value = true
}

function openFile() {
  if (!props.fallback) {
    return
  }
  file.value = { id: props.fallback.fileId, fileName: props.fallback.fileName }
  current.value = null
  focus.value = null
  openedPage = null
  activePage.value = null
  visible.value = true
}

/** 是否在关联模式：只有从操作列「关联」入口进入时才显示关联/取消按钮 */
const matchMode = ref(false)

/** 关联模式下当前预览的是哪一页；全图概览时返回 null，不允许关联 */
const matchTarget = computed(() => {
  if (!file.value || activePage.value === null) {
    return null
  }
  return { fileId: file.value.id, frame: activePage.value, label: `第 ${activePage.value} 张` }
})

const matchTargetDesc = computed(() =>
  current.value?.title ? current.value.title : file.value?.fileName ?? '',
)

/** 当前预览页是否已被人工关联 */
const currentPageMatched = computed(() => {
  const target = matchTarget.value
  if (!target) {
    return false
  }
  return props.matchedPages.some(
    (page) => page.fileId === target.fileId && page.frame === target.frame,
  )
})

function toggleMatchCurrentPage() {
  const target = matchTarget.value
  if (!target) {
    return
  }
  emit('toggle-match', { fileId: target.fileId, frame: target.frame })
}

/** 关联入口：从操作列进入时开启关联模式，先定位到该段已关联/首个命中页 */
function openForMatch() {
  matchMode.value = true
  if (props.sources.length) {
    open(props.sources[0])
    return
  }
  openFile()
}

/** 供外部「操作」列调用，触发关联预览 */
defineExpose({ openForMatch })
</script>

<style scoped>
.preview-bar {
  margin-bottom: 8px;
  padding: 8px 10px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  background: #fafafa;
}
/* 标题与页码居中单独一行，关联图纸另起一行，不和页码挤在一起 */
.preview-head {
  text-align: center;
  line-height: 1.6;
}
.head-title {
  color: #303133;
  font-size: 13px;
  font-weight: 600;
}
.current-material {
  color: #f56c6c;
  font-size: 12px;
  font-weight: 600;
}
.material-spec {
  font-weight: 400;
}
.material-formula {
  color: #606266;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  line-height: 1.9;
  white-space: normal;
  /* 优先在「 + 」处断行，避免把金额/数量数字从中间拆断 */
  word-break: normal;
  overflow-wrap: anywhere;
}
.formula-op,
.formula-total,
.formula-part-static {
  white-space: pre;
}
/* 分项按钮按正文排版：不占额外高度，点击热区贴着数值 */
.formula-part.el-button,
.formula-action.el-button {
  height: auto;
  padding: 0 1px;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  vertical-align: baseline;
}
.formula-action.el-button {
  margin-left: 6px;
}
.formula-input {
  width: 72px;
}
.formula-fixed.el-tag {
  height: 16px;
  padding: 0 3px;
  margin: 0 2px;
  font-size: 10px;
  line-height: 14px;
  vertical-align: middle;
}
.related-line {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed #ebeef5;
}
.related-label {
  color: #606266;
  font-size: 12px;
  margin-right: 2px;
}
.match-hint {
  color: #909399;
  font-size: 12px;
}
.drawing-jump {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 2px 4px;
}
.src-title {
  width: 100%;
  color: #909399;
  font-size: 11px;
  line-height: 1.4;
  word-break: break-all;
}
.src-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 0;
}
.src-file {
  color: #909399;
  font-size: 11px;
  word-break: break-all;
}
.muted {
  color: #a8abb2;
  font-size: 12px;
}
</style>
