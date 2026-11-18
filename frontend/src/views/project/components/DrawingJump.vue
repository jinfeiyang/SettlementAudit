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
      :title="`图纸预览 - ${file?.fileName || ''}`"
      width="80%"
      top="5vh"
      destroy-on-close
      append-to-body
    >
      <DrawingViewer
        v-if="file"
        :key="`${file.id}-${focus?.minX ?? ''}-${focus?.minY ?? ''}`"
        :file-id="file.id"
        :file-name="file.fileName"
        :focus="focus"
      />
    </el-dialog>
  </span>
</template>

<script setup lang="ts">
import { ref } from 'vue'
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
  }>(),
  { sources: () => [], fallback: null, showTitle: false },
)

const visible = ref(false)
const file = ref<{ id: number; fileName: string } | null>(null)
const focus = ref<Bounds | null>(null)

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
  file.value = { id: source.fileId, fileName: source.fileName }
  focus.value = focusBoundsOf(source)
  visible.value = true
}

function openFile() {
  if (!props.fallback) {
    return
  }
  file.value = { id: props.fallback.fileId, fileName: props.fallback.fileName }
  focus.value = null
  visible.value = true
}
</script>

<style scoped>
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
