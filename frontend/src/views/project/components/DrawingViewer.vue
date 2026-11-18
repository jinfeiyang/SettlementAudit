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

    <div v-loading="loading" element-loading-text="图纸转换与渲染中，首次打开需数秒…" class="stage-wrap">
      <div v-show="mode === 'dxf'" ref="stage" class="stage" />
      <div v-if="mode === 'svg'" class="snapshot">
        <img v-if="svgUrl" :src="svgUrl" alt="图纸快照" />
      </div>
    </div>

    <el-alert v-if="message" :title="message" type="warning" :closable="false" show-icon class="msg" />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import * as THREE from 'three'
import { DxfViewer, type LayerInfo } from 'dxf-viewer'
import { projectApi } from '@/api'

const props = defineProps<{ fileId: number; fileName: string }>()

/** 超过该体积不再下发前端渲染，改用服务端 SVG 快照，避免浏览器卡死 */
const SNAPSHOT_LIMIT = 30 * 1024 * 1024

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
    fileSizeText.value = `DXF ${mb(blob.size)}`
    if (blob.size > SNAPSHOT_LIMIT) {
      await loadSnapshot(`图纸较大（${mb(blob.size)}），已切换为服务端快照预览`)
      return
    }
    dxfUrl = URL.createObjectURL(blob)
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
    viewerReady.value = true
    layers.value = [...viewer.GetLayers(false)]
  } catch (e: any) {
    await loadSnapshot(`交互式渲染失败，已切换为快照预览${e?.message ? '：' + e.message : ''}`)
  } finally {
    loading.value = false
  }
}

async function loadSnapshot(reason: string) {
  message.value = reason
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
  const bounds = viewer?.GetBounds()
  const origin = viewer?.GetOrigin()
  if (!bounds) {
    return
  }
  const ox = origin?.x ?? 0
  const oy = origin?.y ?? 0
  viewer?.FitView(bounds.minX - ox, bounds.maxX - ox, bounds.minY - oy, bounds.maxY - oy, 0.05)
}

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
.stage-wrap {
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
