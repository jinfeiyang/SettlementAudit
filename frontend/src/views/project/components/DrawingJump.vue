<template>
  <span class="drawing-jump">
    <template v-if="sources.length">
      <el-button link type="primary" size="small" @click="open(sources[0])">
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
          <el-button link type="primary" size="small" @click="open(src)">
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
      <div v-if="current || material || relatedSources.length > 1" class="preview-bar">
        <div v-if="current || material" class="preview-head">
          <div v-if="current" class="head-title">
            {{ sourceLabel(current) }}<template v-if="current.title"> · {{ current.title }}</template>
          </div>
          <!-- 对应的具体材料名称标红：如「通信光缆」 -->
          <div v-if="material" class="current-material">
            材料：{{ material }}<span v-if="spec" class="material-spec"> {{ spec }}</span>
          </div>
        </div>
        <div v-if="relatedSources.length > 1" class="related-line">
          <span class="related-label">关联图纸（共 {{ relatedSources.length }} 页）</span>
          <el-button
            v-for="src in relatedSources"
            :key="`${src.fileId}-${src.frame}`"
            size="small"
            :type="isCurrent(src) ? 'danger' : 'default'"
            :plain="isCurrent(src)"
            :title="src.title || src.fileName"
            @click="open(src)"
          >
            {{ sourceLabel(src) }}
          </el-button>
        </div>
      </div>
      <DrawingViewer
        v-if="file"
        :key="file.id"
        :file-id="file.id"
        :file-name="file.fileName"
        :focus="focus"
        :highlight="highlight || material"
        :highlight-spec="spec"
        :highlight-fallback="highlight ? material : ''"
      />
    </el-dialog>
  </span>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import DrawingViewer from './DrawingViewer.vue'
import { focusBoundsOf, type Bounds, type DrawingSource } from './drawingCompare'

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
  }>(),
  { sources: () => [], fallback: null, showTitle: false, material: '', spec: '', highlight: '' },
)

const visible = ref(false)
const file = ref<{ id: number; fileName: string } | null>(null)
const focus = ref<Bounds | null>(null)
/** 当前定位的关联页，用于预览顶部高亮与标题 */
const current = ref<DrawingSource | null>(null)

/** 这条材料/清单项出现的所有图页，预览顶部可直接切换定位 */
const relatedSources = computed(() => props.sources)

const currentLabel = computed(() =>
  current.value ? sourceLabel(current.value) : file.value && !props.sources.length ? '整张图' : '',
)

function isCurrent(source: DrawingSource) {
  return current.value?.fileId === source.fileId && current.value?.frame === source.frame
}

/** 张号优先（图签上印的才对得上纸），没有就用图框序号 */
function sourceLabel(source: DrawingSource) {
  if (source.sheet) {
    return `第 ${source.sheet} 张`
  }
  if (source.frame !== null) {
    return `图框 ${source.frame}`
  }
  return '材料表'
}

function open(source: DrawingSource) {
  // 同一文件内切换图页不重建查看器，由查看器监听 focus 直接定位，避免重复解析 DXF
  if (file.value?.id !== source.fileId) {
    file.value = { id: source.fileId, fileName: source.fileName }
  }
  current.value = source
  focus.value = focusBoundsOf(source)
  visible.value = true
}

function openFile() {
  if (!props.fallback) {
    return
  }
  file.value = { id: props.fallback.fileId, fileName: props.fallback.fileName }
  current.value = null
  focus.value = null
  visible.value = true
}
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
