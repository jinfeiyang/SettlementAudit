<template>
  <div class="login-page">
    <div class="brand">
      <h1>通信工程造价结算</h1>
      <h2>AI 预审分析工具</h2>
      <p>清单导入 · 定额核查 · 取费核算 · 疑点复核 · 报告初稿</p>
    </div>
    <el-card class="login-card" shadow="always">
      <template #header>
        <span class="card-title">账号登录</span>
      </template>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" size="large" clearable />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            size="large"
            show-password
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" size="large" class="submit" :loading="loading" @click="submit">
          登录
        </el-button>
      </el-form>
      <el-alert
        class="hint"
        type="info"
        :closable="false"
        title="演示账号（4A 统一认证接入前使用）"
        description="造价人员 admin / 123456 ；项目经理 manager / 123456"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAppStore } from '@/stores/app'

const router = useRouter()
const route = useRoute()
const store = useAppStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({ username: 'admin', password: '123456' })

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  loading.value = true
  try {
    const user = await store.login(form.username, form.password)
    ElMessage.success(`欢迎，${user.realName || user.username}`)
    const redirect = (route.query.redirect as string) || '/dashboard'
    router.replace(redirect)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100%;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 88px;
  background: linear-gradient(135deg, #001529 0%, #0b3a6b 55%, #1f6feb 100%);
  color: #fff;
}
.brand h1 {
  font-size: 40px;
  line-height: 1.3;
  margin: 0 0 10px;
  letter-spacing: 1px;
}
.brand h2 {
  font-size: 20px;
  margin: 0 0 20px;
  font-weight: 400;
  letter-spacing: 3px;
  color: #9fc3ff;
}
.brand p {
  margin: 0;
  font-size: 13px;
  letter-spacing: 2px;
  color: rgba(255, 255, 255, 0.6);
}
.login-card {
  width: 380px;
}
.card-title {
  font-weight: 600;
}
.submit {
  width: 100%;
}
.hint {
  margin-top: 16px;
}
</style>
