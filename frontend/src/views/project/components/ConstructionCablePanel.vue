<template>
  <div class="cable-panel">
    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="搜索段落名称"
        clearable
        style="width: 280px"
      />
      <el-select v-model="coreFilter" placeholder="全部芯数" clearable style="width: 120px">
        <el-option v-for="c in coreOptions" :key="c" :label="`${c} 芯`" :value="c" />
      </el-select>
      <el-select v-model="dupFilter" placeholder="全部重复状态" clearable style="width: 160px">
        <el-option label="起止反转" value="REVERSED" />
        <el-option label="名称近似" value="SIMILAR" />
        <el-option label="正常" value="NONE" />
      </el-select>
      <el-button type="primary" @click="onQuery">查询</el-button>
      <el-button @click="resetQuery">重置</el-button>
      <el-button @click="reload">刷新</el-button>
      <span class="note">
        共 {{ segments.length }} 段<template v-if="dupCount">，疑似重复 {{ dupCount }} 段（起止反转 / 名称近似）</template>
      </span>
    </div>

    <div class="amount-summary">
      <span
        v-for="stat in coreStats"
        :key="stat.core"
        class="core-link"
        :class="{ disabled: !boqByCore.get(stat.core) }"
        :title="boqByCore.get(stat.core) ? '点击跳转到清单对应的光缆行' : '清单无对应光缆行'"
        @click="goToCoreBoq(stat.core)"
      >
        {{ stat.core }} 芯总长：<b>{{ qty(stat.length) }}</b> 米
      </span>
      <span>表七施工费：<b>{{ money(totalAmount) }}</b> 元</span>
    </div>

    <el-table
      ref="tableRef"
      v-loading="loading"
      :data="pageRows"
      border
      :height="tableHeight"
      empty-text="暂无施工光缆逐段数据，请在「资料与解析」重新导入结算清单 Excel"
      :row-class-name="rowClass"
    >
      <el-table-column label="段落名称" min-width="320" class-name="wrap-cell">
        <template #default="{ row }">
          <span class="segment-name">
            <span>{{ row.segmentName }}</span>
            <el-tag v-if="row.dupGroup" :type="row.dupType === 'REVERSED' ? 'danger' : 'warning'" size="small" effect="plain">
              {{ row.dupType === 'REVERSED' ? '起止反转重复' : '名称近似重复' }}
            </el-tag>
            <span v-else class="dup-normal">正常</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="coreCount" label="芯数" width="80" align="right" />
      <el-table-column label="长度(米)" width="130" align="right" class-name="num-cell">
        <template #default="{ row }">{{ qty(row.length) }}</template>
      </el-table-column>
      <el-table-column label="金额(元)" width="130" align="right" class-name="num-cell">
        <template #default="{ row }">{{ money(row.amount) }}</template>
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
import { boqApi, type BoqItemVO, type FixedSegmentVO } from '@/api'
import { money } from '@/utils/format'
import { useTableHeight } from '@/utils/useTableHeight'

/** refreshKey 由父组件在清单重新导入/核查完成后自增，Tab 不销毁也能重新取数 */
const props = defineProps<{ projectId: number; refreshKey?: number }>()
const emit = defineEmits<{
  (e: 'count', count: number): void
  (e: 'locate-boq', rowNo: number): void
  (e: 'go-boq'): void
}>()

const tableRef = ref<{ $el: HTMLElement }>()
const tableHeight = useTableHeight(tableRef)
const segments = ref<FixedSegmentVO[]>([])
const boqItems = ref<BoqItemVO[]>([])
const loading = ref(false)
const keyword = ref('')
const coreFilter = ref<number | undefined>(undefined)
const dupFilter = ref('')
const page = ref(1)
const size = ref(50)

const dupCount = computed(() => segments.value.filter((s) => s.dupGroup > 0).length)

/** 表八光缆行在导入时被改写为「固定单价法施工费（N芯，X段汇总）」，按芯数建立关联 */
const boqByCore = computed(() => {
  const map = new Map<number, BoqItemVO>()
  for (const item of boqItems.value) {
    const m = /(?:固定单价法施工费)[^0-9]*(\d+)\s*芯/.exec(item.featureDesc ?? '')
    if (m) {
      const core = Number(m[1])
      if (!map.has(core)) {
        map.set(core, item)
      }
    }
  }
  return map
})

