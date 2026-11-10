import { h } from 'vue'
import { RouterLink, type Router } from 'vue-router'
import { ApiError, read, type Account, type Role } from './api'

export function protect(router: Router, role: Role) {
  router.beforeEach(async (route) => {
    if (!route.meta.protected) return true
    try {
      const account = await read<Account>('/me')
      return account.role === role || { name: 'denied' }
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) return { name: 'login' }
      return { name: 'unavailable' }
    }
  })
}

export function messagePage(title: string, text?: string) {
  return {
    render: () => h('main', [
      h('h1', title),
      text && h('p', { role: 'alert' }, text),
      h(RouterLink, { to: '/' }, () => '返回入口'),
    ]),
  }
}
