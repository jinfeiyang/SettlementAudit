<template>
  <div class="drawing-viewer">
    <div class="viewer-toolbar">
      <el-tag v-if="mode === 'svg'" size="small" type="warning">快照模式</el-tag>
      <el-select
        v-if="layers.length"
        v-model="hiddenLayers"
        multiple
        collapse-tags
        collapse-tags-tooltip
        size="small"
        placeholder="隐藏图层"
        class="layer-select"
        @change="applyLayers"
      >
        <template #header>
          <div class="layer-actions">
            <el-checkbox :model-value="allVisible" @change="toggleAllVisible">全部显示</el-checkbox>
            <span class="layer-count">共 {{ layers.length }} 个图层</span>
          </div>
        </template>
        <el-option v-for="l in layers" :key="l.name" :label="l.displayName || l.name" :value="l.name" />
      </el-select>
      <el-button size="small" :disabled="mode !== 'dxf' || !viewerReady" @click="fitView">适应窗口</el-button>
      <el-button
        size="small"
        :type="copyMode ? 'primary' : 'default'"
        :loading="textLoading"
        :disabled="mode !== 'dxf' || !viewerReady"
        @click="toggleCopyMode"
      >
        {{ copyMode ? '退出复制模式' : '复制模式' }}
      </el-button>
      <div class="search-box">
        <el-input
          v-model="searchKeyword"
          size="small"
          placeholder="搜索图纸文字并定位"
          clearable
          :disabled="mode !== 'dxf' || !viewerReady"
          @focus="onSearchFocus"
          @click="onSearchFocus"
          @blur="onSearchBlur"
          @keyup.enter="onSearchEnter"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <div v-if="searchOpen && searchKeyword" class="search-dropdown">
          <div v-if="searchLoading" class="search-tip">文字加载中…</div>
          <template v-else-if="searchGroups.length">
            <div class="search-count">找到 {{ searchMatchCount }} 处，按图张分组</div>
            <div v-for="group in searchGroups" :key="group.key" class="search-group">
              <div class="search-group-head" @mousedown.prevent="focusSearchGroup(group)">
                <span class="search-group-title">{{ group.title }}</span>
                <span class="search-group-count">{{ group.count }} 处 {{ expandedGroup === group.key ? '▾' : '▸' }}</span>
              </div>
              <template v-if="expandedGroup === group.key">
                <div
                  v-for="item in group.hits"
                  :key="item.key"
                  class="search-item"
                  @mousedown.prevent="focusSearchResult(item)"
                >
                  <span class="search-item-text">{{ item.before }}<b class="search-kw">{{ item.match }}</b>{{ item.after }}</span>
                </div>
              </template>
            </div>
          </template>
          <div v-else class="search-tip">未找到匹配文字</div>
        </div>
      </div>
      <span class="meta">{{ fileSizeText }}</span>
    </div>

    <div v-if="mode === 'dxf' && showPages && !hidePageBar && frames.length > 1" class="page-bar">
      <el-button size="small" :type="activePage === null ? 'primary' : 'default'" @click="fitView">
        全图
      </el-button>
      <el-button
        v-for="frame in frames"
        :key="frame.no"
        size="small"
        :type="activePage === frame.no ? 'primary' : isRelatedPage(frame.no) ? 'warning' : 'default'"
        :plain="activePage !== frame.no && isRelatedPage(frame.no)"
        :title="isRelatedPage(frame.no) ? '关联图页' : ''"
        @click="focusFrame(frame)"
      >
        {{ frameLabel(frame) }}
      </el-button>
    </div>

    <div
      v-loading="loading"
      element-loading-text="图纸转换与渲染中，首次打开需数秒…"
      class="stage-wrap"
      :class="{
        pickable: showPages && activePage === null && frames.length > 1,
        'copy-mode': copyMode,
      }"
    >
      <div
        v-show="mode === 'dxf'"
        ref="stage"
        class="stage"
        :class="{ 'hidden-in-copy': copyMode }"
      />
      <div v-if="copyMode" class="copy-view" @mousedown.stop @wheel.stop>
        <div class="copy-filter">
          <span class="copy-filter-label">图页筛选</span>
          <el-select v-model="copyPageFilter" size="small" class="copy-filter-select">
            <el-option label="全部图页" value="all" />
            <el-option
              v-for="group in allCopyGroups"
              :key="group.key"
              :label="group.title"
              :value="group.key"
            />
          </el-select>
          <span class="copy-filter-hint">共 {{ visibleCopyGroups.length }} 页文字</span>
        </div>
        <template v-if="visibleCopyGroups.length">
          <section v-for="group in visibleCopyGroups" :key="group.key" class="copy-group">
            <h4 class="copy-group-title">{{ group.title }}</h4>
            <p v-for="(line, index) in group.lines" :key="index" class="copy-line">{{ line }}</p>
          </section>
        </template>
        <el-empty v-else description="该图页没有可复制的文字" />
      </div>
      <el-tooltip
        v-for="spot in hotspots"
        :key="spot.no"
        :content="`${spot.label}${isRelatedPage(spot.no) ? '（关联页）' : ''}`"
        placement="top"
        :show-after="0"
      >
        <div
          class="page-hotspot"
          :class="{ related: isRelatedPage(spot.no) }"
          :style="{ left: `${spot.left}px`, top: `${spot.top}px`, width: `${spot.width}px`, height: `${spot.height}px` }"
          @click="focusPageByNo(spot.no)"
        />
      </el-tooltip>
      <template v-if="!copyMode">
        <div
          v-for="marker in searchMarkers"
          :key="marker.key"
          class="search-marker"
          :style="{ left: `${marker.x}px`, top: `${marker.y}px` }"
        >
          <span v-if="marker.showLabel" class="search-marker-label">{{ marker.text }}</span>
        </div>
      </template>
      <div v-if="mode === 'svg'" class="snapshot">
        <img v-if="svgUrl" :src="svgUrl" alt="图纸快照" />
      </div>
    </div>

    <el-alert v-if="message" :title="message" type="warning" :closable="false" show-icon class="msg" />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as THREE from 'three'
