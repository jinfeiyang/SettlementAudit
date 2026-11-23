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
      :class="{ pickable: showPages && activePage === null && frames.length > 1 }"
    >
      <div v-show="mode === 'dxf'" ref="stage" class="stage" />
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
import { projectApi } from '@/api'
import { highlightDxfText } from '@/utils/dxfHighlight'

const props = defineProps<{
  fileId: number
  fileName: string
  /** 只显示某一页：传该图框的坐标范围，加载完直接定位过去 */
  focus?: { minX: number; minY: number; maxX: number; maxY: number } | null
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
    viewer.Subscribe('viewChanged', updateHotspots)
    viewer.Subscribe('resized', updateHotspots)
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

function updateHotspots() {
  if (!viewer || !props.showPages || activePage.value !== null || frames.value.length < 2) {
    hotspots.value = []
    return
  }
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
