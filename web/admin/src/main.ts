import { createApp, h } from 'vue'
import { createRouter, createWebHistory, RouterView } from 'vue-router'
import StatusPage from '../../shared/StatusPage.vue'
import AuthPage from '../../shared/AuthPage.vue'
import { messagePage, protect } from '../../shared/router'
import AccountList from './AccountList.vue'
import AccountEditor from './AccountEditor.vue'
import SubjectsPage from './SubjectsPage.vue'
import QuestionsPage from './QuestionsPage.vue'
import QuestionEditor from './QuestionEditor.vue'
import PapersPage from './PapersPage.vue'
import PaperEditor from './PaperEditor.vue'
import TasksPage from './TasksPage.vue'
import AppRoot from '../../shared/AppRoot.vue'
import RecordsPage from '../../shared/RecordsPage.vue'
import ReviewPage from '../../shared/ReviewPage.vue'
import ProfilePage from '../../shared/ProfilePage.vue'
import MessagesPage from '../../shared/MessagesPage.vue'
import ActivityPage from '../../shared/ActivityPage.vue'
import DashboardPage from './DashboardPage.vue'
import '../../shared/identity.css'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', component: StatusPage, props: { title: '在线考试 · 管理员端' } },
    { path: '/login', name: 'login', component: AuthPage, props: { role: 'ADMIN', destination: '/dashboard' } },
    { path: '/dashboard', component: DashboardPage, meta: { protected: true } },
    { path: '/attempts', component: RecordsPage, props: { admin: true }, meta: { protected: true } },
    { path: '/attempts/:id', component: ReviewPage, props: route => ({ id: route.params.id, admin: true }), meta: { protected: true } },
    { path: '/profile', component: ProfilePage, meta: { protected: true } },
    { path: '/messages', component: MessagesPage, props: { admin: true }, meta: { protected: true } },
    { path: '/messages/new', component: MessagesPage, props: { admin: true, compose: true }, meta: { protected: true } },
    { path: '/messages/:id', component: MessagesPage, props: route => ({ id: route.params.id, admin: true }), meta: { protected: true } },
    { path: '/activities', alias: '/logs', component: ActivityPage, props: { admin: true }, meta: { protected: true } },
    { path: '/users/students', alias: '/users/student', component: AccountList, props: { role: 'STUDENT' }, meta: { protected: true } },
    { path: '/users/admins', alias: '/users/admin', component: AccountList, props: { role: 'ADMIN' }, meta: { protected: true } },
    { path: '/users/new/:kind(students|admins)', component: AccountEditor, props: true, meta: { protected: true } },
    { path: '/users/:id/edit', component: AccountEditor, props: true, meta: { protected: true } },
    { path: '/subjects', component: SubjectsPage, meta: { protected: true } },
    { path: '/subjects/:id/edit', component: SubjectsPage, props: true, meta: { protected: true } },
    { path: '/questions', component: QuestionsPage, meta: { protected: true } },
    { path: '/questions/new', component: QuestionEditor, meta: { protected: true } },
    { path: '/questions/:id/edit', component: QuestionEditor, props: true, meta: { protected: true } },
    { path: '/papers', component: PapersPage, meta: { protected: true } },
    { path: '/papers/new', component: PaperEditor, meta: { protected: true } },
    { path: '/papers/:id/edit', component: PaperEditor, props: true, meta: { protected: true } },
    { path: '/tasks', component: TasksPage, meta: { protected: true } },
    { path: '/tasks/:id/edit', component: TasksPage, props: true, meta: { protected: true } },
    { path: '/forbidden', name: 'denied', component: messagePage('没有访问权限', '此入口仅供管理员使用。') },
    { path: '/unavailable', name: 'unavailable', component: messagePage('服务暂不可用', '请稍后重试。') },
    { path: '/:pathMatch(.*)*', component: messagePage('页面不存在') },
  ],
})

protect(router, 'ADMIN')
createApp(AppRoot, { admin: true })
  .use(router).mount('#app')
