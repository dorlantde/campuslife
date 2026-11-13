<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../context'
import type { Category, MerchantShop } from '../api/types'
import { copy, shopDraft, shopPatch } from './forms'
import { useLatestRead, useWrite } from './useRequests'
import ErrorNotice from '../components/ErrorNotice.vue'
import { errorText } from '../api/client'
const props = defineProps<{ initial: MerchantShop }>()
const emit = defineEmits<{ saved: [shop: MerchantShop]; dirty: [value: boolean]; busy: [value: boolean] }>()
const base = ref(copy(props.initial)), draft = reactive(shopDraft(base.value))
const categoryId = ref(base.value.category.id)
const categories = ref<Category[]>([])
const categoryError = ref('')
const read = useLatestRead(), write = useWrite()
const editable = computed(() => base.value.approvalStatus === 'APPROVED' && base.value.memberStatus === 'ACTIVE')
const resubmitting = computed(() => base.value.approvalStatus === 'REJECTED')
const dirty = computed(() => JSON.stringify(draft) !== JSON.stringify(shopDraft(base.value)) || categoryId.value !== base.value.category.id)
watch(dirty, value => emit('dirty', value), { immediate: true })
watch(write.busy, value => emit('busy', value))
function accept(value: MerchantShop) { base.value = copy(value); Object.assign(draft, shopDraft(value)); categoryId.value = value.category.id; emit('saved', value) }
async function reload() { await read.run(signal => api<MerchantShop>('/api/v1/merchant/shop', { signal }), value => { accept(value); write.reset() }) }
async function save() {
  const result = await write.run(() => resubmitting.value
    ? api<MerchantShop>('/api/v1/merchant/shop/resubmit', { method: 'POST', body: { expectedVersion: base.value.version, categoryId: categoryId.value, shopName: draft.name, locationText: draft.locationText, description: draft.description } })
    : api<MerchantShop>('/api/v1/merchant/shop', { method: 'PATCH', body: shopPatch(base.value, draft) }), resubmitting.value ? '资料已重新提交，等待审核。' : '经营资料已保存。')
  if (result) accept(result)
}
async function loadCategories() { categoryError.value = ''; try { categories.value = await api<Category[]>('/api/v1/shop-categories', { auth: false }) } catch (e) { categoryError.value = errorText(e) } }
onMounted(loadCategories)
</script>
<template>
  <section class="surface panel management-form">
    <div class="section-title"><h2>{{ resubmitting ? '修改并重新提交申请' : '店铺资料' }}</h2><el-button @click="reload" :disabled="write.busy.value" :loading="read.loading.value">重新载入（覆盖草稿）</el-button></div>
    <el-alert v-if="base.approvalStatus === 'PENDING'" title="申请正在审核中。暂不能修改经营配置，可重新读取店铺查看结果。" type="info" :closable="false" class="notice" />
    <el-alert v-if="base.approvalReason" :title="`审核说明：${base.approvalReason}`" type="warning" :closable="false" class="notice" />
    <el-alert v-if="base.approvalStatus === 'SUSPENDED' || base.memberStatus === 'DISABLED'" title="当前不能维护经营配置。旧订单的处理权限以订单入口核验为准。" type="warning" :closable="false" class="notice" />
    <ErrorNotice :message="read.error.value || write.error.value" /><el-alert v-if="write.message.value" :title="write.message.value" type="success" :closable="false" class="notice" role="status" />
    <div v-if="resubmitting && categoryError" class="inline-error" role="alert">{{ categoryError }} <el-button @click="loadCategories">重新读取分类</el-button></div>
    <form @submit.prevent="save"><fieldset :disabled="(!editable && !resubmitting) || write.busy.value || write.blocked.value || read.loading.value">
      <div class="form-grid"><div class="span-two"><label for="shop-name">店铺名称</label><el-input id="shop-name" v-model="draft.name" required /></div>
        <div class="span-two"><label for="shop-location">店铺位置</label><el-input id="shop-location" v-model="draft.locationText" required /></div>
        <div v-if="resubmitting" class="span-two"><label for="resubmit-category">申请分类</label><select id="resubmit-category" v-model="categoryId" required><option v-if="!categories.some(c => c.id === base.category.id)" :value="base.category.id">{{ base.category.name }}（原分类）</option><option v-for="c in categories" :key="c.id" :value="c.id">{{ c.name }}</option></select></div>
        <div class="span-two"><label for="shop-description">店铺介绍</label><el-input id="shop-description" v-model="draft.description" type="textarea" :rows="4" /></div>
        <label v-if="editable" class="checkbox-line span-two"><input v-model="draft.businessEnabled" type="checkbox">开启营业开关</label>
      </div>
      <p v-if="editable" class="field-help">营业开关与营业时间分别设置。开启不等于已具备下单条件；名称和介绍修改后直接生效。</p>
      <el-button v-if="editable || resubmitting" type="primary" native-type="submit" :loading="write.busy.value" :disabled="write.blocked.value">{{ resubmitting ? '重新提交入驻申请' : '保存店铺资料' }}</el-button>
    </fieldset></form>
  </section>
</template>
