<template>
  <el-card shadow="never">
    <template #header>
      <div class="header-row">
        <span>规则库（定额子目 / 取费标准，外置可配置）</span>
        <span class="hint">规则带版本与生效时间；命中率偏低时优先补充定额子目与特征关键词</span>
      </div>
    </template>

    <el-tabs v-model="tab">
      <el-tab-pane label="定额子目" name="quota">
        <div class="toolbar">
          <el-input
            v-model="quotaQuery.keyword"
            placeholder="定额编号 / 名称 / 特征关键词"
            clearable
            style="width: 260px"
            @keyup.enter="loadQuota"
          />
          <el-select v-model="quotaQuery.engType" placeholder="全部类型" clearable style="width: 140px">
            <el-option label="通信线路" value="LINE" />
            <el-option label="设备安装" value="DEVICE" />
          </el-select>
          <el-button type="primary" @click="loadQuota">查询</el-button>
          <el-button @click="resetQuota">重置</el-button>
          <el-button type="primary" plain @click="openQuotaCreate">新增定额</el-button>
          <el-upload :show-file-list="false" :http-request="importQuota" accept=".xlsx,.xls">
            <el-button>导入 Excel</el-button>
          </el-upload>
          <el-button @click="ruleApi.quotaTemplate()">下载模板</el-button>
        </div>

        <el-table
          ref="quotaTableRef"
          v-loading="quotaLoading"
          :data="quotas"
          empty-text="暂无定额子目"
          :height="quotaHeight"
        >
          <el-table-column prop="quotaCode" label="定额编号" width="130" />
          <el-table-column prop="quotaName" label="定额名称" min-width="220" show-overflow-tooltip />
          <el-table-column prop="unit" label="单位" width="80" />
          <el-table-column label="基价" width="110" align="right">
            <template #default="{ row }">{{ money(row.basePrice) }}</template>
          </el-table-column>
          <el-table-column label="人工费" width="110" align="right">
            <template #default="{ row }">{{ money(row.laborPrice) }}</template>
          </el-table-column>
          <el-table-column label="特征关键词" min-width="200">
            <template #default="{ row }">
              <el-tag v-for="key in row.featureKeys || []" :key="key" size="small" class="key">{{ key }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="工程类型" width="110">
            <template #default="{ row }">{{ row.engTypeName || '-' }}</template>
          </el-table-column>
          <el-table-column prop="version" label="版本" width="80" />
          <el-table-column label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openQuotaEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="removeQuota(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-pagination
          class="pager"
          :current-page="quotaQuery.page"
          :page-size="quotaQuery.size"
          :total="quotaTotal"
          layout="total, sizes, prev, pager, next, jumper"
          :page-sizes="[10, 20, 50]"
          @current-change="(p: number) => { quotaQuery.page = p; loadQuota() }"
          @size-change="(s: number) => { quotaQuery.size = s; quotaQuery.page = 1; loadQuota() }"
        />
      </el-tab-pane>

      <el-tab-pane label="取费规则" name="fee">
        <div class="toolbar">
          <el-input
            v-model="feeQuery.keyword"
            placeholder="费用项编码 / 名称 / 工程类别"
            clearable
            style="width: 260px"
            @keyup.enter="loadFee"
          />
          <el-select v-model="feeQuery.engType" placeholder="全部类型" clearable style="width: 140px">
            <el-option label="通信线路" value="LINE" />
            <el-option label="设备安装" value="DEVICE" />
          </el-select>
          <el-button type="primary" @click="loadFee">查询</el-button>
          <el-button @click="resetFee">重置</el-button>
          <el-button type="primary" plain @click="openFeeCreate">新增费用项</el-button>
          <el-upload :show-file-list="false" :http-request="importFee" accept=".xlsx,.xls">
            <el-button>导入 Excel</el-button>
          </el-upload>
          <el-button @click="ruleApi.feeTemplate()">下载模板</el-button>
        </div>

        <el-table
          ref="feeTableRef"
          v-loading="feeLoading"
          :data="fees"
          empty-text="暂无取费规则"
          :height="feeHeight"
        >
          <el-table-column prop="feeCode" label="费用项编码" width="130" />
          <el-table-column prop="feeName" label="费用项名称" min-width="160" />
          <el-table-column prop="baseFormula" label="取费基数公式" min-width="160" />
          <el-table-column label="费率" width="110" align="right">
            <template #default="{ row }">{{ decimalPercent(row.rate) }}</template>
          </el-table-column>
          <el-table-column prop="engCategory" label="工程类别" width="120" />
          <el-table-column label="工程类型" width="110">
            <template #default="{ row }">
              {{ row.engType === 'LINE' ? '通信线路' : row.engType === 'DEVICE' ? '设备安装' : '通用' }}
            </template>
          </el-table-column>
          <el-table-column prop="version" label="版本" width="80" />
          <el-table-column prop="effectiveDate" label="生效日期" width="120" />
          <el-table-column label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openFeeEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="removeFee(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-pagination
          class="pager"
          :current-page="feeQuery.page"
          :page-size="feeQuery.size"
          :total="feeTotal"
          layout="total, sizes, prev, pager, next, jumper"
          :page-sizes="[10, 20, 50]"
          @current-change="(p: number) => { feeQuery.page = p; loadFee() }"
          @size-change="(s: number) => { feeQuery.size = s; feeQuery.page = 1; loadFee() }"
        />
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="quotaVisible" :title="quotaEditing ? '编辑定额子目' : '新增定额子目'" width="600px">
      <el-form :model="quotaForm" label-width="100px">
        <el-form-item label="定额编号" required>
          <el-input v-model="quotaForm.quotaCode" placeholder="如 TXL1-001" />
        </el-form-item>
        <el-form-item label="定额名称" required>
          <el-input v-model="quotaForm.quotaName" placeholder="如 敷设室外光缆（架空）" />
        </el-form-item>
        <el-form-item label="单位">
          <el-input v-model="quotaForm.unit" placeholder="如 百米 / 台" />
        </el-form-item>
        <el-form-item label="基价（元）">
          <el-input-number v-model="quotaForm.basePrice" :min="0" :precision="2" style="width: 200px" />
        </el-form-item>
        <el-form-item label="人工费（元）">
          <el-input-number v-model="quotaForm.laborPrice" :min="0" :precision="2" style="width: 200px" />
          <span class="hint">用于取费基数核算</span>
        </el-form-item>
        <el-form-item label="特征关键词">
          <el-select
            v-model="quotaForm.featureKeys"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="输入后回车，如 光缆 / 架空 / 敷设"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="工程类型">
          <el-radio-group v-model="quotaForm.engType">
            <el-radio value="LINE">通信线路</el-radio>
            <el-radio value="DEVICE">设备安装</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="版本">
          <el-input v-model="quotaForm.version" placeholder="v1" style="width: 160px" />
        </el-form-item>
        <el-form-item label="生效日期">
          <el-date-picker v-model="quotaForm.effectiveDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="quotaVisible = false">取消</el-button>
        <el-button type="primary" @click="saveQuota">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="feeVisible" :title="feeEditing ? '编辑取费规则' : '新增取费规则'" width="600px">
      <el-form :model="feeForm" label-width="110px">
        <el-form-item label="费用项编码" required>
          <el-input v-model="feeForm.feeCode" placeholder="如 F01" />
        </el-form-item>
        <el-form-item label="费用项名称" required>
          <el-input v-model="feeForm.feeName" placeholder="如 措施项目费" />
        </el-form-item>
        <el-form-item label="取费基数公式">
          <el-input v-model="feeForm.baseFormula" placeholder="如 人工费*1.0 / 直接费*1.0" />
        </el-form-item>
        <el-form-item label="费率">
          <el-input-number v-model="feeForm.rate" :min="0" :max="1" :step="0.005" :precision="4" style="width: 200px" />
          <span class="hint">小数形式，0.05 表示 5%</span>
        </el-form-item>
        <el-form-item label="工程类别">
          <el-input v-model="feeForm.engCategory" placeholder="如 通信线路 / 设备安装" />
        </el-form-item>
        <el-form-item label="工程类型">
          <el-select v-model="feeForm.engType" clearable placeholder="通用" style="width: 200px">
            <el-option label="通信线路" value="LINE" />
            <el-option label="设备安装" value="DEVICE" />
          </el-select>
        </el-form-item>
        <el-form-item label="版本">
          <el-input v-model="feeForm.version" placeholder="v1" style="width: 160px" />
        </el-form-item>
        <el-form-item label="生效日期">
          <el-date-picker v-model="feeForm.effectiveDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="feeVisible = false">取消</el-button>
        <el-button type="primary" @click="saveFee">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type TableInstance, type UploadRequestOptions } from 'element-plus'