import { DxfViewer, type LayerInfo } from 'dxf-viewer'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { projectApi, type DrawingTextLabel } from '@/api'
import { highlightDxfText, type DxfBounds } from '@/utils/dxfHighlight'

const props = defineProps<{
  fileId: number
  fileName: string
  /** 只显示某一页：传该图框的坐标范围，加载完直接定位过去 */
  focus?: { minX: number; minY: number; maxX: number; maxY: number } | null
  /** 标红限定在这些页框内：一条材料关联多页时，每张关联页都要标红 */
  highlightBounds?: DxfBounds[] | null
  /** 要标红的文字（如材料名称）：加载前把命中的文字改成红色 */
  highlight?: string
  /** 材料规格：用来区分同名不同规格的行（如 48芯 / 24芯） */
  highlightSpec?: string
  /** 主目标在图上找不到时的备用文字（如清单行名找不到就退回材料表名称） */
  highlightFallback?: string
  /** 顶部显示全部图页按钮：默认整图预览，点页码定位放大到那一张 */
  showPages?: boolean
  /** 与当前材料/清单项关联的图页序号：页码标橙、全图里给关联页描边 */
  relatedPages?: number[]
  /** 隐藏页码条（进入预览的入口把「全图」放进自己的工具行时用） */
  hidePageBar?: boolean
}>()

const emit = defineEmits<{ (event: 'page-change', page: number | null): void }>()

/** 超过该体积不再下发前端渲染，改用服务端 SVG 快照，避免浏览器卡死 */
const SNAPSHOT_LIMIT = 30 * 1024 * 1024

type FrameBox = {
  no: number
  sheet?: string | null
  minX: number
  minY: number
  maxX: number
  maxY: number
}

const frames = ref<FrameBox[]>([])
const activePage = ref<number | null>(null)

/**
 * 页码标签：一律用唯一的图框序号，图签张号可能重复（实测同一张图里
 * 「第8张」「第13张」各出现多次），用张号会让全图热区/页码条出现重复提示。
 */
function frameLabel(frame: FrameBox): string {
  return `第 ${frame.no} 张`
}

/**
 * 中文字体：dxf-viewer 内置字体仅含西文，不给字体则中文标注全部丢失。
 * 必须是 TrueType(glyf) 轮廓：dxf-viewer 用 opentype.js 读取字形 xMin/yMin/xMax/yMax，
 * CFF(OTF) 字体这些字段解析不到 → 文字包围盒为 NaN → 所有 TEXT 实体（图签表格文字）
 * 被整体丢弃，表现为表格只剩框线、单元格文字不显示。
 */
const FONT_URL = `${import.meta.env.BASE_URL}fonts/NotoSansSC-Regular.ttf`

