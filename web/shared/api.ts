export type Role = 'STUDENT' | 'ADMIN'
export type Grade = { id: string; name: string }
export type Account = {
  id: string
  username: string
  role: Role
  gradeId: string | null
  status: 'ENABLED' | 'DISABLED' | 'DELETED'
  version: number
}
type Payload<T> = { code: number | string; message: string; data: T }

export class ApiError extends Error {
  constructor(public status: number, message: string, public code?: string | number) {
    super(message)
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    const timeout = AbortSignal.timeout(5000)
    response = await fetch('/api/v1' + path, { ...init, signal: init?.signal ? AbortSignal.any([timeout, init.signal]) : timeout })
  } catch {
    throw new ApiError(0, '暂时无法连接考试服务，请稍后重试。')
  }
  let body: Payload<T>
  try {
    body = await response.json()
  } catch {
    throw new ApiError(response.status, '考试服务暂不可用，请稍后重试。')
  }
  if (!response.ok || body.code !== 0) {
    throw new ApiError(response.status, body.message || '操作失败，请稍后重试。', body.code)
  }
  return body.data
}

export function read<T>(path: string, signal?: AbortSignal) {
  return request<T>(path, { signal })
}

export async function write<T>(method: string, path: string, data?: object, signal?: AbortSignal) {
  const csrf = await read<{ token: string; headerName: string }>('/auth/csrf', signal)
  if (data instanceof FormData) return request<T>(path, { method, headers: { [csrf.headerName]: csrf.token }, body: data, signal })
  const headers: Record<string, string> = { [csrf.headerName]: csrf.token }
  if (data) headers['Content-Type'] = 'application/json'
  return request<T>(path, { method, headers, body: data && JSON.stringify(data), signal })
}
