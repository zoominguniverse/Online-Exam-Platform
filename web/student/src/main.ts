import { createApp, h } from 'vue'
import { createRouter, createWebHistory, RouterView } from 'vue-router'
import StatusPage from '../../shared/StatusPage.vue'
import AuthPage from '../../shared/AuthPage.vue'
import { messagePage, protect } from '../../shared/router'
import StudentHome from './StudentHome.vue'
import PapersPage from './PapersPage.vue'
import AssignmentsPage from './AssignmentsPage.vue'
import AttemptPage from './AttemptPage.vue'
import AppRoot from '../../shared/AppRoot.vue'
import RecordsPage from '../../shared/RecordsPage.vue'
import ReviewPage from '../../shared/ReviewPage.vue'
import ProfilePage from '../../shared/ProfilePage.vue'
import MessagesPage from '../../shared/MessagesPage.vue'
import ActivityPage from '../../shared/ActivityPage.vue'
import MistakesPage from './MistakesPage.vue'
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
    { path: '/attempts/:id', alias: '/exam/:id', component: AttemptPage, props: true, meta: { protected: true } },
    { path: '/attempts/:id/review', alias: '/attempts/:id/grade', component: ReviewPage, props: true, meta: { protected: true } },
    { path: '/records', component: RecordsPage, meta: { protected: true } },
    { path: '/mistakes', component: MistakesPage, meta: { protected: true } },
    { path: '/mistakes/:id', component: MistakesPage, props: true, meta: { protected: true } },
    { path: '/profile', component: ProfilePage, meta: { protected: true } },
    { path: '/messages', component: MessagesPage, meta: { protected: true } },
    { path: '/messages/:id', component: MessagesPage, props: true, meta: { protected: true } },
    { path: '/activities', component: ActivityPage, meta: { protected: true } },
    { path: '/forbidden', name: 'denied', component: messagePage('没有访问权限', '此入口仅供学生使用。') },
    { path: '/unavailable', name: 'unavailable', component: messagePage('服务暂不可用', '请稍后重试。') },
    { path: '/:pathMatch(.*)*', component: messagePage('页面不存在') },
  ],
})

protect(router, 'STUDENT')
createApp(AppRoot)
  .use(router).mount('#app')