const stage = ref<HTMLElement>()
const mode = ref<'dxf' | 'svg'>('dxf')
const loading = ref(true)
const message = ref('')
const fileSizeText = ref('')
const layers = ref<LayerInfo[]>([])
const hiddenLayers = ref<string[]>([])
const svgUrl = ref('')
const viewerReady = ref(false)

/**
 * 复制模式：WebGL 画布里的文字是像素，无法框选。切换后在原区域显示解析出的文字，
 * 按图页分组、恢复阅读顺序，用普通 DOM 排版供鼠标框选复制；退出后回到图纸。
 */
const copyMode = ref(false)
const textLoading = ref(false)
const textLabels = ref<DrawingTextLabel[]>([])
const copyPageFilter = ref('all')

type CopyGroup = { key: string; title: string; lines: string[] }

/** 按 y 聚成行、行内按 x 排序，尽量还原图纸上的阅读顺序 */
function buildCopyLines(labels: DrawingTextLabel[], tolerance: number): string[] {
  const rows: { y: number; items: DrawingTextLabel[] }[] = []
  for (const label of [...labels].sort((a, b) => b.y - a.y || a.x - b.x)) {
    const row = rows.find((item) => Math.abs(item.y - label.y) <= tolerance)
    if (row) {
      row.items.push(label)
    } else {
      rows.push({ y: label.y, items: [label] })
    }
  }
  return rows.map((row) => row.items
    .sort((a, b) => a.x - b.x)
    .map((item) => item.text.trim())
    .join('  '))
}

const allCopyGroups = computed<CopyGroup[]>(() => {
  if (!textLabels.value.length) {
    return []
  }
  const byFrame = new Map<number, DrawingTextLabel[]>()
  const orphans: DrawingTextLabel[] = []
  for (const label of textLabels.value) {
    if (label.frame == null) {
      orphans.push(label)
      continue
    }
    const list = byFrame.get(label.frame) ?? []
    list.push(label)
    byFrame.set(label.frame, list)
  }
  const groups: CopyGroup[] = []
  for (const [frameNo, list] of [...byFrame.entries()].sort((a, b) => a[0] - b[0])) {
    const frame = frames.value.find((item) => item.no === frameNo)
    const tolerance = frame && frame.maxY > frame.minY ? (frame.maxY - frame.minY) / 80 : 5
    const sheet = list.find((item) => item.sheet)?.sheet
    const title = list.find((item) => item.title)?.title
    groups.push({
      key: `frame-${frameNo}`,
      title: `第 ${frameNo} 张${sheet ? ` · ${sheet}` : ''}${title ? ` · ${title}` : ''}`,
      lines: buildCopyLines(list, tolerance),
    })
  }
  if (orphans.length) {
    groups.push({ key: 'orphans', title: '未归属图页的文字', lines: buildCopyLines(orphans, 5) })
  }
  return groups
})

/** 按选中的图页过滤，全部图页时不过滤 */
const visibleCopyGroups = computed<CopyGroup[]>(() => {
  if (copyPageFilter.value === 'all') {
    return allCopyGroups.value
  }
  return allCopyGroups.value.filter((group) => group.key === copyPageFilter.value)
})

/** 文字数据供复制模式和搜索共用，首次需要时加载一次 */
let textLabelsPromise: Promise<boolean> | null = null
async function ensureTextLabels(): Promise<boolean> {
  if (textLabels.value.length) {
    return true
  }
  if (!textLabelsPromise) {
    textLoading.value = true
    textLabelsPromise = (async () => {
      try {
        const data = await projectApi.parseResult(props.fileId)
        textLabels.value = ((data?.textLabels ?? []) as DrawingTextLabel[]).filter((item) => !!item.text?.trim())
        return textLabels.value.length > 0
      } catch {
        return false
      }
    })()
  }
  try {
    return await textLabelsPromise
  } finally {
    textLoading.value = false
  }
}

async function toggleCopyMode() {
  if (copyMode.value) {
    copyMode.value = false
    return
  }
  if (!await ensureTextLabels()) {
    ElMessage.warning('该图纸还没有解析出文字，请先完成解析')
    return
  }
  copyPageFilter.value = 'all'
  copyMode.value = true
  ElMessage.success('文字视图：框选需要的内容即可复制，退出后恢复图纸')
}

