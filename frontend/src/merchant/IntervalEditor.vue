<script setup lang="ts">
import type { TimeInterval } from '../api/types'
const props = defineProps<{ modelValue: TimeInterval[]; prefix: string; disabled?: boolean; aligned?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: TimeInterval[]] }>()
function change(index: number, field: keyof TimeInterval, value: string) {
  emit('update:modelValue', props.modelValue.map((row, i) => i === index ? { ...row, [field]: value } : row))
}
</script>
<template>
  <div class="interval-editor">
    <div v-for="(row, index) in modelValue" :key="index" class="interval-row">
      <label :for="`${prefix}-${index}-start`" class="sr-only">{{ prefix }}第{{ index + 1 }}段开始</label>
      <input :id="`${prefix}-${index}-start`" type="time" :step="aligned ? 900 : 60" :value="row.startTime" :disabled="disabled" required @input="change(index, 'startTime', ($event.target as HTMLInputElement).value)">
      <span>至</span>
      <label :for="`${prefix}-${index}-end`" class="sr-only">{{ prefix }}第{{ index + 1 }}段结束</label>
      <input :id="`${prefix}-${index}-end`" type="time" :step="aligned ? 900 : 60" :value="row.endTime" :disabled="disabled" required @input="change(index, 'endTime', ($event.target as HTMLInputElement).value)">
      <el-button type="danger" text :disabled="disabled" :aria-label="`移除${prefix}第${index + 1}段`" @click="emit('update:modelValue', modelValue.filter((_, i) => i !== index))">移除</el-button>
    </div>
    <span v-if="!modelValue.length" class="muted">未配置时段</span>
    <el-button text :disabled="disabled || modelValue.length >= 4" @click="emit('update:modelValue', [...modelValue, { startTime: '', endTime: '' }])">添加{{ prefix }}时段</el-button>
  </div>
</template>
