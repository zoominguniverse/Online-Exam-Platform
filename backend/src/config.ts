export function config(env: NodeJS.ProcessEnv) {
  const required = ['DATABASE_URL', 'APP_ORIGIN', 'CORS_ALLOWED_ORIGINS', 'PRIVATE_STORAGE_ROOT', 'OUTBOX_ENABLED', 'ACCESS_TOKEN_SECRET', 'LOG_LEVEL', 'RATE_LIMIT_MAX', 'HOST', 'PORT'];
  const bad = required.filter(key => !env[key]);
  function url(key: string, protocols: string[]) {
    try { const u = new URL(env[key]!); if (!protocols.includes(u.protocol)) bad.push(key); return u; }
    catch { bad.push(key); return null; }
  }
  url('DATABASE_URL', ['postgres:', 'postgresql:']);
  const origin = url('APP_ORIGIN', ['https:']);
  const origins = (env.CORS_ALLOWED_ORIGINS || '').split(',');
  for (const v of origins) { try { const u = new URL(v); if (u.protocol !== 'https:' || u.origin !== v) bad.push('CORS_ALLOWED_ORIGINS'); } catch { bad.push('CORS_ALLOWED_ORIGINS'); } }
  if (origin && (origin.origin !== env.APP_ORIGIN || !origins.includes(origin.origin))) bad.push('APP_ORIGIN');
  if (!['true', 'false'].includes(env.OUTBOX_ENABLED!)) bad.push('OUTBOX_ENABLED');
  if (!['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent'].includes(env.LOG_LEVEL!)) bad.push('LOG_LEVEL');
  for (const key of ['PORT', 'RATE_LIMIT_MAX']) if (!/^\d+$/.test(env[key] || '') || Number(env[key]) < 1 || (key === 'PORT' && Number(env[key]) > 65535)) bad.push(key);
  if ((env.ACCESS_TOKEN_SECRET || '').length < 32) bad.push('ACCESS_TOKEN_SECRET');
  if (!(env.PRIVATE_STORAGE_ROOT || '').startsWith('/')) bad.push('PRIVATE_STORAGE_ROOT');
  if (bad.length) throw new Error(`缺失或格式错误的配置键：${[...new Set(bad)].join(', ')}`);
  return { databaseUrl: env.DATABASE_URL!, appOrigin: env.APP_ORIGIN!, origins, secret: env.ACCESS_TOKEN_SECRET!, host: env.HOST!, port: Number(env.PORT), logLevel: env.LOG_LEVEL!, rateLimit: Number(env.RATE_LIMIT_MAX), outbox: env.OUTBOX_ENABLED === 'true' };
}
export type Config = ReturnType<typeof config>;
