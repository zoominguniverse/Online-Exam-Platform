export const uuid = { type: 'string', format: 'uuid' };
export const object = (properties: Record<string, unknown>, required = Object.keys(properties)) => ({ type: 'object', additionalProperties: false, properties, required });
const version = { type: 'integer', minimum: 1 };
const answers = { type: 'array', maxItems: 200, items: object({ itemId: uuid, text: { type: 'string', maxLength: 10000 } }) };
const key = { type: 'string', minLength: 1, maxLength: 128, pattern: '^[A-Za-z0-9_.:-]+$' };
const listQuery = (kind: string) => object({
  page: { type: 'string', pattern: '^[1-9][0-9]{0,8}$' },
  pageSize: { type: 'string', pattern: '^[1-9][0-9]{0,8}$' },
  sort: { type: 'string', enum: kind === 'papers' ? ['createdAt', 'title'] : kind==='users'?['createdAt','username']:['createdAt'] },
  direction: { type: 'string', enum: ['asc', 'desc'] },
  ...(kind === 'papers' ? { type: { type: 'string', enum: ['fixed', 'timed'] } } : {}),
  ...(kind === 'attempts' ? { status: { type: 'string', enum: ['running', 'submitted', 'completed'] } } : {}),
  ...(kind==='users'?{role:{type:'string',enum:['student','admin']},status:{type:'string',enum:['active','disabled','deleted']},gradeId:uuid,username:{type:'string',pattern:'^[A-Za-z0-9_][A-Za-z0-9_.-]{2,31}$'}}:{}),
}, []);
export type Route = { method: 'GET' | 'POST' | 'PUT' | 'DELETE'; url: string; operation: string; roles: readonly string[]; public?: boolean; csrf?: boolean; conditionalAdmin?:boolean; conditionalVersion?:boolean; query?: ReturnType<typeof object>; body?: Record<string,unknown>; keyed?: boolean };
const username = { type:'string',pattern:'^[A-Za-z0-9_][A-Za-z0-9_.-]{2,31}$' };
const password = { type:'string',minLength:12,maxLength:64 };
const role={type:'string',enum:['student','admin']};
const gradeId={anyOf:[uuid,{type:'null'}]};
export const routes: Route[] = [
  { method:'POST',url:'/api/v1/users',operation:'registerStudent',roles:['anonymous','admin'],public:true,conditionalAdmin:true,keyed:true,body:{oneOf:[object({username,password,gradeId:uuid}),object({username,password,role,gradeId})]} },
  { method:'GET',url:'/api/v1/users',operation:'listUsers',roles:['admin'],query:listQuery('users') },
  { method:'GET',url:'/api/v1/users/:id',operation:'getUser',roles:['admin'] },
  { method:'PUT',url:'/api/v1/users/:id',operation:'updateUser',roles:['admin'],keyed:true,body:object({version,username,role,gradeId}) },
  { method:'POST',url:'/api/v1/users/:id/suspensions',operation:'suspendUser',roles:['admin'],keyed:true,body:object({version}) },
  { method:'DELETE',url:'/api/v1/users/:id',operation:'deleteUser',roles:['admin'],conditionalVersion:true },
  { method:'POST',url:'/api/v1/sessions',operation:'createSession',roles:['anonymous'],public:true,keyed:true,body:object({username,password}) },
  { method:'POST',url:'/api/v1/sessions/refreshes',operation:'refreshSession',roles:['anonymous'],public:true,csrf:true,body:object({}) },
  { method:'DELETE',url:'/api/v1/sessions/:id',operation:'deleteSession',roles:['student','admin'],csrf:true },
  { method:'POST',url:'/api/v1/users/:id/password-changes',operation:'changePassword',roles:['student','admin'],keyed:true,body:object({currentPassword:password,password}) },
  { method: 'GET', url: '/api/v1/papers', operation: 'listPapers', roles: ['student'], query: listQuery('papers') },
  { method: 'GET', url: '/api/v1/papers/:id', operation: 'getPaper', roles: ['student'] },
  { method: 'POST', url: '/api/v1/papers/:id/attempts', operation: 'startPaper', roles: ['student'], keyed: true, body: object({}, []) },
  { method: 'POST', url: '/api/v1/tasks/:id/attempts', operation: 'startTask', roles: ['student'], keyed: true, body: object({}, []) },
  { method: 'GET', url: '/api/v1/attempts', operation: 'listAttempts', roles: ['student'], query: listQuery('attempts') },
  { method: 'GET', url: '/api/v1/attempts/:id', operation: 'getAttempt', roles: ['student'] },
  { method: 'PUT', url: '/api/v1/attempts/:id/answers', operation: 'saveAnswers', roles: ['student'], keyed: true, body: object({ version, answers }) },
  { method: 'POST', url: '/api/v1/attempts/:id/submissions', operation: 'submitAttempt', roles: ['student'], keyed: true, body: object({ version, answers }) },
  { method: 'POST', url: '/api/v1/attempts/:id/gradings', operation: 'gradeAttempt', roles: ['student'], keyed: true, body: object({ version, scores: { type: 'array', minItems: 1, maxItems: 200, items: object({ itemId: uuid, score: { type: 'string', pattern: '^(0|[1-9][0-9]{0,5})(\\.[0-9]{1,2})?$' } }) } }) },
  { method: 'GET', url: '/api/v1/mistakes', operation: 'listMistakes', roles: ['student'], query: listQuery('mistakes') },
  { method: 'GET', url: '/api/v1/mistakes/:id', operation: 'getMistake', roles: ['student'] },
];
export function schema(route: Route) {
  return {
    ...(route.url.includes(':id') ? { params: object({ id: uuid }) } : {}),
    ...(route.query ? { querystring: route.query } : {}),
    ...(route.body ? { body: route.body } : {}),
    ...((route.keyed || route.csrf || route.conditionalVersion) ? { headers: { type: 'object', required: [...(route.keyed?['idempotency-key']:[]),...(route.csrf?['x-csrf-token']:[]),...(route.conditionalVersion?['if-match']:[])], properties: { ...(route.keyed?{'idempotency-key':key}:{}),...(route.csrf?{'x-csrf-token':{type:'string',minLength:1,maxLength:256}}:{}),...(route.conditionalVersion?{'if-match':{type:'string',pattern:'^"[1-9][0-9]{0,8}"$'}}:{}) } } } : {}),
  };
}
export function query(raw: Record<string, string>) {
  return { page: Number(raw.page || 1), pageSize: Math.min(Number(raw.pageSize || 20), 100), sort: raw.sort || 'createdAt', direction: (raw.direction || 'desc') as 'asc' | 'desc', ...(raw.status ? { status: raw.status } : {}), ...(raw.type ? { type: raw.type } : {}),...(raw.role?{role:raw.role}:{}),...(raw.gradeId?{gradeId:raw.gradeId}:{}),...(raw.username?{username:raw.username}:{}) };
}