import { ruleApi } from '@/api'
import { decimalPercent, money } from '@/utils/format'
import { useTableHeight } from '@/utils/useTableHeight'

const tab = ref('quota')

/** 表格高度跟随窗口，50 条/页时也不会撑出可视区域 */
const quotaTableRef = ref<TableInstance>()
const quotaHeight = useTableHeight(quotaTableRef)
const feeTableRef = ref<TableInstance>()
const feeHeight = useTableHeight(feeTableRef)

const quotas = ref<any[]>([])
const quotaTotal = ref(0)
const quotaLoading = ref(false)
const quotaQuery = reactive<any>({ keyword: '', engType: '', page: 1, size: 20 })
const quotaVisible = ref(false)
const quotaEditing = ref(false)
const quotaEditId = ref<number>()
const quotaForm = reactive<any>({
  quotaCode: '',
  quotaName: '',
  unit: '',
  basePrice: undefined,
  laborPrice: undefined,
  featureKeys: [],
  engType: 'LINE',
  version: 'v1',
  effectiveDate: undefined,
})

const fees = ref<any[]>([])
const feeTotal = ref(0)
const feeLoading = ref(false)
const feeQuery = reactive<any>({ keyword: '', engType: '', page: 1, size: 20 })
const feeVisible = ref(false)
const feeEditing = ref(false)
const feeEditId = ref<number>()
const feeForm = reactive<any>({
  feeCode: '',
  feeName: '',
  baseFormula: '人工费*1.0',
  rate: 0.05,
  engCategory: '',
  engType: 'LINE',
  version: 'v1',
  effectiveDate: undefined,
})

