<template>
  <div v-if="overview">
    <el-row :gutter="16">
      <el-col :span="6">
        <el-card shadow="hover" class="metric">
          <div class="label">高风险疑点（确定偏差）</div>
          <div class="value danger">{{ overview.issueHigh }}</div>
          <el-button link type="primary" @click="$emit('go', 'issues')">查看疑点</el-button>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="metric">
          <div class="label">中风险疑点（需复核）</div>
          <div class="value warning">{{ overview.issueMedium }}</div>
          <el-button link type="primary" @click="$emit('go', 'issues')">查看疑点</el-button>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="metric">
          <div class="label">定额前三命中率</div>
          <div class="value" :class="hitClass">{{ percent(overview.hitRate) }}</div>
          <span class="hint">验收目标 ≥ 85%</span>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="metric">
          <div class="label">取费偏差</div>
          <div class="value" :class="feeClass">{{ decimalPercent(overview.feeDeviation) }}</div>
          <span class="hint">核算金额 {{ money(overview.checkAmount) }} 元</span>
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
    <el-alert
      v-else-if="overview.hitRate !== undefined && overview.hitRate < 85"
      class="tip"
      type="warning"
      show-icon
      :closable="false"
      title="定额前三命中率低于验收目标 85%"
      description="建议在「规则库」补充通信线路/设备安装两类定额子目与特征关键词，提升自动匹配质量。"
    />

    <el-descriptions class="summary" :column="3" border>
      <el-descriptions-item label="疑点合计">{{ overview.issueTotal }}</el-descriptions-item>
      <el-descriptions-item label="待复核">{{ overview.issuePending }}</el-descriptions-item>
      <el-descriptions-item label="已确认">{{ overview.issueConfirmed }}</el-descriptions-item>
      <el-descriptions-item label="低风险提示">{{ overview.issueLow }}</el-descriptions-item>
      <el-descriptions-item label="已驳回">{{ overview.issueRejected }}</el-descriptions-item>
      <el-descriptions-item label="报告状态">
        {{ overview.reportStatus === 'CONFIRMED' ? '已确认' : overview.reportStatus ? '草稿' : '未生成' }}
      </el-descriptions-item>
    </el-descriptions>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { Overview } from '@/api'
import { decimalPercent, money, percent } from '@/utils/format'

const props = defineProps<{ overview?: Overview }>()
defineEmits<{ (e: 'go', tab: string): void }>()

const hitClass = computed(() => {
  const rate = props.overview?.hitRate
  if (rate === undefined || rate === null) {
    return ''
  }
  return rate >= 85 ? 'success' : 'warning'
})

const feeClass = computed(() => {
  const dev = props.overview?.feeDeviation
  if (dev === undefined || dev === null) {
    return ''
  }
  return Math.abs(dev) <= 0.05 ? 'success' : Math.abs(dev) > 0.15 ? 'danger' : 'warning'
})
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
