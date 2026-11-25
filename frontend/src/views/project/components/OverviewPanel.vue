<template>
  <div v-if="overview">
    <el-row :gutter="16">
      <el-col :span="8">
        <el-card shadow="hover" class="metric">
          <div class="label">高风险疑点</div>
          <div class="value danger">{{ overview.issueHigh }}</div>
          <el-button link type="primary" @click="$emit('issueFilter', { level: 3 })">查看疑点</el-button>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" class="metric">
          <div class="label">中风险疑点</div>
          <div class="value warning">{{ overview.issueMedium }}</div>
          <el-button link type="primary" @click="$emit('issueFilter', { level: 2 })">查看疑点</el-button>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" class="metric">
          <div class="label">低风险疑点</div>
          <div class="value low">{{ overview.issueLow }}</div>
          <el-button link type="primary" @click="$emit('issueFilter', { level: 1 })">查看疑点</el-button>
        </el-card>
      </el-col>
    </el-row>

    <el-alert
      v-if="overview.boqCount === 0"
      class="tip"
      type="warning"
      show-icon
      :closable="false"
      title="尚未导入工程量清单"
      description="请到「资料与解析」上传清单 Excel（可下载模板），解析成功后再发起自动核查。"
    />

    <el-descriptions class="summary" :column="3" border>
      <el-descriptions-item label="疑点合计">
        <el-button link type="primary" @click="$emit('issueFilter', {})">{{ overview.issueTotal }}</el-button>
      </el-descriptions-item>
      <el-descriptions-item label="待复核">
        <el-button link type="primary" @click="$emit('issueFilter', { reviewStatus: 'PENDING' })">
          {{ overview.issuePending }}
        </el-button>
      </el-descriptions-item>
      <el-descriptions-item label="已确认">
        <el-button link type="primary" @click="$emit('issueFilter', { reviewStatus: 'CONFIRMED' })">
          {{ overview.issueConfirmed }}
        </el-button>
      </el-descriptions-item>
      <el-descriptions-item label="已驳回">
        <el-button link type="primary" @click="$emit('issueFilter', { reviewStatus: 'REJECTED' })">
          {{ overview.issueRejected }}
        </el-button>
      </el-descriptions-item>
      <el-descriptions-item label="清单项数">{{ overview.boqCount }}</el-descriptions-item>
      <el-descriptions-item label="最近核查">{{ datetime(overview.lastCheckTime) }}</el-descriptions-item>
    </el-descriptions>
  </div>
</template>

<script setup lang="ts">
import type { Overview } from '@/api'
import { datetime } from '@/utils/format'

defineProps<{ overview?: Overview }>()
defineEmits<{
  (e: 'go', tab: string): void
  (e: 'issueFilter', filter: { level?: number; reviewStatus?: string }): void
}>()
</script>

<style scoped>
.metric .label {
  color: #909399;
  font-size: 13px;
}
.metric .value {
  font-size: 28px;
  font-weight: 600;
  margin: 8px 0;
}
.metric .hint {
  color: #a8abb2;
  font-size: 12px;
}
.danger {
  color: #f56c6c;
}
.warning {
  color: #e6a23c;
}
.low {
  color: #909399;
}
.success {
  color: #67c23a;
}
.tip {
  margin: 16px 0;
}
.summary {
  margin-top: 16px;
}
</style>