/** 文字搜索：输入关键词即列出命中文字及所在图页，点击定位到该处 */
const searchKeyword = ref('')
const searchOpen = ref(false)
const searchLoading = ref(false)
type SearchResult = {
  key: string
  label: DrawingTextLabel
  before: string
  match: string
  after: string
  page: string
}
type SearchGroup = { key: string; title: string; count: number; hits: SearchResult[] }
const searchGroups = ref<SearchGroup[]>([])
const searchMatchCount = ref(0)
const expandedGroup = ref('')
type SearchMarker = { key: string; x: number; y: number; text: string; showLabel: boolean }
const searchMarkers = ref<SearchMarker[]>([])
let activeSearchLabels: DrawingTextLabel[] = []
let searchSeq = 0

function pageTextOf(label: DrawingTextLabel): string {
  if (label.frame == null) {
    return '图框外'
  }
  return `第 ${label.frame} 张${label.sheet ? ` · ${label.sheet}` : ''}`
}

async function runSearch(keyword: string) {
  const kw = keyword.trim().toLowerCase()
  const seq = ++searchSeq
  searchMatchCount.value = 0
  searchMarkers.value = []
  activeSearchLabels = []
  expandedGroup.value = ''
  if (!kw) {
    searchGroups.value = []
    return
  }
  if (!textLabels.value.length) {
    searchLoading.value = true
    const ok = await ensureTextLabels()
    searchLoading.value = false
    if (!ok || seq !== searchSeq) {
      searchGroups.value = []
      return
    }
  }
  if (seq !== searchSeq) {
    return
  }
  const groups = new Map<string, SearchGroup>()
  let count = 0
  for (const label of textLabels.value) {
    const index = label.text.toLowerCase().indexOf(kw)
    if (index < 0) {
      continue
    }
    count += 1
    const key = String(label.frame ?? 'out')
    let group = groups.get(key)
    if (!group) {
      group = { key, title: pageTextOf(label), count: 0, hits: [] }
      groups.set(key, group)
    }
    group.count += 1
    // 单张图最多列 100 条，避免某个高频词把下拉撑爆
    if (group.hits.length < 100) {
      group.hits.push({
        key: `${key}-${label.x}-${label.y}-${count}`,
        label,
        before: label.text.slice(0, index),
        match: label.text.slice(index, index + keyword.trim().length),
        after: label.text.slice(index + keyword.trim().length),
        page: pageTextOf(label),
      })
    }
  }
  searchMatchCount.value = count
  searchGroups.value = [...groups.values()].sort((a, b) => {
    if (a.key === 'out') return 1
    if (b.key === 'out') return -1
    return Number(a.key) - Number(b.key)
  })
}

let searchBlurTimer: number | undefined
function onSearchFocus() {
  if (searchBlurTimer !== undefined) {
    window.clearTimeout(searchBlurTimer)
    searchBlurTimer = undefined
  }
  searchOpen.value = true
  if (searchKeyword.value) {
    void runSearch(searchKeyword.value)
  }
}
function onSearchBlur() {
  // 延迟关闭，让结果项的 mousedown 先处理；重新聚焦时上面的 focus 会取消该定时器
  if (searchBlurTimer !== undefined) {
    window.clearTimeout(searchBlurTimer)
  }
  searchBlurTimer = window.setTimeout(() => {
    searchOpen.value = false
    searchBlurTimer = undefined
  }, 150)
}
function onSearchEnter() {
  if (searchGroups.value.length) {
    focusSearchGroup(searchGroups.value[0])
  }
}

watch(searchKeyword, (value) => {
  void runSearch(value)
})

/** 画布上按文字坐标重算所有标记位置 */
function updateSearchMarkers() {
  if (!activeSearchLabels.length || !viewer) {
    searchMarkers.value = []
    return
  }
  // FitView 触发的 viewChanged 发生在 Render 之前，此时相机矩阵还是旧的；
  // 投影前强制更新，否则标记会整体偏移。
  viewer.GetCamera().updateMatrixWorld()
  const origin = viewer.GetOrigin()
  const ox = origin?.x ?? 0
  const oy = origin?.y ?? 0
  searchMarkers.value = activeSearchLabels.map((label) => {
    const point = viewer!.SceneToCanvasCoord(label.x - ox, label.y - oy)
    return { key: `${label.frame ?? 'out'}-${label.x}-${label.y}`, x: point.x, y: point.y,
      text: label.text, showLabel: false }
  })
}