const coreOptions = computed(() =>
  [...new Set(segments.value.map((s) => s.coreCount).filter((c): c is number => c != null))]
    .sort((a, b) => a - b))

/** 各芯数总长度：取自表七逐段明细，按芯数分组求和 */
const coreStats = computed(() => {
  const map = new Map<number, number>()
  for (const s of segments.value) {
    if (s.coreCount == null) {
      continue
    }
    map.set(s.coreCount, (map.get(s.coreCount) ?? 0) + Number(s.length ?? 0))
  }
  return [...map.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([core, length]) => ({ core, length }))
})

const totalAmount = computed(() =>
  segments.value.reduce((sum, s) => sum + Number(s.amount ?? 0), 0))

const filtered = computed(() => {
  const kw = keyword.value.trim()
  return segments.value.filter((s) => {
    if (kw && !(s.segmentName ?? '').includes(kw)) {
      return false
    }
    if (coreFilter.value != null && s.coreCount !== coreFilter.value) {
      return false
    }
    if (dupFilter.value === 'NONE' && s.dupGroup > 0) {
      return false
    }
    if (dupFilter.value && dupFilter.value !== 'NONE' && s.dupType !== dupFilter.value) {
      return false
    }
    return true
  })
})

const pageRows = computed(() => {
  const start = (page.value - 1) * size.value
  return filtered.value.slice(start, start + size.value)
})

function rowClass({ row }: { row: FixedSegmentVO }) {
  return row.dupGroup > 0 ? 'dup-row' : ''
}

function qty(value?: number | null) {
  if (value === undefined || value === null || Number.isNaN(Number(value))) {
    return '-'
  }
  return Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 4 })
}

function onQuery() {
  page.value = 1
}

function resetQuery() {
  keyword.value = ''
  coreFilter.value = undefined
  dupFilter.value = ''
  page.value = 1
}

function onSizeChange(next: number) {
  size.value = next
  page.value = 1
}

/** 点击段落名跳转到清单对应芯数的光缆行 */
function goToCoreBoq(core?: number) {
  const target = core != null ? boqByCore.value.get(core) : undefined
  if (target?.rowNo != null) {
    emit('locate-boq', target.rowNo)
  } else {
    emit('go-boq')
  }
}

async function reload() {
  loading.value = true
  try {
    const [segmentRows, boqRows] = await Promise.all([
      boqApi.fixedSegments(props.projectId),
      boqApi.all(props.projectId),
    ])
    segments.value = segmentRows
    boqItems.value = boqRows
    emit('count', segments.value.length)
  } finally {
    loading.value = false
  }
}

watch(() => props.refreshKey, reload)
watch([keyword, coreFilter, dupFilter], () => {
  page.value = 1
})
onMounted(reload)
</script>

<style scoped>
.cable-panel {
  min-width: 0;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.note {
  font-size: 12px;
  color: #909399;
}
.amount-summary {
  display: flex;
  align-items: center;
  gap: 18px;
  flex-wrap: wrap;
  padding: 10px 14px;
  margin-bottom: 12px;
  background: #f5f7fa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  font-size: 13px;
  color: #606266;
}
.amount-summary b {
  font-variant-numeric: tabular-nums;
  color: #303133;
  font-size: 14px;
}
.core-link {
  color: #409eff;
  cursor: pointer;
}
.core-link:hover {
  text-decoration: underline;
}
.core-link.disabled {
  color: #606266;
  cursor: default;
}
.core-link.disabled:hover {
  text-decoration: none;
}
.segment-name {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.dup-normal {
  color: #c0c4cc;
  font-size: 11px;
}
.muted {
  color: #a8abb2;
  font-size: 12px;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
:deep(.dup-row) {
  background-color: #fdf6ec !important;
}
:deep(.num-cell .cell) {
  font-variant-numeric: tabular-nums;
}
:deep(.el-table .cell) {
  white-space: normal;
  word-break: break-all;
  line-height: 1.45;
}
</style>
