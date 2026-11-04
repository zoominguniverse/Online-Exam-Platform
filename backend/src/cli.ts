import { config } from './config.ts';
import { database } from './infrastructure/database.ts';
import { migrate } from './infrastructure/migrate.ts';
import { seedExam } from './infrastructure/seed.ts';
import { Postgres, SYSTEM } from './infrastructure/postgres.ts';
import { credentials } from './infrastructure/credentials.ts';
import { randomUUID } from 'node:crypto';

let db: ReturnType<typeof database> | undefined;
try {
  const settings=config(process.env); db=database(settings.databaseUrl);
  const repo=new Postgres(db.pool); const command=process.argv[2];
  if (command==='migrate') await migrate(db.pool);
  else if (command==='rollback') {
    if (process.env.DISPOSABLE_DATABASE!=='true') throw new Error('rollback requires DISPOSABLE_DATABASE=true; foundation rollback deletes data');
    await migrate(db.pool,'down');
  } else if (command==='seed') {
    const target=new URL(settings.databaseUrl);
    if (process.env.DEV_SEED_ALLOWED!=='true' || !['127.0.0.1','localhost'].includes(target.hostname) || !['/exam_dev','/exam_test'].includes(target.pathname)) throw new Error('seed requires explicit local development database and DEV_SEED_ALLOWED=true');
    const data=await seedExam(db.pool); console.log(JSON.stringify({gradeId:data.gradeId,papers:data.papers}));
  } else if (command==='create-user') {
    const {BOOTSTRAP_USERNAME:username,BOOTSTRAP_PASSWORD:password,BOOTSTRAP_ROLE:role,BOOTSTRAP_GRADE_ID:gradeId}=process.env;
    if (!username || !/^[A-Za-z0-9_][A-Za-z0-9_.-]{2,31}$/.test(username) || !password || [...password].length<12 || [...password].length>64 || Buffer.byteLength(password)>72 || !['student','admin'].includes(role||'') || (role==='student' && (!gradeId || !await repo.gradeExists(gradeId)))) throw new Error('invalid bootstrap configuration keys');
    const crypto=await credentials(settings.secret); const hash=await crypto.hash(password);
    const user=await repo.transaction(async r=>{
      const user=await r.createUser({id:randomUUID(),username,passwordHash:hash,role,gradeId:gradeId||null,createdBy:SYSTEM});
      await r.audit(SYSTEM,'user.bootstrapped',user.id,randomUUID());return user;
    });
    console.log(JSON.stringify({id:user.id,role:user.role}));
  } else if (command==='outbox-replay') {
    const id=process.env.EVENT_ID;
    if (!id || !/^[0-9a-f-]{36}$/.test(id)) throw new Error('EVENT_ID is required');
    await repo.transaction(async r=>{
      const p=r as Postgres;
      const row=await p.one("UPDATE outbox_events SET status='pending',attempts=0,available_at=CURRENT_TIMESTAMP,lease_token=NULL,lease_until=NULL,last_error=NULL WHERE id=$1 AND status='failed' RETURNING id",[id]);
      if (!row) throw new Error('event is not in failed queue');
      await r.audit(SYSTEM,'outbox.replayed',id,randomUUID());
    });
  } else throw new Error('unknown command');
  console.log(JSON.stringify({code:'OK',command}));
} catch (error) {
  console.error(error instanceof Error && error.message.startsWith('缺失或格式错误的配置键')?error.message:'命令失败，请检查配置键、命令、数据库权限和数据状态。');
  process.exitCode=1;
} finally { if (db) await db.close(); }
