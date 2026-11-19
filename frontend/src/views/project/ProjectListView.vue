<template>
  <el-card shadow="never">
    <template #header>
      <div class="header-row">
        <span>预审项目管理</span>
        <el-button type="primary" @click="openCreate">新建预审项目</el-button>
      </div>
    </template>

    <el-form :inline="true" class="filters">
      <el-form-item label="关键字">
        <el-input
          v-model="query.keyword"
          placeholder="项目名称 / 编号"
          clearable
          style="width: 220px"
          @keyup.enter="reload"
        />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" placeholder="全部" clearable style="width: 140px">
          <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="reload">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows" empty-text="暂无项目，点击右上角新建">
      <el-table-column prop="projectNo" label="项目编号" width="170" class-name="wrap-cell" />
      <el-table-column prop="projectName" label="项目名称" min-width="300" class-name="wrap-cell" />
      <el-table-column label="合同编号" width="240" class-name="nowrap-cell">
        <template #default="{ row }">{{ row.contractNo || '-' }}</template>
      </el-table-column>
      <el-table-column label="订单编号" width="330" class-name="nowrap-cell">
        <template #default="{ row }">{{ row.orderNo || '-' }}</template>
      </el-table-column>
      <el-table-column label="施工单位" min-width="200" class-name="wrap-cell">
        <template #default="{ row }">{{ row.builderUnit || '-' }}</template>
      </el-table-column>
      <el-table-column label="监理单位" min-width="200" class-name="wrap-cell">
        <template #default="{ row }">{{ row.supervisorUnit || '-' }}</template>
      </el-table-column>
      <el-table-column prop="city" label="地市" width="90" />
      <el-table-column label="结算送审金额（元）" width="160" align="right">
        <template #default="{ row }">{{ money(row.settleAmount) }}</template>
      </el-table-column>
      <el-table-column label="施工费（元）" width="130" align="right">
        <template #default="{ row }">{{ money(row.constructionFee) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ row.statusName }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ datetime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="router.push(`/projects/${row.id}`)">进入作业</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      :current-page="query.page"
      :page-size="query.size"
      :total="total"
      layout="total, sizes, prev, pager, next, jumper"
      :page-sizes="[10, 20, 50]"
      @current-change="onPageChange"
      @size-change="onSizeChange"
    />
  </el-card>

  <el-dialog v-model="dialogVisible" :title="editing ? '编辑预审项目' : '新建预审项目'" width="560px">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
      <el-form-item v-if="!editing" label="导入图纸">
        <div class="guess-box">
          <el-upload
            :auto-upload="false"
            :limit="1"
            accept=".dwg,.dxf"
            :show-file-list="false"
            :on-change="guessFromDrawing"
          >
            <el-button :loading="guessing">选择图纸自动识别</el-button>
          </el-upload>
          <span class="guess-tip">
            {{ guessNote || '支持 DXF/DWG，自动识别图签里的项目名称、编号、参建单位等' }}
          </span>
        </div>
      </el-form-item>
      <el-form-item label="项目名称" prop="projectName">
        <el-input v-model="form.projectName" placeholder="如：XX 市 XX 段光缆线路工程" />
      </el-form-item>
      <el-form-item label="项目编号">
        <el-input
          v-model="form.projectNo"
          :placeholder="editing ? '留空则保持原编号不变' : '留空自动生成'"
          clearable
        />
      </el-form-item>
      <el-form-item label="合同编号">
        <el-input v-model="form.contractNo" placeholder="选填，如 CU12-3301-2024-000368" />
      </el-form-item>
      <el-form-item label="订单编号">
        <el-input v-model="form.orderNo" placeholder="选填，如 PO-zj-zjszysc-202605080003744" />
      </el-form-item>
      <el-form-item label="施工单位">
        <el-input v-model="form.builderUnit" placeholder="选填" />
      </el-form-item>
      <el-form-item label="监理单位">
        <el-input v-model="form.supervisorUnit" placeholder="选填" />
      </el-form-item>
      <el-form-item label="地市">
        <el-input v-model="form.city" placeholder="如：台州" />
      </el-form-item>
      <el-form-item label="结算送审金额">
        <el-input-number v-model="form.settleAmount" :min="0" :step="1000" :precision="2" style="width: 220px" />
        <span class="unit">元</span>
      </el-form-item>
      <el-form-item label="施工费">
        <el-input-number v-model="form.constructionFee" :min="0" :step="1000" :precision="2" style="width: 220px" />
        <span class="unit">元（不含税）</span>
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="选填" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules, type UploadFile } from 'element-plus'
import { projectApi, type ProjectVO } from '@/api'
import { datetime, money, statusTag } from '@/utils/format'

const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const rows = ref<ProjectVO[]>([])
const total = ref(0)
const dialogVisible = ref(false)
const editing = ref(false)
const editingId = ref<number>()
const formRef = ref<FormInstance>()
const guessing = ref(false)
const guessNote = ref('')

// 图签栏目名 → 表单字段：同一含义有多种写法，按顺序取第一个非空的
const LABEL_FIELDS: Record<string, string> = {
  项目名称: 'projectName',
  工程名称: 'projectName',
  项目编号: 'projectNo',
  合同编号: 'contractNo',
  订单编号: 'orderNo',
  施工单位: 'builderUnit',
  监理单位: 'supervisorUnit',
}
const CITY_NAMES = ['杭州', '宁波', '温州', '嘉兴', '湖州', '绍兴', '金华', '衢州', '舟山', '台州', '丽水']

