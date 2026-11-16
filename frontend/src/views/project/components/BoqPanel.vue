<template>
  <div>
    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="搜索清单名称 / 编号 / 特征"
        clearable
        style="width: 260px"
      />
      <el-button @click="load">刷新</el-button>
      <el-button @click="boqApi.template(projectId)">下载清单模板</el-button>
      <el-button @click="$emit('go', 'files')">上传/解析清单</el-button>
      <el-button type="danger" plain @click="clearAll">清空清单</el-button>
      <span v-if="highlightRow" class="hint">
        已定位清单第 {{ highlightRow }} 行
        <el-button link type="primary" @click="clearHighlight">取消高亮</el-button>
      </span>
    </div>

    <el-table
      v-loading="loading"
      :data="pageRows"
      :row-class-name="rowClass"
      empty-text="暂无清单数据，请先在「资料与解析」上传清单 Excel"
      max-height="560"
    >
      <el-table-column prop="rowNo" label="行号" width="70" align="center" />
      <el-table-column prop="itemCode" label="清单编号" width="130" />
      <el-table-column prop="itemName" label="清单名称" min-width="200" show-overflow-tooltip />
      <el-table-column prop="featureDesc" label="项目特征" min-width="180" show-overflow-tooltip />
      <el-table-column prop="unit" label="单位" width="80" />
      <el-table-column label="工程量" width="100" align="right">
        <template #default="{ row }">{{ number(row.quantity) }}</template>
      </el-table-column>
      <el-table-column label="综合单价" width="110" align="right">
        <template #default="{ row }">{{ money(row.unitPrice) }}</template>
      </el-table-column>
      <el-table-column label="合价" width="120" align="right">
        <template #default="{ row }">{{ money(row.totalPrice) }}</template>
      </el-table-column>
      <el-table-column label="定额匹配" width="240">
        <template #default="{ row }">
          <template v-if="row.matchQuotaName">
            <el-tag :type="matchTag(row.matchLevel)" size="small">{{ matchLabel(row.matchLevel) }}</el-tag>
            <div class="quota">
              {{ row.matchQuotaCode }} {{ row.matchQuotaName }}
              <span v-if="row.matchScore" class="score">（{{ Number(row.matchScore).toFixed(2) }}）</span>
            </div>
          </template>
          <el-tag v-else type="danger" size="small" effect="plain">未匹配</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      :current-page="page"
      :page-size="size"
      :total="filtered.length"
      layout="total, sizes, prev, pager, next"
      :page-sizes="[20, 50, 100]"
      @current-change="(p: number) => (page = p)"
      @size-change="onSizeChange"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { boqApi, type BoqItemVO } from '@/api'
import { money } from '@/utils/format'

const props = defineProps<{ projectId: number; highlightRow?: number }>()
const emit = defineEmits<{ (e: 'changed'): void; (e: 'go', tab: string): void }>()

const all = ref<BoqItemVO[]>([])
const loading = ref(false)
const keyword = ref('')
const page = ref(1)
const size = ref(50)

const filtered = computed(() => {
  const kw = keyword.value.trim()
  if (!kw) {
    return all.value
  }
  return all.value.filter(
    (item) =>
      item.itemName?.includes(kw) ||
      item.itemCode?.includes(kw) ||
      item.featureDesc?.includes(kw),
  )
})

const pageRows = computed(() => {
  const start = (page.value - 1) * size.value
  return filtered.value.slice(start, start + size.value)
})

async function load() {
  loading.value = true
  try {
    all.value = await boqApi.all(props.projectId)
    focusHighlight()
  } finally {
    loading.value = false
  }
}

/** 回溯定位（D2）：定位到疑点对应的清单行并高亮 */
function focusHighlight() {
  const rowNo = props.highlightRow
  if (!rowNo) {
    return
  }
  const index = all.value.findIndex((item) => item.rowNo === rowNo)
  if (index >= 0) {
    page.value = Math.floor(index / size.value) + 1
  }
}

function rowClass({ row }: { row: BoqItemVO }) {
  return props.highlightRow && row.rowNo === props.highlightRow ? 'highlight-row' : ''
}

function clearHighlight() {
  page.value = 1
}

function matchTag(level?: number): 'success' | 'warning' | 'danger' {
  if (level === 1) {
    return 'success'
  }
  return level === 2 ? 'warning' : 'danger'
}

function matchLabel(level?: number) {
  if (level === 1) {
    return '精确匹配'
  }
  return level === 2 ? '语义召回' : '未命中'
}

function number(value?: number) {
  return value === undefined || value === null ? '-' : Number(value)
}

function onSizeChange(next: number) {
  size.value = next
  page.value = 1
}

async function clearAll() {
  await ElMessageBox.confirm('清空后需重新导入清单，已有疑点将一并清除，确认继续？', '清空确认', {
    type: 'warning',
  })
  await boqApi.clear(props.projectId)
  ElMessage.success('已清空')
  await load()
  emit('changed')
}

watch(() => props.highlightRow, focusHighlight)
watch(keyword, () => {
  page.value = 1
})

onMounted(load)
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
  color: #e6a23c;
  font-size: 12px;
}
.quota {
  color: #606266;
  font-size: 12px;
  line-height: 1.4;
}
.score {
  color: #909399;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
:deep(.highlight-row) {
  background-color: #fdf6ec !important;
}
</style>
