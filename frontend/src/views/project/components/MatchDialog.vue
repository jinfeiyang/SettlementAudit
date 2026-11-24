<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="620px"
    align-center
    @update:model-value="emit('update:modelValue', $event)"
    @open="onOpen"
  >
    <el-input v-model="keyword" placeholder="搜索名称 / 规格" clearable />
    <el-scrollbar max-height="340px" class="options">
      <div
        v-for="opt in filtered"
        :key="opt.value"
        :class="['option', { active: picked === opt.value }]"
        @click="picked = opt.value"
      >
        <div class="option-main">
          {{ opt.label }}
          <el-tag v-if="opt.value === current" size="small" type="success" effect="plain">当前</el-tag>
        </div>
        <!-- 规格 / 型号 / 项目特征：同一名称下就差这一项，匹配前必须看得到 -->
        <div v-if="opt.meta" class="option-meta">{{ opt.meta }}</div>
        <div v-if="opt.desc" class="option-desc">{{ opt.desc }}</div>
      </div>
      <div v-if="!filtered.length" class="empty">没有可选项</div>
    </el-scrollbar>
    <template #footer>
      <el-button v-if="canUnmatch" type="danger" plain @click="emit('unmatch')">取消匹配</el-button>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      <el-button type="primary" :disabled="!picked" @click="picked && emit('confirm', picked)">
        确定匹配
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

interface MatchOption {
  value: string
  label: string
  /** 规格 / 型号 / 项目特征，单独一行展示，长文本自动换行 */
  meta?: string
  desc?: string
}

const props = defineProps<{
  modelValue: boolean
  title: string
  options: MatchOption[]
  /** 当前匹配项的 value，用于标记「当前」 */
  current?: string
  /** 是否展示「取消匹配」（当前已有匹配时） */
  canUnmatch?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'confirm', value: string): void
  (e: 'unmatch'): void
}>()

const keyword = ref('')
const picked = ref('')

const filtered = computed(() => {
  const kw = keyword.value.trim()
  if (!kw) {
    return props.options
  }
  return props.options.filter(
    (opt) =>
      opt.label.includes(kw) || (opt.meta ?? '').includes(kw) || (opt.desc ?? '').includes(kw),
  )
})

function onOpen() {
  keyword.value = ''
  // 默认选中当前匹配项，方便看出这条清单/材料现在对应什么
  picked.value = props.current ?? ''
}
</script>

<style scoped>
.options {
  margin-top: 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
}

.option {
  padding: 8px 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
}

.option:last-child {
  border-bottom: none;
}

.option:hover {
  background: var(--el-fill-color-light);
}

.option.active {
  background: var(--el-color-primary-light-9);
}

.option-main {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.option-meta {
  margin-top: 2px;
  color: var(--el-text-color-regular);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-line;
  word-break: break-all;
}

.option-desc {
  margin-top: 2px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.empty {
  padding: 24px;
  text-align: center;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
