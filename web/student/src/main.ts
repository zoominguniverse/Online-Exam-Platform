import { createApp, h } from 'vue'
import { createRouter, createWebHistory, RouterView } from 'vue-router'
import StatusPage from '../../shared/StatusPage.vue'
import AuthPage from '../../shared/AuthPage.vue'
import { messagePage, protect } from '../../shared/router'
import StudentHome from './StudentHome.vue'
import PapersPage from './PapersPage.vue'
import AssignmentsPage from './AssignmentsPage.vue'
import AttemptPage from './AttemptPage.vue'
import '../../shared/identity.css'

const auth = { role: 'STUDENT', destination: '/home' }
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', component: StatusPage, props: { title: '在线考试 · 学生 Web 端' } },
    { path: '/login', name: 'login', component: AuthPage, props: auth },
    { path: '/register', name: 'register', component: AuthPage, props: { ...auth, registration: true } },
    { path: '/home', component: StudentHome, meta: { protected: true } },
    { path: '/papers', component: PapersPage, meta: { protected: true } },
    { path: '/tasks', component: AssignmentsPage, meta: { protected: true } },
    { path: '/tasks/:id', component: AssignmentsPage, props: true, meta: { protected: true } },
    { path: '/attempts/:id', component: AttemptPage, props: true, meta: { protected: true } },
    { path: '/forbidden', name: 'denied', component: messagePage('没有访问权限', '此入口仅供学生使用。') },
    { path: '/unavailable', name: 'unavailable', component: messagePage('服务暂不可用', '请稍后重试。') },
    { path: '/:pathMatch(.*)*', component: messagePage('页面不存在') },
  ],
})

protect(router, 'STUDENT')
createApp({ render: () => h(RouterView, null, { default: ({ Component, route }) => h(Component, { key: route.name ?? route.path }) }) })
  .use(router).mount('#app')