async function loadQuota() {
  quotaLoading.value = true
  try {
    const res = await ruleApi.quotaPage({ ...quotaQuery })
    quotas.value = res.records
    quotaTotal.value = res.total
  } finally {
    quotaLoading.value = false
  }
}

async function loadFee() {
  feeLoading.value = true
  try {
    const res = await ruleApi.feePage({ ...feeQuery })
    fees.value = res.records
    feeTotal.value = res.total
  } finally {
    feeLoading.value = false
  }
}

function resetQuota() {
  quotaQuery.keyword = ''
  quotaQuery.engType = ''
  quotaQuery.page = 1
  loadQuota()
}

function resetFee() {
  feeQuery.keyword = ''
  feeQuery.engType = ''
  feeQuery.page = 1
  loadFee()
}

function openQuotaCreate() {
  quotaEditing.value = false
  quotaEditId.value = undefined
  Object.assign(quotaForm, {
    quotaCode: '',
    quotaName: '',
    unit: '',
    basePrice: undefined,
    laborPrice: undefined,
    featureKeys: [],
    engType: 'LINE',
    version: 'v1',
    effectiveDate: undefined,
  })
  quotaVisible.value = true
}

function openQuotaEdit(row: any) {
  quotaEditing.value = true
  quotaEditId.value = row.id
  Object.assign(quotaForm, row)
  quotaVisible.value = true
}

async function saveQuota() {
  if (!quotaForm.quotaCode || !quotaForm.quotaName) {
    ElMessage.warning('定额编号与名称必填')
    return
  }
  const payload = { ...quotaForm }
  if (quotaEditing.value && quotaEditId.value) {
    await ruleApi.quotaUpdate(quotaEditId.value, payload)
  } else {
    await ruleApi.quotaCreate(payload)
  }
  ElMessage.success('已保存')
  quotaVisible.value = false
  await loadQuota()
}

async function removeQuota(row: any) {
  await ElMessageBox.confirm(`确认删除定额「${row.quotaCode} ${row.quotaName}」？`, '删除确认', {
    type: 'warning',
  })
  await ruleApi.quotaDelete(row.id)
  ElMessage.success('已删除')
  await loadQuota()
}

async function importQuota(options: UploadRequestOptions) {
  const res = await ruleApi.quotaImport(options.file as File)
  ElMessage.success(res.message)
  await loadQuota()
}

function openFeeCreate() {
  feeEditing.value = false
  feeEditId.value = undefined
  Object.assign(feeForm, {
    feeCode: '',
    feeName: '',
    baseFormula: '人工费*1.0',
    rate: 0.05,
    engCategory: '',
    engType: 'LINE',
    version: 'v1',
    effectiveDate: undefined,
  })
  feeVisible.value = true
}

function openFeeEdit(row: any) {
  feeEditing.value = true
  feeEditId.value = row.id
  Object.assign(feeForm, row)
  feeVisible.value = true
}

async function saveFee() {
  if (!feeForm.feeCode || !feeForm.feeName) {
    ElMessage.warning('费用项编码与名称必填')
    return
  }
  const payload = { ...feeForm }
  if (feeEditing.value && feeEditId.value) {
    await ruleApi.feeUpdate(feeEditId.value, payload)
  } else {
    await ruleApi.feeCreate(payload)
  }
  ElMessage.success('已保存')
  feeVisible.value = false
  await loadFee()
}

async function removeFee(row: any) {
  await ElMessageBox.confirm(`确认删除费用项「${row.feeCode} ${row.feeName}」？`, '删除确认', {
    type: 'warning',
  })
  await ruleApi.feeDelete(row.id)
  ElMessage.success('已删除')
  await loadFee()
}

async function importFee(options: UploadRequestOptions) {
  const res = await ruleApi.feeImport(options.file as File)
  ElMessage.success(res.message)
  await loadFee()
}

onMounted(() => {
  loadQuota()
  loadFee()
})
</script>

<style scoped>
.header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.hint {
  color: #909399;
  font-size: 12px;
}
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
.key {
  margin-right: 4px;
}
</style>
