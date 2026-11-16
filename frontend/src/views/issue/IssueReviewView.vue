<template>
  <el-card shadow="never">
    <template #header>
      <div class="header-row">
        <span>疑点复核（三级分级 · 跨项目）</span>
        <el-button @click="exportExcel">导出疑点清单</el-button>
      </div>
    </template>

    <el-form :inline="true" class="filters">
      <el-form-item label="项目">
        <el-select v-model="query.projectId" placeholder="全部项目" clearable filterable style="width: 240px">
          <el-option v-for="p in projects" :key="p.id" :label="p.projectName" :value="p.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="级别">
        <el-select v-model="query.level" placeholder="全部" clearable style="width: 130px">
          <el-option label="高（确定偏差）" :value="3" />
          <el-option label="中（需复核）" :value="2" />
          <el-option label="低（提示性）" :value="1" />
        </el-select>
      </el-form-item>
      <el-form-item label="类型">
        <el-select v-model="query.issueType" placeholder="全部" clearable style="width: 140px">
          <el-option v-for="(label, value) in typeOptions" :key="value" :label="label" :value="value" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.reviewStatus" placeholder="全部" clearable style="width: 130px">
          <el-option label="待复核" value="PENDING" />
          <el-option label="已确认" value="CONFIRMED" />
          <el-option label="已驳回" value="REJECTED" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="reload">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows" empty-text="无疑点数据">
      <el-table-column label="级别" width="80">
        <template #default="{ row }">
          <el-tag :type="levelTag(row.level)" size="small" effect="dark">{{ row.levelName }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="projectName" label="项目" width="200" show-overflow-tooltip />
      <el-table-column prop="issueTypeName" label="类型" width="110" />
      <el-table-column prop="title" label="疑点标题" min-width="240" show-overflow-tooltip />
      <el-table-column label="清单行号" width="100" align="center">
        <template #default="{ row }">
          <el-button v-if="row.rowNo" link type="primary" @click="locate(row)">{{ row.rowNo }}</el-button>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="复核状态" width="110">
        <template #default="{ row }">
          <el-tag :type="reviewTag(row.reviewStatus)" size="small" effect="plain">
            {{ row.reviewStatusName }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
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
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { issueApi, projectApi, type IssueVO, type ProjectVO } from '@/api'
import { levelTag } from '@/utils/format'

const router = useRouter()
const rows = ref<IssueVO[]>([])
const projects = ref<ProjectVO[]>([])
const total = ref(0)
const loading = ref(false)

const typeOptions: Record<string, string> = {
  QUOTA_MISMATCH: '定额套用',
  FEE_ERROR: '取费核查',
  QTY_ERROR: '工程量/合价',
  DUP_CHARGE: '重复计费',
  OTHER: '其他',
}

const query = reactive<any>({
  projectId: undefined,
  level: undefined,
  issueType: '',
  reviewStatus: '',
  page: 1,
  size: 20,
})

function reviewTag(status: string): 'success' | 'danger' | 'warning' {
  if (status === 'CONFIRMED') {
    return 'success'
  }
  return status === 'REJECTED' ? 'danger' : 'warning'
}

async function reload() {
  loading.value = true
  try {
    const res = await issueApi.page({ ...query })
    rows.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

function reset() {
  query.projectId = undefined
  query.level = undefined
  query.issueType = ''
  query.reviewStatus = ''
  query.page = 1
  reload()
}

function onPageChange(page: number) {
  query.page = page
  reload()
}

function locate(row: IssueVO) {
  router.push({ path: `/projects/${row.projectId}`, query: { tab: 'boq', row: String(row.rowNo ?? '') } })
}

async function review(row: IssueVO, action: 'CONFIRM' | 'REJECT') {
  await issueApi.review(row.id, { action })
  ElMessage.success(action === 'CONFIRM' ? '已确认' : '已驳回')
  await reload()
}

function exportExcel() {
  const params: Record<string, unknown> = {}
  if (query.projectId) {
    params.projectId = query.projectId
  }
  if (query.level) {
    params.level = query.level
  }
  if (query.issueType) {
    params.issueType = query.issueType
  }
  if (query.reviewStatus) {
    params.reviewStatus = query.reviewStatus
  }
  issueApi.export(params)
}

onMounted(async () => {
  const res = await projectApi.page({ page: 1, size: 100 })
  projects.value = res.records
  await reload()
})
</script>

<style scoped>
.header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.filters {
  margin-bottom: 4px;
}
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
