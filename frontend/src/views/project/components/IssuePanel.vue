<template>
  <div>
    <div class="toolbar">
      <el-select v-model="query.level" placeholder="全部级别" clearable style="width: 130px">
        <el-option label="高（确定偏差）" :value="3" />
        <el-option label="中（需复核）" :value="2" />
        <el-option label="低（提示性）" :value="1" />
      </el-select>
      <el-select v-model="query.issueType" placeholder="全部类型" clearable style="width: 140px">
        <el-option v-for="(label, value) in typeOptions" :key="value" :label="label" :value="value" />
      </el-select>
      <el-select v-model="query.reviewStatus" placeholder="全部状态" clearable style="width: 130px">
        <el-option label="待复核" value="PENDING" />
        <el-option label="已确认" value="CONFIRMED" />
        <el-option label="已驳回" value="REJECTED" />
      </el-select>
      <el-input
        v-model="query.keyword"
        placeholder="搜索疑点标题 / 明细"
        clearable
        style="width: 220px"
        @keyup.enter="reload"
      />
      <el-button type="primary" @click="reload">查询</el-button>
      <el-button @click="exportExcel">导出疑点清单</el-button>
      <el-button v-if="selection.length" @click="batchReview('CONFIRM')">
        批量确认（{{ selection.length }}）
      </el-button>
      <el-button v-if="selection.length" @click="batchReview('REJECT')">批量驳回</el-button>
    </div>

    <el-table
      v-loading="loading"
      :data="rows"
      empty-text="暂无疑点，请先执行自动核查"
      max-height="560"
      @selection-change="(val: any[]) => (selection = val)"
    >
      <el-table-column type="selection" width="44" />
      <el-table-column label="级别" width="80">
        <template #default="{ row }">
          <el-tag :type="levelTag(row.level)" size="small" effect="dark">{{ row.levelName }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="issueTypeName" label="类型" width="110" />
      <el-table-column prop="title" label="疑点标题" min-width="300" class-name="wrap-cell" />
      <el-table-column label="清单行号" width="100" align="center">
        <template #default="{ row }">
          <el-button v-if="row.rowNo" link type="primary" @click="locate(row)">{{ row.rowNo }}</el-button>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="itemName" label="关联清单项" min-width="280" class-name="wrap-cell" />
      <el-table-column label="复核状态" width="110">
        <template #default="{ row }">
          <el-tag :type="reviewTag(row.reviewStatus)" size="small" effect="plain">
            {{ row.reviewStatusName }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button link type="primary" @click="locate(row)">定位</el-button>
          <el-button link type="success" @click="review(row, 'CONFIRM')">确认</el-button>
          <el-button link type="danger" @click="review(row, 'REJECT')">驳回</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      :current-page="query.page"
      :page-size="query.size"
      :total="total"
      layout="total, prev, pager, next, jumper"
      @current-change="onPageChange"
    />

    <el-drawer v-model="detailVisible" :title="current?.title || '疑点详情'" size="46%">
      <template v-if="current">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="级别">
            <el-tag :type="levelTag(current.level)" size="small" effect="dark">{{ current.levelName }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="类型">{{ current.issueTypeName }}</el-descriptions-item>
          <el-descriptions-item label="命中规则">{{ current.ruleCode || '-' }}</el-descriptions-item>
          <el-descriptions-item label="复核状态">
            <el-tag :type="reviewTag(current.reviewStatus)" size="small">{{ current.reviewStatusName }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="清单行号">{{ current.rowNo ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="清单编号">{{ current.itemCode || '-' }}</el-descriptions-item>
          <el-descriptions-item label="清单名称" :span="2">{{ current.itemName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="工程量">
            {{ current.quantity ?? '-' }} {{ current.unit || '' }}
          </el-descriptions-item>
          <el-descriptions-item label="合价（元）">{{ money(current.totalPrice) }}</el-descriptions-item>
        </el-descriptions>

        <h4>确定性判定明细</h4>
        <p class="detail">{{ current.detail || '-' }}</p>

        <h4>AI 归因建议（仅建议，不作为结论）</h4>
        <el-alert
          v-if="current.aiNote"
          type="info"
          :closable="false"
          :title="`模型：${current.aiModel || '未知'}`"
          :description="current.aiNote"
        />
        <el-empty v-else description="未启用 AI 归因（可在后端配置 LLM 后开启）" :image-size="60" />

        <h4>复核意见</h4>
        <el-input v-model="comment" type="textarea" :rows="3" placeholder="填写复核意见（选填）" />
        <div class="drawer-actions">
          <el-button type="success" @click="review(current, 'CONFIRM')">确认疑点</el-button>
          <el-button type="danger" @click="review(current, 'REJECT')">驳回疑点</el-button>
          <el-button @click="locate(current)">回溯定位清单行</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { issueApi, type IssueVO } from '@/api'
import { levelTag, money } from '@/utils/format'

/** refreshKey 由父组件在核查完成后自增，用来触发列表重载（Tab 已挂载时不会重新 onMounted） */
const props = defineProps<{ projectId: number; refreshKey?: number }>()
const emit = defineEmits<{ (e: 'locate', rowNo?: number): void; (e: 'changed'): void }>()

const typeOptions: Record<string, string> = {
  QUOTA_MISMATCH: '定额套用',
  FEE_ERROR: '取费核查',
  QTY_ERROR: '工程量/合价',
  DUP_CHARGE: '重复计费',
  DRAWING_MISMATCH: '图纸核对',
  OTHER: '其他',
}

const rows = ref<IssueVO[]>([])
const total = ref(0)
const loading = ref(false)
const selection = ref<IssueVO[]>([])
const detailVisible = ref(false)
const current = ref<IssueVO>()
const comment = ref('')
const query = reactive<any>({ level: undefined, issueType: '', reviewStatus: '', keyword: '', page: 1, size: 20 })

function reviewTag(status: string): 'success' | 'danger' | 'warning' {
  if (status === 'CONFIRMED') {
    return 'success'
  }
  return status === 'REJECTED' ? 'danger' : 'warning'
}

async function reload() {
  loading.value = true
  try {
    const res = await issueApi.page({ ...query, projectId: props.projectId })
    rows.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

function onPageChange(page: number) {
  query.page = page
  reload()
}

function openDetail(row: IssueVO) {
  current.value = row
  comment.value = row.reviewComment || ''
  detailVisible.value = true
}

function locate(row: IssueVO) {
  // 项目级疑点（如混合计价取费口径提示）没有对应清单行，跳过去也只会看到一张无关清单
  if (!row.rowNo) {
    ElMessage.warning('该疑点是项目级问题，没有对应的清单行')
    return
  }
  detailVisible.value = false
  emit('locate', row.rowNo)
}

async function review(row: IssueVO, action: 'CONFIRM' | 'REJECT') {
  await issueApi.review(row.id, { action, comment: comment.value })
  ElMessage.success(action === 'CONFIRM' ? '已确认' : '已驳回')
  detailVisible.value = false
  await reload()
  emit('changed')
}

async function batchReview(action: 'CONFIRM' | 'REJECT') {
  const ids = selection.value.map((item) => item.id)
  const count = await issueApi.batchReview({ ids, action })
  ElMessage.success(`已处理 ${count} 条`)
  await reload()
  emit('changed')
}

function exportExcel() {
  issueApi.export({ projectId: props.projectId })
}

onMounted(reload)
watch(() => props.refreshKey, () => {
  // 核查会清空并重算疑点，条数变少时当前页可能越界，回到第一页再拉
  query.page = 1
  selection.value = []
  reload()
})
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
.detail {
  color: #303133;
  line-height: 1.7;
  white-space: pre-wrap;
}
.drawer-actions {
  margin-top: 16px;
  display: flex;
  gap: 8px;
}
h4 {
  margin: 18px 0 8px;
}
/* 疑点标题、关联清单项多是长中文，省略号截断后核对时看不全，统一换行完整展示 */
:deep(.el-table .cell) {
  white-space: normal;
  word-break: break-all;
  line-height: 1.45;
}
:deep(.wrap-cell .cell) {
  padding: 6px 10px;
}
</style>
