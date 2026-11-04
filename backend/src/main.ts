import { config } from './config.ts';
import { buildServer } from './interfaces/http.ts';
import { database } from './infrastructure/database.ts';
import { credentials } from './infrastructure/credentials.ts';
import { Postgres } from './infrastructure/postgres.ts';
import { Auth } from './application/auth.ts';
import { Exams } from './application/exams.ts';
import { Accounts } from './application/accounts.ts';
// Composition root is the only place that wires interface and infrastructure.
try {
  const settings = config(process.env);
  const db = database(settings.databaseUrl);
  await db.ready();
  const repo=new Postgres(db.pool);
  const crypto=await credentials(settings.secret);
  const auth=new Auth(repo,crypto);
  const app = buildServer({ origins: settings.origins, rateLimit: settings.rateLimit, ready: db.ready,security:auth,auth,accounts:new Accounts(repo,crypto),exams:new Exams(repo) });
  for (const signal of ['SIGTERM', 'SIGINT']) process.once(signal, async () => { await app.close(); await db.close(); });
  await app.listen({ host: settings.host, port: settings.port });
  console.log(JSON.stringify({ timestamp: new Date().toISOString(), level: 'info', module: 'startup', message: 'API ready; registered resources require authorization' }));
} catch (error) {
  console.error(error instanceof Error && error.message.startsWith('缺失或格式错误的配置键') ? error.message : '启动失败，请检查配置及数据库连接。');
  process.exit(1);
}