/** 点图张：定位到该页、展开该页全部命中，并在画布上标出这些文字 */
function focusSearchGroup(group: SearchGroup) {
  const label = group.hits[0]?.label
  if (!label) {
    return
  }
  expandedGroup.value = expandedGroup.value === group.key ? '' : group.key
  if (!expandedGroup.value) {
    activeSearchLabels = []
    searchMarkers.value = []
    return
  }
  if (copyMode.value) {
    copyMode.value = false
  }
  const frame = label.frame != null ? frames.value.find((f) => f.no === label.frame) : null
  if (frame) {
    fitBox(frame, 0.03)
  } else {
    // 图框外文字：按这一组命中的坐标范围定位
    const xs = group.hits.map((h) => h.label.x)
    const ys = group.hits.map((h) => h.label.y)
    const bounds = viewer?.GetBounds()
    const spanX = Math.max(...xs) - Math.min(...xs)
    const spanY = Math.max(...ys) - Math.min(...ys)
    const span = Math.max(spanX, spanY, bounds ? (bounds.maxX - bounds.minX) / 100 : 1000)
    const cx = (Math.max(...xs) + Math.min(...xs)) / 2
    const cy = (Math.max(...ys) + Math.min(...ys)) / 2
    const ox = viewer?.GetOrigin()?.x ?? 0
    const oy = viewer?.GetOrigin()?.y ?? 0
    viewer?.FitView(cx - ox - span, cx - ox + span, cy - oy - span, cy - oy + span, 0.1)
    viewer?.Render()
  }
  activePage.value = label.frame
  emit('page-change', label.frame)
  updateHotspots()
  activeSearchLabels = group.hits.map((h) => h.label)
  searchMarkers.value = []
  nextTick(updateSearchMarkers)
}

/** 点具体命中：定位并放大到该文字，只标记这一处 */
function focusSearchResult(item: SearchResult) {
  searchOpen.value = false
  if (copyMode.value) {
    copyMode.value = false
  }
  const label = item.label
  const frame = label.frame != null ? frames.value.find((f) => f.no === label.frame) : null
  const bounds = viewer?.GetBounds()
  const span = frame
    ? Math.max((frame.maxX - frame.minX) / 12, (frame.maxY - frame.minY) / 12)
    : bounds
      ? Math.max(bounds.maxX - bounds.minX, bounds.maxY - bounds.minY) / 60
      : 1000
  const target = viewer
  if (target) {
    const origin = target.GetOrigin()
    const ox = origin?.x ?? 0
    const oy = origin?.y ?? 0
    target.FitView(label.x - ox - span, label.x - ox + span, label.y - oy - span, label.y - oy + span, 0.1)
    target.Render()
  }
  activePage.value = label.frame
  emit('page-change', label.frame)
  updateHotspots()
  activeSearchLabels = [label]
  searchMarkers.value = []
  nextTick(() => {
    updateSearchMarkers()
    if (searchMarkers.value[0]) {
      searchMarkers.value[0].showLabel = true
    }
  })
}

let viewer: DxfViewer | null = null
let dxfUrl = ''

