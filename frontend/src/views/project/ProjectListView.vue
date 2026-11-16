<template>
  <el-card shadow="never">
    <template #header>
      <div class="header-row">
        <span>预审项目管理</span>
        <el-button type="primary" disabled>新建预审项目（A 模块开发中）</el-button>
      </div>
    </template>
    <el-table :data="[]" empty-text="骨架阶段：项目列表接口在 A4 开发接入">
      <el-table-column prop="projectNo" label="项目编号" />
      <el-table-column prop="projectName" label="项目名称" />
      <el-table-column prop="engType" label="工程类型" />
      <el-table-column prop="settleAmount" label="结算送审金额" />
      <el-table-column prop="status" label="状态" />
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
// 项目管理（A4/A5）：列表 + 资料上传 + 解析状态跟踪
</script>

<style scoped>
.header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
