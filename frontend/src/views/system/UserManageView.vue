<template>
  <el-card shadow="never">
    <template #header>
      <div class="header-row">
        <span>用户管理</span>
        <span class="hint">仅项目经理可新增、停用、改角色和重置密码；至少保留一个启用的项目经理账号</span>
      </div>
    </template>

    <el-alert
      v-if="!store.isManager"
      class="tip"
      type="warning"
      show-icon
      :closable="false"
      title="当前账号为造价人员，只有项目经理可以管理用户"
    />

    <div class="toolbar">
      <el-input
        v-model="query.keyword"
        placeholder="用户名 / 姓名"
        clearable
        style="width: 240px"
        @keyup.enter="reload"
      />
      <el-button type="primary" @click="reload">查询</el-button>
      <el-button @click="resetQuery">重置</el-button>
      <el-button type="primary" plain :disabled="!store.isManager" @click="openCreate">新增用户</el-button>
    </div>

    <el-table ref="tableRef" v-loading="loading" :data="rows" empty-text="暂无用户" :height="tableHeight">
      <el-table-column prop="username" label="用户名" min-width="160" />
      <el-table-column prop="realName" label="姓名" min-width="140">
        <template #default="{ row }">{{ row.realName || '-' }}</template>
      </el-table-column>
      <el-table-column label="角色" width="120">
        <template #default="{ row }">
          <el-tag :type="row.roleCode === 'MANAGER' ? 'warning' : 'primary'" size="small" effect="plain">
            {{ row.roleName }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small" effect="dark">
            {{ row.statusName }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ datetime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="300" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="!store.isManager" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" :disabled="!store.isManager" @click="openPassword(row)">重置密码</el-button>
          <el-button
            link
            :type="row.status === 1 ? 'warning' : 'success'"
            :disabled="!store.isManager || row.id === store.user?.id"
            @click="toggleStatus(row)"
          >
            {{ row.status === 1 ? '停用' : '启用' }}
          </el-button>
          <el-button link type="danger" :disabled="!store.isManager || row.id === store.user?.id" @click="remove(row)">
            删除
          </el-button>
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
      @current-change="(p: number) => { query.page = p; reload() }"
      @size-change="(s: number) => { query.size = s; query.page = 1; reload() }"
    />

    <el-dialog v-model="formVisible" :title="editingId ? '编辑用户' : '新增用户'" width="480px" align-center>
      <el-form :model="form" label-width="80px">
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" :disabled="!!editingId" placeholder="3~32 位字母、数字或 _.-" />
        </el-form-item>
        <el-form-item v-if="!editingId" label="密码" required>
          <el-input v-model="form.password" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="form.realName" placeholder="真实姓名" />
        </el-form-item>
        <el-form-item label="角色" required>
          <el-select v-model="form.roleCode" style="width: 100%">
            <el-option label="造价人员" value="AUDITOR" />
            <el-option label="项目经理" value="MANAGER" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="editingId" label="状态">
          <el-select v-model="form.status" style="width: 100%">
            <el-option label="启用" :value="1" />
            <el-option label="禁用" :value="0" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="passwordVisible" title="重置密码" width="420px" align-center>
      <el-form :model="passwordForm" label-width="80px">
        <el-form-item label="用户">
          <el-input :model-value="passwordUser?.username || ''" disabled />
        </el-form-item>
        <el-form-item label="新密码" required>
          <el-input v-model="passwordForm.password" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitPassword">确定</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { userApi, type SysUserVO } from '@/api'
import { useAppStore } from '@/stores/app'
import { datetime } from '@/utils/format'
import { useTableHeight } from '@/utils/useTableHeight'

const store = useAppStore()
const tableRef = ref()
const tableHeight = useTableHeight(tableRef)
const loading = ref(false)
const saving = ref(false)
const rows = ref<SysUserVO[]>([])
const total = ref(0)
const query = reactive({ page: 1, size: 20, keyword: '' })
const formVisible = ref(false)
const passwordVisible = ref(false)
const editingId = ref<number>()
const passwordUser = ref<SysUserVO>()
const form = reactive({ username: '', password: '', realName: '', roleCode: 'AUDITOR', status: 1 })
const passwordForm = reactive({ password: '' })

async function reload() {
  loading.value = true
  try {
    const res = await userApi.page({ ...query })
    rows.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

function resetQuery() {
  query.keyword = ''
  query.page = 1
  reload()
}

function openCreate() {
  editingId.value = undefined
  Object.assign(form, { username: '', password: '', realName: '', roleCode: 'AUDITOR', status: 1 })
  formVisible.value = true
}

function openEdit(row: SysUserVO) {
  editingId.value = row.id
  Object.assign(form, {
    username: row.username,
    password: '',
    realName: row.realName ?? '',
    roleCode: row.roleCode,
    status: row.status,
  })
  formVisible.value = true
}

async function submitForm() {
  saving.value = true
  try {
    if (editingId.value) {
      await userApi.update(editingId.value, {
        realName: form.realName,
        roleCode: form.roleCode,
        status: form.status,
      })
      ElMessage.success('用户已更新')
    } else {
      await userApi.create({
        username: form.username,
        password: form.password,
        realName: form.realName,
        roleCode: form.roleCode,
      })
      ElMessage.success('用户已新增')
    }
    formVisible.value = false
    await reload()
  } finally {
    saving.value = false
  }
}

function openPassword(row: SysUserVO) {
  passwordUser.value = row
  passwordForm.password = ''
  passwordVisible.value = true
}

async function submitPassword() {
  if (!passwordUser.value) {
    return
  }
  saving.value = true
  try {
    await userApi.resetPassword(passwordUser.value.id, passwordForm.password)
    passwordVisible.value = false
    ElMessage.success('密码已重置')
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row: SysUserVO) {
  const status = row.status === 1 ? 0 : 1
  await ElMessageBox.confirm(`确定${status === 1 ? '启用' : '停用'}用户「${row.username}」吗？`, '提示', {
    type: 'warning',
  })
  await userApi.update(row.id, { status })
  ElMessage.success('状态已更新')
  await reload()
}

async function remove(row: SysUserVO) {
  await ElMessageBox.confirm(`确定删除用户「${row.username}」吗？`, '提示', { type: 'warning' })
  await userApi.remove(row.id)
  ElMessage.success('用户已删除')
  await reload()
}

onMounted(reload)
</script>

<style scoped>
.header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
.hint {
  color: #909399;
  font-size: 12px;
}
.tip {
  margin-bottom: 12px;
}
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
