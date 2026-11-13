<script setup lang="ts">
import { discardRequest, settleDiscard } from '../shared/discard'
</script>
<template>
  <el-dialog :model-value="!!discardRequest" title="切换前核对草稿" width="min(480px, calc(100vw - 32px))" :close-on-click-modal="false" @update:model-value="value => { if (!value) settleDiscard(false) }">
    <p>{{ discardRequest?.message }}</p>
    <template #footer><el-button @click="settleDiscard(false)">保留草稿</el-button><el-button type="danger" @click="settleDiscard(true)">丢弃草稿并继续</el-button></template>
  </el-dialog>
</template>
