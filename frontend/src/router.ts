import { createRouter, createWebHistory } from 'vue-router'
import { session, verifySession } from './context'
import type { Role } from './api/types'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/shops' },
    { path: '/shops', component: () => import('./pages/ShopsPage.vue') },
    { path: '/shops/:shopId([1-9][0-9]*)', component: () => import('./pages/ShopPage.vue') },
    { path: '/shops/:shopId([1-9][0-9]*)/print/:serviceId([1-9][0-9]*)', component: () => import('./pages/PrintPage.vue'), meta: { auth: true, role: 'STUDENT' } },
    { path: '/shops/:shopId([1-9][0-9]*)/book/:serviceId([1-9][0-9]*)', component: () => import('./pages/BookingPage.vue'), meta: { auth: true, role: 'STUDENT' } },
    { path: '/orders', component: () => import('./pages/OrdersPage.vue'), meta: { auth: true, role: 'STUDENT' } },
    { path: '/orders/:orderId([1-9][0-9]*)', component: () => import('./pages/OrderPage.vue'), meta: { auth: true, role: 'STUDENT' } },
    { path: '/merchant/orders', component: () => import('./pages/OrdersPage.vue'), props: { merchant: true }, meta: { auth: true, role: 'MERCHANT' } },
    { path: '/merchant/orders/:orderId([1-9][0-9]*)', component: () => import('./pages/OrderPage.vue'), props: { merchant: true }, meta: { auth: true, role: 'MERCHANT' } },
    { path: '/login', component: () => import('./pages/AuthPage.vue') },
    { path: '/register', component: () => import('./pages/AuthPage.vue') },
    { path: '/apply', component: () => import('./pages/MerchantApplyPage.vue') },
    { path: '/me', component: () => import('./pages/ProfilePage.vue'), meta: { auth: true } },
    { path: '/workspace', component: () => import('./pages/WorkspacePage.vue'), meta: { auth: true } },
    { path: '/merchant', redirect: '/merchant/shop' },
    { path: '/merchant/shop', component: () => import('./pages/MerchantShopPage.vue'), meta: { auth: true, role: 'MERCHANT' } },
    { path: '/admin', redirect: '/admin/shops' },
    { path: '/admin/shops', component: () => import('./pages/AdminShopsPage.vue'), meta: { auth: true, role: 'ADMIN' } },
    { path: '/admin/shops/:shopId([1-9][0-9]*)', component: () => import('./pages/AdminShopPage.vue'), meta: { auth: true, role: 'ADMIN' } },
    { path: '/admin/orders', component: () => import('./pages/AdminOrderPage.vue'), meta: { auth: true, role: 'ADMIN' } },
    { path: '/admin/orders/:orderId([1-9][0-9]*)', component: () => import('./pages/AdminOrderPage.vue'), meta: { auth: true, role: 'ADMIN' } },
    { path: '/admin/accounts', component: () => import('./pages/AdminAccountsPage.vue'), meta: { auth: true, role: 'ADMIN' } },
    { path: '/admin/reviews', component: () => import('./pages/AdminReviewsPage.vue'), meta: { auth: true, role: 'ADMIN' } },
    { path: '/session', component: () => import('./pages/StatusPage.vue') },
    { path: '/forbidden', component: () => import('./pages/StatusPage.vue') },
    { path: '/:pathMatch(.*)*', component: () => import('./pages/StatusPage.vue') },
  ],
  scrollBehavior(to, from, saved) { return saved || (to.path === from.path ? {} : { top: 0 }) },
})
router.beforeEach(async to => {
  if (!to.meta.auth) return true
  try {
    if (!await verifySession()) return { path: '/login', query: { returnTo: to.fullPath } }
  } catch { return { path: '/session', query: { returnTo: to.fullPath } } }
  if (to.meta.role && session.current.value?.account.role !== to.meta.role as Role) return '/forbidden'
  return true
})
