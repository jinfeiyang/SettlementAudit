<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { manualConclusionApi } from '@/api'

const props = defineProps<{ projectId: number; refreshKey: number }>()
const emit = defineEmits<{ changed: [] }>()

type Row = Record<string, any>
const rows = ref<Row[]>([])
const loading = ref(false)
const typeFilter = ref('')
const statusFilter = ref('ACTIVE')

const typeLabel: Record<string, string> = {
  DRAWING_EDIT: '图纸材料编辑',
  MERGE: '合并组',
  MATCH: '清单匹配',
  QUANTITY_FIX: '数量分项修正',
}

const filtered = computed(() => rows.value.filter((row) =>
  (!typeFilter.value || row.type === typeFilter.value)
  && (!statusFilter.value || row.status === statusFilter.value),
))

async function load() {
  loading.value = true
  try {
    rows.value = await manualConclusionApi.list(props.projectId)
  } finally {
    loading.value = false
  }
}

async function revoke(row: Row) {
  try {
    await ElMessageBox.confirm(
      `撤销后「${row.summary || '该结论'}」将恢复原始解析或自动匹配，确定继续？`,
      '撤销人工结论',
      { type: 'warning' },
    )
    await manualConclusionApi.revoke(props.projectId, row.id)
    ElMessage.success('已撤销')
    await load()
    emit('changed')
  } catch (error: any) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error?.message || '撤销失败')
    }
  }
}

onMounted(load)
watch(() => props.refreshKey, load)
</script>

<template>
  <div>
    <div class="toolbar">
      <span class="hint">统一查看该项目的人工编辑、合并、匹配和数量修正；撤销后恢复原始解析或自动匹配。</span>
      <el-select v-model="typeFilter" placeholder="全部类型" clearable style="width: 150px">
        <el-option v-for="(label, value) in typeLabel" :key="value" :label="label" :value="value" />
      </el-select>
      <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 120px">
        <el-option label="生效中" value="ACTIVE" />
        <el-option label="已撤销" value="REVOKED" />
      </el-select>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-table :data="filtered" v-loading="loading" size="small" border stripe>
      <el-table-column label="类型" width="130">
        <template #default="{ row }">{{ typeLabel[row.type] || row.type }}</template>
      </el-table-column>
      <el-table-column prop="sourceDesc" label="来源" min-width="220" show-overflow-tooltip />
      <el-table-column prop="summary" label="结论摘要" min-width="260" show-overflow-tooltip />
      <el-table-column prop="beforeValue" label="变更前" min-width="160" show-overflow-tooltip />
      <el-table-column prop="afterValue" label="变更后" min-width="160" show-overflow-tooltip />
      <el-table-column label="操作人" width="110">
        <template #default="{ row }">{{ row.username || '未记录' }}</template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="170" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
            {{ row.status === 'ACTIVE' ? '生效中' : '已撤销' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'ACTIVE'" link type="danger" @click="revoke(row)">撤销</el-button>
          <span v-else>-</span>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!filtered.length && !loading" description="暂无人工结论" :image-size="60" />
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.hint {
  flex: 1;
  font-size: 12px;
  color: #909399;
}
</style>
