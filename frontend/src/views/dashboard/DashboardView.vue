<template>
  <div>
    <el-row :gutter="16">
      <el-col v-for="card in cards" :key="card.label" :span="6">
        <el-card shadow="hover">
          <div class="label">{{ card.label }}</div>
          <div class="value">{{ card.value }}</div>
        </el-card>
      </el-col>
    </el-row>
    <el-card shadow="never" class="chart-card">
      <template #header>预审指标看板（骨架占位：解析率/命中率/疑点分布）</template>
      <div ref="chartRef" class="chart" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import * as echarts from 'echarts'

const cards = [
  { label: '进行中项目', value: 0 },
  { label: '待复核疑点', value: 0 },
  { label: '清单可解析率', value: '-' },
  { label: '定额前三命中率', value: '-' },
]

const chartRef = ref<HTMLDivElement>()

onMounted(() => {
  const chart = echarts.init(chartRef.value!)
  chart.setOption({
    tooltip: {},
    xAxis: { type: 'category', data: ['高', '中', '低'] },
    yAxis: { type: 'value' },
    series: [{ type: 'bar', data: [0, 0, 0], name: '疑点分布' }],
  })
  window.addEventListener('resize', () => chart.resize())
})
</script>

<style scoped>
.label {
  color: #909399;
  font-size: 13px;
}
.value {
  font-size: 26px;
  font-weight: 600;
  margin-top: 8px;
}
.chart-card {
  margin-top: 16px;
}
.chart {
  height: 320px;
}
</style>