function mb(size: number) {
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

/**
 * 等容器尺寸稳定：对话框展开动画期间容器一直在变，
 * 动画没结束就 FitView 会按小画布算缩放，图纸显示得过小。
 */
function waitForStableSize(el: HTMLElement, frames = 60): Promise<void> {
  return new Promise((resolve) => {
    let lastWidth = el.clientWidth
    let lastHeight = el.clientHeight
    let stable = 0
    const check = (left: number) => {
      const width = el.clientWidth
      const height = el.clientHeight
      stable = width > 0 && height > 0 && width === lastWidth && height === lastHeight
        ? stable + 1
        : 0
      lastWidth = width
      lastHeight = height
      if (stable >= 2 || left <= 0) {
        resolve()
        return
      }
      requestAnimationFrame(() => check(left - 1))
    }
    requestAnimationFrame(() => check(frames))
  })
}

/** 等容器有实际尺寸（对话框动画完成），最多等约 20 帧 */
function waitForSize(el: HTMLElement, frames = 20): Promise<void> {
  return new Promise((resolve) => {
    const check = (left: number) => {
      if (left <= 0 || (el.clientWidth > 0 && el.clientHeight > 0)) {
        resolve()
        return
      }
      requestAnimationFrame(() => check(left - 1))
    }
    check(frames)
  })
}

async function init() {
  loading.value = true
  try {
    const blob = await projectApi.drawing(props.fileId, 'dxf')
    // 标红直接改 DXF 里的文字颜色再交给渲染器：字体、字号、位置都不动
    const renderBlob = props.highlight
      ? new Blob(
          [
            highlightDxfText(
              await blob.arrayBuffer(),
              props.highlight,
              props.highlightSpec,
              props.highlightFallback,
              props.highlightBounds?.length ? props.highlightBounds : props.focus,
            ),
          ],
          { type: blob.type },
        )
      : blob
    fileSizeText.value = `DXF ${mb(renderBlob.size)}`
    if (renderBlob.size > SNAPSHOT_LIMIT) {
      await loadSnapshot(`图纸较大（${mb(renderBlob.size)}），已切换为服务端快照预览`)
      return
    }
    dxfUrl = URL.createObjectURL(renderBlob)
    await nextTick()
    const el = stage.value
    if (!el) {
      return
    }
    // dialog 打开动画期间容器可能仍是 0 尺寸，canvas 建在 0 尺寸上会渲染空白
    await waitForSize(el)
    viewer = new DxfViewer(el, {
      autoResize: true,
      antialias: true,
      // 白底必须在构造时给出：实体颜色在 Load 时按背景色做修正（AutoCAD 7 号色=白会反转为黑）
      clearColor: new THREE.Color('#ffffff'),
      colorCorrection: true,
    })
    await viewer.Load({ url: dxfUrl, fonts: [FONT_URL] })
    viewer.Subscribe('viewChanged', onViewChanged)
    viewer.Subscribe('resized', onViewChanged)
    viewerReady.value = true
    layers.value = [...viewer.GetLayers(false)]
    // 预览容器（对话框）展开到最终尺寸后再定位，否则缩放会按小画布计算
    await waitForStableSize(el)
    // 先取图页，页码条与关联页标识才有数据
    if (props.showPages) {
      await loadFrames()
    }
    if (props.focus) {
      focusPage()
      syncActivePage()
    } else {
      // 默认全图概览（按图页整体范围适配），顶部页码/点缩略图定位放大
      fitView()
      updateHotspots()
      emit('page-change', null)
    }
  } catch (e: any) {
    await loadSnapshot(`交互式渲染失败，已切换为快照预览${e?.message ? '：' + e.message : ''}`)
  } finally {
    loading.value = false
  }
}

async function loadSnapshot(reason: string) {
  message.value = props.focus ? `${reason}（快照模式只显示整图，无法定位到单页）` : reason
  mode.value = 'svg'
  destroyViewer()
  const blob = await projectApi.drawing(props.fileId, 'svg')
  if (svgUrl.value) {
    URL.revokeObjectURL(svgUrl.value)
  }
  svgUrl.value = URL.createObjectURL(blob)
  fileSizeText.value = `SVG ${mb(blob.size)}`
}

/**
 * 适应窗口：GetBounds() 返回图纸自身坐标，FitView 期望场景坐标（已减去 origin），
 * 直接传原始 bounds 会把相机推到图形之外，表现为画布空白。
 */
function fitView() {
  activePage.value = null
  // 全图按图页的整体范围适配：图纸里常有远处的零散文字/图元，
  // 直接取全部图元边界会把 29 张图挤成一个小点
  if (frames.value.length > 1) {
    const minX = Math.min(...frames.value.map((frame) => frame.minX))
    const minY = Math.min(...frames.value.map((frame) => frame.minY))
    const maxX = Math.max(...frames.value.map((frame) => frame.maxX))
    const maxY = Math.max(...frames.value.map((frame) => frame.maxY))
    fitBox({ no: 0, minX, minY, maxX, maxY }, 0.02)
    return
  }
  const bounds = viewer?.GetBounds()
  const origin = viewer?.GetOrigin()
  if (!bounds) {
    return
  }
  const ox = origin?.x ?? 0
  const oy = origin?.y ?? 0
  viewer?.FitView(bounds.minX - ox, bounds.maxX - ox, bounds.minY - oy, bounds.maxY - oy, 0.05)
  viewer?.Render()
}

/** 取图纸的图页列表，顶部页码条用 */
async function loadFrames() {
  try {
    const result = await projectApi.parseResult(props.fileId)
    frames.value = (result?.frames || []) as FrameBox[]
  } catch {
    frames.value = []
  }
}

// 全图模式下在画布上叠加每张图的透明热区，点击即定位放大到那一张。
// 不依赖查看器的点击事件，缩放/平移后按图页坐标实时换算屏幕位置。
type Hotspot = { no: number; label: string; left: number; top: number; width: number; height: number }
const hotspots = ref<Hotspot[]>([])

function onViewChanged() {
  updateHotspots()
  updateSearchMarkers()
}

function updateHotspots() {
  if (!viewer || !props.showPages || activePage.value !== null || frames.value.length < 2) {
    hotspots.value = []
    return
  }
  viewer.GetCamera().updateMatrixWorld()
  const origin = viewer.GetOrigin()
  const ox = origin?.x ?? 0
  const oy = origin?.y ?? 0
  const list: Hotspot[] = []
  for (const frame of frames.value) {
    const topLeft = viewer.SceneToCanvasCoord(frame.minX - ox, frame.maxY - oy)
    const bottomRight = viewer.SceneToCanvasCoord(frame.maxX - ox, frame.minY - oy)
    const left = Math.min(topLeft.x, bottomRight.x)
    const top = Math.min(topLeft.y, bottomRight.y)
    const width = Math.abs(bottomRight.x - topLeft.x)
    const height = Math.abs(bottomRight.y - topLeft.y)
    if (width > 2 && height > 2) {
      list.push({ no: frame.no, label: frameLabel(frame), left, top, width, height })
    }
  }
  hotspots.value = list
}

function isRelatedPage(no: number) {
  return (props.relatedPages || []).includes(no)
}

function focusPageByNo(no: number) {
  const frame = frames.value.find((item) => item.no === no)
  if (frame) {
    focusFrame(frame)
  }
}

/** 点页码：定位并放大到那一张，效果与图纸清单里的定位一致 */
function focusFrame(frame: FrameBox) {
  activePage.value = frame.no
  emit('page-change', frame.no)
  fitBox(frame, 0.03)
}

/** 回到全图概览（外部工具行的「全图」按钮调用） */
function showWhole() {
  fitView()
  updateHotspots()
  emit('page-change', null)
}

defineExpose({ showWhole })

function fitBox(box: FrameBox, padding: number) {
  const target = viewer
  if (!target) {
    return
  }
  const origin = target.GetOrigin()
  const ox = origin?.x ?? 0
  const oy = origin?.y ?? 0
  target.FitView(box.minX - ox, box.maxX - ox, box.minY - oy, box.maxY - oy, padding)
  target.Render()
}

/** 当前定位范围落在哪一图页，页码条同步高亮 */
function syncActivePage() {
  const box = props.focus
  if (!props.showPages || !box || !frames.value.length) {
    return
  }
  const centerX = (box.minX + box.maxX) / 2
  const centerY = (box.minY + box.maxY) / 2
  const hit = frames.value.find((frame) => centerX >= frame.minX && centerX <= frame.maxX
    && centerY >= frame.minY && centerY <= frame.maxY)
  activePage.value = hit ? hit.no : null
  emit('page-change', activePage.value)
  updateHotspots()
}

/**
 * 定位到指定图框（材料行点进来时看的那一页）。
 * FitView 要的是场景坐标（原始坐标减 origin），坐标不换算会偏到图纸外。
 */
function focusPage() {
  const box = props.focus
  const target = viewer
  if (!box || !target) {
    return
  }
  const origin = target.GetOrigin()
  const ox = origin?.x ?? 0
  const oy = origin?.y ?? 0
  // Load 返回时画布尺寸与 OrbitControls 还没落定：等两帧再定位，并主动重绘一次
  // （这个查看器是事件驱动渲染，只改相机不一定触发重绘）
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      target.FitView(box.minX - ox, box.maxX - ox, box.minY - oy, box.maxY - oy, 0.06)
      target.Render()
    })
  })
}