async function guessFromDrawing(file: UploadFile) {
  if (!file.raw) {
    return
  }
  guessing.value = true
  guessNote.value = '识别中…'
  try {
    const res = await projectApi.parseDrawingMeta(file.raw)
    fillFromProjectInfo(res.projectInfo || [])
  } catch (e: any) {
    guessNote.value = e?.message || '识别失败，请手工填写'
  } finally {
    guessing.value = false
  }
}

function fillFromProjectInfo(info: { label: string; value: string }[]) {
  const filled: string[] = []
  for (const { label, value } of info) {
    const text = (value || '').trim()
    if (!text) {
      continue
    }
    const key = LABEL_FIELDS[label]
    // 已经填过的不覆盖，避免把手工改过的内容冲掉
    if (key && !String(form[key] ?? '').trim()) {
      form[key] = text
      filled.push(label)
    }
    if (label === '施工地点' && !form.city) {
      const city = CITY_NAMES.find((name) => text.includes(name))
      if (city) {
        form.city = city
        filled.push('地市')
      }
    }
    if (label === '施工费' && form.constructionFee == null) {
      const amount = parseAmount(text)
      if (amount != null) {
        form.constructionFee = amount
        filled.push('施工费')
      }
    }
  }
  guessNote.value = filled.length
    ? `已识别并填入：${filled.join('、')}`
    : '未识别到项目信息，请手工填写'
}

function parseAmount(text: string) {
  const wan = text.match(/([\d.]+)\s*万元/)
  if (wan) {
    return Math.round(Number(wan[1]) * 10000 * 100) / 100
  }
  const yuan = text.match(/([\d.]+)\s*元/)
  return yuan ? Number(yuan[1]) : null
}

const statusOptions = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'IMPORTED', label: '已导入' },
  { value: 'CHECKING', label: '核查中' },
  { value: 'CHECKED', label: '已核查' },
  { value: 'REPORTED', label: '已出报告' },
  { value: 'ARCHIVED', label: '已归档' },
]

const query = reactive({ keyword: '', status: '', page: 1, size: 10 })
// 工程类型页面暂不展示，仍按 LINE 提交，保证后端校验与字典展示有值
const form = reactive<any>({
  projectName: '',
  projectNo: '',
  engType: 'LINE',
  contractNo: '',
  orderNo: '',
  builderUnit: '',
  supervisorUnit: '',
  city: '',
  settleAmount: undefined,
  constructionFee: undefined,
  remark: '',
})

const rules: FormRules = {
  projectName: [{ required: true, message: '请输入项目名称', trigger: 'blur' }],
}

async function reload() {
  loading.value = true
  try {
    const res = await projectApi.page({ ...query })
    rows.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

function reset() {
  query.keyword = ''
  query.status = ''
  query.page = 1
  reload()
}

function onPageChange(page: number) {
  query.page = page
  reload()
}

function onSizeChange(size: number) {
  query.size = size
  query.page = 1
  reload()
}

function openCreate() {
  editing.value = false
  editingId.value = undefined
  guessNote.value = ''
  Object.assign(form, {
    projectName: '',
    projectNo: '',
    engType: 'LINE',
    contractNo: '',
    orderNo: '',
    builderUnit: '',
    supervisorUnit: '',
    city: '',
    settleAmount: undefined,
    constructionFee: undefined,
    remark: '',
  })
  dialogVisible.value = true
}

function openEdit(row: ProjectVO) {
  editing.value = true
  editingId.value = row.id
  Object.assign(form, {
    projectName: row.projectName,
    projectNo: row.projectNo,
    engType: row.engType || 'LINE',
    contractNo: row.contractNo || '',
    orderNo: row.orderNo || '',
    builderUnit: row.builderUnit || '',
    supervisorUnit: row.supervisorUnit || '',
    city: row.city,
    settleAmount: row.settleAmount,
    constructionFee: row.constructionFee,
    remark: row.remark,
  })
  dialogVisible.value = true
}

async function save() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  saving.value = true
  try {
    const payload = {
      ...form,
      settleAmount: form.settleAmount ?? null,
      constructionFee: form.constructionFee ?? null,
    }
    if (editing.value && editingId.value) {
      await projectApi.update(editingId.value, payload)
      ElMessage.success('已保存')
    } else {
      const created = await projectApi.create(payload)
      ElMessage.success('项目已创建，可继续上传资料')
      dialogVisible.value = false
      router.push(`/projects/${created.id}`)
      return
    }
    dialogVisible.value = false
    reload()
  } finally {
    saving.value = false
  }
}

async function remove(row: ProjectVO) {
  await ElMessageBox.confirm(`确认删除项目「${row.projectName}」？清单、疑点与报告将一并删除。`, '删除确认', {
    type: 'warning',
  })
  await projectApi.remove(row.id)
  ElMessage.success('已删除')
  reload()
}

onMounted(reload)
</script>

<style scoped>
.guess-box {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
}

.guess-tip {
  flex: 1;
  color: #909399;
  font-size: 12px;
  line-height: 1.4;
}

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
.unit {
  margin-left: 8px;
  color: #909399;
}
/* 项目名称、单位名称偏长，省略号截断后看不清，改为换行完整展示 */
:deep(.wrap-cell .cell) {
  white-space: normal;
  word-break: break-all;
  line-height: 1.45;
  padding: 4px 8px;
}
/* 合同/订单编号是连贯的一串字符，折行反而难读，列宽给够后单行展示 */
:deep(.nowrap-cell .cell) {
  white-space: nowrap;
  overflow: visible;
  padding: 4px 8px;
}
</style>
