<template>
  <div>
    <el-row :gutter="16">
      <el-col v-for="card in cards" :key="card.label" :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="label">{{ card.label }}</div>
          <div class="value" :style="{ color: card.color }">{{ card.value }}</div>
          <div class="sub">{{ card.sub }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="charts">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>疑点分级分布（一期指标：三级分级）</template>
          <div ref="barRef" class="chart" />
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>疑点类型分布</template>
          <div ref="pieRef" class="chart" />
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="recent">
      <template #header>
        <div class="card-header">
          <span>最近预审项目</span>
          <el-button text type="primary" @click="router.push('/projects')">查看全部</el-button>
        </div>
      </template>
      <el-table :data="recent" empty-text="暂无项目，先到「项目管理」新建预审项目">
        <el-table-column prop="projectNo" label="项目编号" width="170" />
        <el-table-column prop="projectName" label="项目名称" min-width="220" />
        <el-table-column label="结算送审金额（元）" width="180">
          <template #default="{ row }">{{ money(row.settleAmount) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">{{ datetime(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="router.push(`/projects/${row.id}`)">进入作业</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import { dashboardApi } from '@/api'
import { datetime, money, percent, statusTag } from '@/utils/format'

const router = useRouter()
const data = ref<Record<string, any>>({})
const barRef = ref<HTMLDivElement>()
const pieRef = ref<HTMLDivElement>()
let barChart: echarts.ECharts | undefined
let pieChart: echarts.ECharts | undefined

const cards = computed(() => [
  { label: '预审项目', value: data.value.projectTotal ?? 0, sub: `核查中 ${data.value.checking ?? 0} · 已核查 ${data.value.checked ?? 0}`, color: '#1f6feb' },
  { label: '待复核疑点', value: data.value.issuePending ?? 0, sub: `高风险 ${data.value.issueHigh ?? 0} 条`, color: '#f56c6c' },
  { label: '平均清单可解析率', value: percent(data.value.avgParseRate), sub: '目标 ≥ 95%', color: '#67c23a' },
  { label: '平均定额前三命中率', value: percent(data.value.avgHitRate), sub: '目标 ≥ 85%', color: '#e6a23c' },
])

const recent = computed<any[]>(() => data.value.recentProjects ?? [])

async function load() {
  data.value = await dashboardApi.stats()
  renderCharts()
}

function renderCharts() {
  if (barRef.value) {
    barChart = barChart ?? echarts.init(barRef.value)
    barChart.setOption({
      tooltip: { trigger: 'axis' },
      grid: { left: 40, right: 20, top: 30, bottom: 30 },
      xAxis: { type: 'category', data: ['高风险', '中风险', '低风险'] },
      yAxis: { type: 'value', minInterval: 1 },
      series: [
        {
          name: '疑点数量',
          type: 'bar',
          barWidth: 48,
          itemStyle: { color: (params: any) => ['#f56c6c', '#e6a23c', '#909399'][params.dataIndex] },
          data: [data.value.issueHigh ?? 0, data.value.issueMedium ?? 0, data.value.issueLow ?? 0],
        },
      ],
    })
  }
  if (pieRef.value) {
    pieChart = pieChart ?? echarts.init(pieRef.value)
    const dist = data.value.issueTypeDist ?? {}
    pieChart.setOption({
      tooltip: { trigger: 'item' },
      legend: { bottom: 0 },
      series: [
        {
          name: '疑点类型',
          type: 'pie',
          radius: ['45%', '68%'],
          label: { formatter: '{b}: {c}' },
          data: Object.keys(dist).map((key) => ({ name: key, value: dist[key] })),
        },
      ],
    })
  }
}

function resize() {
  barChart?.resize()
  pieChart?.resize()
}

onMounted(() => {
  load()
  window.addEventListener('resize', resize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  barChart?.dispose()
  pieChart?.dispose()
})
</script>

<style scoped>
.stat-card .label {
  color: #909399;
  font-size: 13px;
}
.stat-card .value {
  font-size: 26px;
  font-weight: 600;
  margin-top: 8px;
}
.stat-card .sub {
  color: #a8abb2;
  font-size: 12px;
  margin-top: 6px;
}
.charts {
  margin-top: 16px;
}
.chart {
  height: 300px;
}
.recent {
  margin-top: 16px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
