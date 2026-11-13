<script setup lang="ts">
defineProps<{ message: string }>()
</script>
<template><el-alert v-if="message" :title="message" type="error" :closable="false" show-icon class="notice" role="alert" /></template>
