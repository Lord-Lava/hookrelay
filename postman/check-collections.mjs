import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { Script } from 'node:vm';

const root = new URL('./', import.meta.url);
let requests = 0;
for (const name of readdirSync(root).filter(name => name.endsWith('.postman_collection.json'))) {
  const collection = JSON.parse(readFileSync(new URL(name.replaceAll('%', '%25'), root), 'utf8'));
  const visit = items => {
    for (const item of items) {
      if (item.item) { visit(item.item); continue; }
      requests++;
      const request = item.request;
      const url = typeof request.url === 'string' ? request.url : request.url.raw;
      assert(url.startsWith('{{base_url}}/'), `${item.name}: unexpected request host`);
      if (request.body?.mode === 'raw') JSON.parse(request.body.raw);
      for (const event of item.event ?? []) {
        new Script(event.script.exec.join('\n'), { filename: `${name}: ${item.name}` });
      }
      assert(item.event?.some(event => event.listen === 'test'), `${item.name}: missing assertions`);
      if (item.name.startsWith('Cross-project')) {
        assert(url.includes('{{foreignEndpointId}}'), `${item.name}: must use an existing foreign resource`);
        assert(request.header.some(h => h.key === 'X-Api-Key' && h.value === '{{api_key}}'));
      }
    }
  };
  for (const event of collection.event ?? []) new Script(event.script.exec.join('\n'));
  visit(collection.item);
}
console.log(`Validated JSON bodies and scripts for ${requests} collection requests.`);
