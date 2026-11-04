export type Row = Record<string, any>;
export type Principal = { id: string; role: 'student' | 'admin'; sessionId: string };
export interface Repository {
  transaction<T>(work: (repo: Repository) => Promise<T>): Promise<T>;
  userByName(name: string): Promise<Row | null>;
  userById(id: string): Promise<Row | null>;
  createUser(data: Row): Promise<Row>;
  lockAdministrators(): Promise<void>;
  activeAdministratorCount(): Promise<number>;
  updateUser(id: string, version: number, data: Row): Promise<Row>;
  listUsers(query: ListQuery): Promise<Row>;
  gradeExists(id: string): Promise<boolean>;
  createSession(data: Row): Promise<void>;
  session(id: string): Promise<Row | null>;
  revokeSession(id: string): Promise<void>;
  revokeUser(id: string, passwordHash?: string): Promise<void>;
  refreshByHash(hash: string): Promise<Row | null>;
  consumeRefresh(id: string): Promise<void>;
  addRefresh(data: Row): Promise<void>;
  paper(id: string): Promise<Row | null>;
  task(id: string): Promise<Row | null>;
  eligible(taskId: string, ownerId: string): Promise<boolean>;
  attempt(id: string): Promise<Row | null>;
  taskAttempt(taskId: string, ownerId: string): Promise<Row | null>;
  createAttempt(data: Row): Promise<Row>;
  updateAttempt(id: string, version: number, data: Row): Promise<Row>;
  saveAnswers(id: string, answers: Row[]): Promise<void>;
  answers(id: string): Promise<Row[]>;
  mistake(id: string): Promise<Row | null>;
  createGrading(attempt: Row, scores: Row[], total: string, actor: string): Promise<void>;
  list(kind: 'papers' | 'attempts' | 'mistakes', owner: string, query: ListQuery, now?: Date): Promise<Row>;
  lockKey(scope: string): Promise<void>;
  idempotency(scope: string, key: string): Promise<Row | null>;
  remember(scope: string, key: string, digest: string, result: Row, now: Date): Promise<void>;
  audit(actor: string, action: string, objectId: string, requestId: string): Promise<void>;
  enqueue(actor: string, action: string, objectId: string): Promise<void>;
}
export type ListQuery = { page: number; pageSize: number; sort: string; direction: 'asc' | 'desc'; status?: string; type?: string; role?: string; gradeId?: string; username?: string };
export class Failure extends Error {
  code: string;
  data: Row | null;
  constructor(code: string, message: string, data: Row | null = null) { super(message); this.code = code; this.data = data; }
}
export const errors: Record<string, number> = {
  PARAM_INVALID: 400, AUTHN_INVALID: 401, AUTHN_REQUIRED: 401, AUTHN_SESSION_REVOKED: 401,
  AUTHZ_FORBIDDEN: 403, RESOURCE_NOT_FOUND: 404, BIZ_IDEMPOTENCY_CONFLICT: 409,
  BIZ_VERSION_CONFLICT: 409, BIZ_STATE_CONFLICT: 409, BIZ_UNAVAILABLE: 409,
  BIZ_DEADLINE_REACHED: 409, BIZ_USERNAME_EXISTS: 409, BIZ_LAST_ADMIN: 409, DEP_UNAVAILABLE: 503,
  RATE_LIMITED: 429, INTERNAL_FAILURE: 500,
};
export const notFound = () => new Failure('RESOURCE_NOT_FOUND', '资源不存在，请返回列表重试。');
export function validatePassword(value: string) {
  const length=[...value].length;
  if(length<12 || length>64 || Buffer.byteLength(value,'utf8')>72)throw new Failure('PARAM_INVALID','密码须为 12–64 个字符且 UTF-8 不超过 72 字节。');
}
export function owned(row: Row | null, actor: Principal): asserts row is Row {
  if (!row || row.owner_id !== actor.id) throw notFound();
}
export function cents(value: unknown): number {
  if (typeof value !== 'string' || !/^(0|[1-9]\d{0,5})(\.\d{1,2})?$/.test(value))
    throw new Failure('PARAM_INVALID', '分数须为最多两位小数的非负十进制字符串。');
  const [whole, part = ''] = value.split('.');
  return Number(whole) * 100 + Number(part.padEnd(2, '0'));
}
export const decimal = (value: number) => `${Math.floor(value / 100)}.${String(value % 100).padStart(2, '0')}`;
export function validateAnswers(items: Row[], answers: Row[]) {
  const seen = new Set<string>();
  for (const answer of answers) {
    const q = items.find(i => i.id === answer.itemId);
    if (!q || seen.has(answer.itemId) || typeof answer.text !== 'string' || answer.text.length > 10000)
      throw new Failure('PARAM_INVALID', '答案题号重复、不属于本卷或文本超限。');
    seen.add(answer.itemId);
    const text = answer.text;
    if (text && ((q.type === 'single' && !q.options.includes(text)) ||
      (q.type === 'boolean' && !['true', 'false'].includes(text)) ||
      (q.type === 'multiple' && (() => {
        try { const a = JSON.parse(text); return !Array.isArray(a) || !a.length || new Set(a).size !== a.length || a.some(v => !q.options.includes(v)); }
        catch { return true; }
      })()))) throw new Failure('PARAM_INVALID', '答案格式与题型不匹配。');
  }
}