/** 预览顶部切换关联图页：同一文件只移动视角，不重新解析整张 DXF */
watch(
  () => props.focus,
  () => {
    if (viewerReady.value) {
      focusPage()
      syncActivePage()
    }
  },
)

function applyLayers() {
  for (const layer of layers.value) {
    viewer?.ShowLayer(layer.name, !hiddenLayers.value.includes(layer.name))
  }
}

/** 勾选=全部显示（清空隐藏列表）；取消=隐藏全部图层 */
const allVisible = computed(() => hiddenLayers.value.length === 0)

function toggleAllVisible(checked: boolean | string | number) {
  hiddenLayers.value = checked ? [] : layers.value.map((l) => l.name)
  applyLayers()
}

function destroyViewer() {
  viewerReady.value = false
  try {
    viewer?.Destroy()
  } catch {
    // 渲染器未初始化时 Destroy 可能抛错，忽略
  }
  viewer = null
}

function cleanup() {
  destroyViewer()
  if (dxfUrl) {
    URL.revokeObjectURL(dxfUrl)
  }
  if (svgUrl.value) {
    URL.revokeObjectURL(svgUrl.value)
  }
}

onMounted(init)
onBeforeUnmount(cleanup)
</script>

<style scoped>
.drawing-viewer {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.viewer-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
}
.layer-select {
  width: 240px;
}
.search-box {
  position: relative;
  width: 220px;
}
.search-dropdown {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  z-index: 20;
  max-height: 320px;
  overflow-y: auto;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.12);
}
.search-count,
.search-tip {
  padding: 6px 10px;
  font-size: 12px;
  color: #909399;
}
.search-group + .search-group {
  border-top: 1px solid #f5f7fa;
}
.search-group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  cursor: pointer;
  background: #fafbfc;
}
.search-group-head:hover {
  background: #f0f7ff;
}
.search-group-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.search-group-count {
  flex-shrink: 0;
  font-size: 12px;
  color: #909399;
}
.search-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  cursor: pointer;
  font-size: 12px;
  border-top: 1px solid #f5f7fa;
}
.search-item:hover {
  background: #f5f7fa;
}
.search-item-text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #303133;
}
.search-kw {
  color: #f56c6c;
}
.search-item-page {
  flex-shrink: 0;
  color: #909399;
}
.search-marker {
  position: absolute;
  z-index: 5;
  width: 14px;
  height: 14px;
  margin: -7px 0 0 -7px;
  border: 2px solid #f56c6c;
  border-radius: 50%;
  box-shadow: 0 0 0 3px rgba(245, 108, 108, 0.25);
  pointer-events: none;
}
.search-marker-label {
  position: absolute;
  left: 12px;
  top: -8px;
  max-width: 220px;
  padding: 2px 6px;
  font-size: 12px;
  line-height: 16px;
  color: #fff;
  background: #f56c6c;
  border-radius: 3px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.page-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  max-height: 76px;
  overflow-y: auto;
  padding: 6px 8px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  background: #fafbfc;
}
.page-bar :deep(.el-button + .el-button) {
  margin-left: 0;
}
.layer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 10px;
  border-bottom: 1px solid #f0f2f5;
}
.layer-count {
  color: #909399;
  font-size: 12px;
}
.meta {
  color: #909399;
  font-size: 12px;
  margin-left: auto;
}
/* 复制模式：用普通排版的可选文字视图替换画布，保证不重叠、可框选 */
.stage.hidden-in-copy {
  visibility: hidden;
}
.copy-view {
  position: absolute;
  inset: 0;
  z-index: 4;
  background: #fff;
  overflow: auto;
  padding: 0 18px 12px;
  cursor: text;
  user-select: text;
}
.copy-filter {
  position: sticky;
  top: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 0;
  margin-bottom: 4px;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
  user-select: none;
}
.copy-filter-label {
  font-size: 13px;
  color: #606266;
  flex-shrink: 0;
}
.copy-filter-select {
  width: 240px;
}
.copy-filter-hint {
  font-size: 12px;
  color: #909399;
}
.copy-group + .copy-group {
  margin-top: 18px;
  padding-top: 12px;
  border-top: 1px solid #ebeef5;
}
.copy-group-title {
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}
.copy-line {
  margin: 0;
  min-height: 20px;
  font-size: 13px;
  line-height: 20px;
  color: #111;
  white-space: pre-wrap;
  word-break: break-all;
}
.stage-wrap.copy-mode .page-hotspot {
  pointer-events: none;
}
.stage-wrap.pickable {
  cursor: zoom-in;
}
.page-hotspot {
  position: absolute;
  z-index: 2;
  cursor: zoom-in;
  border-radius: 2px;
  transition: background-color 0.15s, box-shadow 0.15s;
}
.page-hotspot:hover {
  background: rgba(64, 158, 255, 0.08);
  box-shadow: 0 0 0 2px #409eff;
}
/* 关联页：全图里用橙色描边标出来，点进去就是那张 */
.page-hotspot.related {
  background: rgba(230, 162, 60, 0.08);
  box-shadow: inset 0 0 0 2px #e6a23c;
}
.stage-wrap {
  position: relative;
  height: 60vh;
  min-height: 420px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  overflow: hidden;
  background: #fff;
}
.stage {
  width: 100%;
  height: 100%;
  background: #fff;
}
.snapshot {
  width: 100%;
  height: 100%;
  overflow: auto;
}
.snapshot img {
  width: 100%;
  display: block;
}
.msg {
  margin-top: 4px;
}
</style>
