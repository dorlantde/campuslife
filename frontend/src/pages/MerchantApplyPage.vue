<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink, onBeforeRouteLeave } from 'vue-router'
import { api, session } from '../context'
import type { Category, MerchantApplication } from '../api/types'
import { useLatestRead, useWrite } from '../merchant/useRequests'
import ErrorNotice from '../components/ErrorNotice.vue'
import { askDiscard } from '../shared/discard'
const categories = ref<Category[]>([])
const read = useLatestRead(), write = useWrite()
const result = ref<MerchantApplication>()
const form = reactive({ username: '', password: '', nickname: '', categoryId: '', shopName: '', locationText: '', description: '' })
const dirty = computed(() => !result.value && Object.values(form).some(Boolean))
function load() { void read.run(signal => api<Category[]>('/api/v1/shop-categories', { auth: false, signal }), value => { categories.value = value }) }
async function apply() {
  if (session.current.value) return
  const value = await write.run(() => api<MerchantApplication>('/api/v1/merchant-applications', { auth: false, method: 'POST', body: { ...form } }), '申请已提交，等待管理员审核。')
  form.password = ''
  if (value) result.value = value
}
onBeforeRouteLeave(async () => !write.busy.value && (!dirty.value || await askDiscard('离开会丢弃未保存的申请内容。若提交结果不确定，请先登录核对。')))
onMounted(load)
</script>
<template>
  <section class="management-heading"><span class="eyebrow">JOIN CAMPUSLIFE</span><h1>商家入驻申请</h1><p>创建独立商家账号，填写店铺资料，审核后即可配置校园服务。</p></section>
  <section v-if="session.current.value" class="surface panel"><el-alert title="申请会创建新的商家账号。请先退出当前账号，再填写入驻申请。" type="info" :closable="false" /><RouterLink to="/workspace" class="text-link">返回当前工作区 →</RouterLink></section>
  <section v-else-if="result" class="surface panel"><h2>申请已提交</h2><p>店铺编号：{{ result.shopId }} · 待审核</p><p class="muted">尚未自动登录。请使用刚设置的商家账号登录，查看审核结果；被拒后可以修改资料重新提交。</p><RouterLink :to="{ path: '/login', query: { returnTo: '/merchant/shop' } }" class="text-link">登录查看申请 →</RouterLink></section>
  <form v-else class="surface panel management-form application-form" @submit.prevent="apply">
    <ErrorNotice :message="read.error.value || write.error.value" />
    <el-button v-if="read.error.value" @click="load" :loading="read.loading.value">重新读取分类</el-button>
    <fieldset :disabled="write.busy.value || write.blocked.value">
      <legend>商家账号</legend><div class="form-grid">
        <div><label for="apply-user">商家用户名</label><el-input id="apply-user" v-model="form.username" autocomplete="username" :maxlength="32" placeholder="4～32位字母、数字或下划线" required /></div>
        <div><label for="apply-nickname">店主昵称</label><el-input id="apply-nickname" v-model="form.nickname" autocomplete="nickname" required /></div>
        <div class="span-two"><label for="apply-password">商家密码</label><el-input id="apply-password" v-model="form.password" type="password" autocomplete="new-password" show-password required /><p class="field-help">至少8字符、UTF-8不超过72字节；不修改密码中的空白，不会保存到浏览器草稿。</p></div>
      </div>
      <legend>店铺资料</legend><div class="form-grid">
        <div><label for="apply-category">店铺分类</label><select id="apply-category" v-model="form.categoryId" required><option value="" disabled>请选择分类</option><option v-for="c in categories" :key="c.id" :value="c.id">{{ c.name }}</option></select></div>
        <div><label for="apply-name">店铺名称</label><el-input id="apply-name" v-model="form.shopName" required /></div>
        <div class="span-two"><label for="apply-location">店铺位置</label><el-input id="apply-location" v-model="form.locationText" placeholder="例如：星海校园服务中心101室" required /></div>
        <div class="span-two"><label for="apply-description">店铺介绍</label><el-input id="apply-description" v-model="form.description" type="textarea" :rows="3" /></div>
      </div>
      <el-button type="primary" native-type="submit" :loading="write.busy.value" :disabled="!categories.length || write.blocked.value">提交入驻申请</el-button>
    </fieldset>
    <div v-if="write.blocked.value" class="quiet-note">申请可能已经提交，请先用该商家账号登录查看。请勿反复创建账号。<RouterLink to="/login" class="text-link">前往登录核对 →</RouterLink></div>
  </form>
</template>
