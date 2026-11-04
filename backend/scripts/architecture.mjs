import ts from 'typescript';
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const allowed = { domain: ['domain'], application: ['domain', 'application'], interfaces: ['domain', 'application', 'interfaces'], infrastructure: ['domain', 'infrastructure'] };
export async function architecture(root) {
  const violations = [];
  async function walk(dir) {
    for (const item of await readdir(dir, { withFileTypes: true })) {
      const file = path.join(dir, item.name);
      if (item.isDirectory()) { await walk(file); continue; }
      if (!file.endsWith('.ts')) continue;
      const relative = path.relative(root, file);
      const layer = relative.split(path.sep)[0];
      if (!allowed[layer]) continue; // Explicit composition roots live directly in src/.
      const source = await readFile(file, 'utf8');
      const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true);
      function visit(node) {
        let specifier;
        if ((ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) && node.moduleSpecifier && ts.isStringLiteral(node.moduleSpecifier)) specifier = node.moduleSpecifier.text;
        if (ts.isCallExpression(node) && (node.expression.kind === ts.SyntaxKind.ImportKeyword || node.expression.getText(ast) === 'require')) {
          if (!node.arguments[0] || !ts.isStringLiteral(node.arguments[0])) violations.push(`${relative}: nonliteral dynamic import`);
          else specifier = node.arguments[0].text;
        }
        if (specifier) {
          const target = specifier.startsWith('.') ? path.relative(root, path.resolve(path.dirname(file), specifier)).split(path.sep)[0] : specifier;
          const builtin = specifier.startsWith('node:') && !['node:fs', 'node:fs/promises', 'node:net', 'node:http', 'node:https', 'node:child_process'].includes(specifier);
          if (!allowed[layer].includes(target) && !builtin && !(layer === 'infrastructure' || (layer === 'interfaces' && ['fastify', '@fastify/cookie', '@fastify/cors'].includes(specifier)))) violations.push(`${relative}: ${layer} -> ${specifier}`);
        }
        if (layer !== 'infrastructure' && (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node) || ts.isTemplateExpression(node))) {
          const literal = ts.isTemplateExpression(node) ? node.head.text : node.text;
          if (/\b(SELECT\s+.+\s+FROM|INSERT\s+INTO|UPDATE\s+\w+\s+SET|DELETE\s+FROM|CREATE\s+TABLE)\b/is.test(literal)) violations.push(`${relative}: SQL outside infrastructure`);
        }
        ts.forEachChild(node, visit);
      }
      visit(ast);
    }
  }
  await walk(root);
  return violations;
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const violations = await architecture(path.resolve(process.argv[2] || 'src'));
  if (violations.length) { console.error(violations.join('\n')); process.exitCode = 1; }
  else console.log('CT01 architecture: passed');
}
