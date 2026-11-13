<script setup lang="ts">
import { ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { api, session, verifySession } from './context'
import { router } from './router'
import { errorText } from './api/client'
import { roleName } from './shared/format'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import DiscardPrompt from './components/DiscardPrompt.vue'
import { askDiscard, settleDiscard } from './shared/discard'
import { hasUnconfirmedCreate, clearPendingCreates } from './orders/pending'
const route = useRoute()
const leaving = ref(false)
async function logout() {
  if (leaving.value) return
  if (session.current.value && hasUnconfirmedCreate(session.current.value.account.id) && !await askDiscard('仍有未确认的下单记录。退出将清除本标签页恢复记录，不会取消服务端订单；请先到订单列表核对，确认后才退出。')) return
  leaving.value = true
  const epoch = session.generation.value
  let message = '已退出当前会话。'
  try { await api('/api/v1/auth/logout', { method: 'POST' }) }
  catch (e) { message = `仅本地已清除，服务端会话可能仍有效。${errorText(e)}` }
  finally {
    if (epoch === session.generation.value) { session.clear(message); await router.push('/login') }
    leaving.value = false
  }
}
watch(() => session.current.value, value => {
  if (!value && route.meta.auth && !leaving.value) void router.replace({ path: '/login', query: { returnTo: route.fullPath } })
})
watch(session.generation, () => { settleDiscard(false); clearPendingCreates() }, { flush: 'sync' })
void verifySession().catch(() => { session.notice.value = '暂时无法核实登录状态；可浏览公开商家，或稍后重试登录验证。' })
</script>
<template>
  <el-config-provider :locale="zhCn"><div class="site-shell">
    <header class="site-header">
      <div class="header-inner">
        <RouterLink to="/shops" class="brand" aria-label="CampusLife 首页"><img src="/favicon.svg" width="38" height="38" alt=""><span>CampusLife<small>星海校园生活</small></span></RouterLink>
        <nav aria-label="主导航"><RouterLink to="/shops">发现商家</RouterLink><RouterLink v-if="session.verified.value" to="/workspace">我的工作区</RouterLink><RouterLink v-if="session.verified.value && session.current.value?.account.role === 'STUDENT'" to="/orders">我的订单</RouterLink><template v-if="session.verified.value && session.current.value?.account.role === 'MERCHANT'"><RouterLink to="/merchant/shop">店铺管理</RouterLink><RouterLink to="/merchant/orders">订单任务</RouterLink></template><template v-if="session.verified.value && session.current.value?.account.role === 'ADMIN'"><RouterLink to="/admin/shops">入驻审核</RouterLink><RouterLink to="/admin/orders">订单核实</RouterLink><RouterLink to="/admin/accounts">账号管理</RouterLink><RouterLink to="/admin/reviews">评价处理</RouterLink></template><RouterLink v-if="!session.current.value" to="/apply">商家入驻</RouterLink></nav>
        <div class="account-nav" v-if="session.current.value && session.verified.value"><RouterLink to="/me">{{ session.current.value.account.nickname }}</RouterLink><span class="role-pill">{{ roleName[session.current.value.account.role] }}</span><el-button text :loading="leaving" @click="logout">退出</el-button></div>
        <RouterLink v-else to="/login" class="login-link">登录 / 注册</RouterLink>
      </div>
    </header>
    <main class="main-container">
      <el-alert v-if="session.notice.value" :title="session.notice.value" type="info" :closable="false" class="notice" role="status" />
      <RouterView v-if="!route.meta.auth || session.current.value" :key="`${session.generation.value}:${route.path}`" />
    </main>
    <footer class="site-footer"><span>CampusLife · 星海大学虚构校区</span><span>学习与演示项目 · 支付与退款均为模拟操作</span></footer>
    <DiscardPrompt />
  </div></el-config-provider>
</template>
