#!/usr/bin/env python3
"""Build-artifact smoke test in disposable Compose services; optional Newman runs.
Never connects to the developer's normal Compose project or database volumes.
"""
import argparse
import base64
import hashlib
import json
import secrets
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def yaml_str(v, indent=0):
    pad = "  " * indent
    if isinstance(v, dict):
        lines = []
        for k, val in v.items():
            lines.append(f"{pad}{k}:")
            lines.append(yaml_str(val, indent + 1))
        return "\n".join(lines)
    if isinstance(v, list):
        return "\n".join(f"{pad}- {yaml_str(item, indent + 1).strip()}" for item in v)
    if isinstance(v, bool):
        return f"{pad}{str(v).lower()}"
    return f"{pad}{v}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', default='hookrelay-review:local')
    parser.add_argument('--newman', help='Optional path to an externally installed Newman binary')
    args = parser.parse_args()
    key_a = 'hr_live_verifyaa_' + secrets.token_hex(24)
    key_b = 'hr_live_verifybb_' + secrets.token_hex(24)
    password = secrets.token_hex(24)
    project_a = '22222222-2222-4222-8222-222222222222'
    project_b = str(uuid.uuid4())
    compose_name = 'hookrelay-verify-' + secrets.token_hex(4)
    with tempfile.TemporaryDirectory(prefix='hookrelay-verify-') as directory:
        temp = Path(directory)
        compose = {
            'services': {
                'postgres': {
                    'image': 'postgres:18.7-alpine',
                    'environment': {'POSTGRES_DB': 'hookrelay', 'POSTGRES_USER': 'hookrelay', 'POSTGRES_PASSWORD': password},
                    'healthcheck': {'test': ['CMD-SHELL', 'pg_isready -U hookrelay -d hookrelay'], 'interval': '2s', 'timeout': '3s', 'retries': 30}},
                'rabbitmq': {
                    'image': 'rabbitmq:4.4.0-management',
                    'environment': {'RABBITMQ_DEFAULT_USER': 'hookrelay', 'RABBITMQ_DEFAULT_PASS': password},
                    'healthcheck': {'test': ['CMD', 'rabbitmq-diagnostics', '-q', 'ping'], 'interval': '5s', 'timeout': '5s', 'retries': 30}},
                'backend': {
                    'image': args.image,
                    'ports': ['127.0.0.1::8080'],
                    'environment': {'SPRING_PROFILES_ACTIVE': 'local', 'HOOKRELAY_DEV_API_KEY': key_a,
                        'SIGNING_ENCRYPTION_KEY': base64.b64encode(secrets.token_bytes(32)).decode(),
                        'SPRING_DATASOURCE_URL': 'jdbc:postgresql://postgres:5432/hookrelay',
                        'SPRING_DATASOURCE_USERNAME': 'hookrelay', 'SPRING_DATASOURCE_PASSWORD': password,
                        'SPRING_RABBITMQ_HOST': 'rabbitmq', 'SPRING_RABBITMQ_USERNAME': 'hookrelay', 'SPRING_RABBITMQ_PASSWORD': password},
                    'depends_on': {'postgres': {'condition': 'service_healthy'}, 'rabbitmq': {'condition': 'service_healthy'}}}}}
        config = temp / 'compose.yml'
        config.write_text(yaml_str(compose))
        config.chmod(0o600)
        command = ['docker', 'compose', '--project-name', compose_name, '-f', str(config)]
        def run(*arguments, **kwargs):
            return subprocess.run(command + list(arguments), check=True, text=True, **kwargs)
        try:
            run('up', '-d', '--wait', '--wait-timeout', '180')
            address = run('port', 'backend', '8080', capture_output=True).stdout.strip()
            base = 'http://' + address
            def request(method, path, key=None, body=None):
                headers = {'Content-Type': 'application/json'}
                if key:
                    headers['X-Api-Key'] = key
                data = None if body is None else json.dumps(body).encode()
                req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
                try:
                    response = urllib.request.urlopen(req, timeout=10)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    payload = response.read()
                    return response.status, response.headers, json.loads(payload) if payload else None
            deadline = time.monotonic() + 90
            while True:
                try:
                    if request('GET', '/actuator/health')[0] == 200:
                        break
                except (urllib.error.URLError, TimeoutError, ConnectionError):
                    pass
                if time.monotonic() >= deadline:
                    raise RuntimeError('Backend health deadline exceeded')
                time.sleep(0.5)
            sql = f"""
INSERT INTO projects(id,organization_id,name,environment)
VALUES ('{project_b}','11111111-1111-4111-8111-111111111111','Verification B','development');
INSERT INTO api_keys(id,project_id,name,key_prefix,key_hash)
VALUES ('{uuid.uuid4()}','{project_b}','Verification B','verifybb','{hashlib.sha256(key_b.encode()).hexdigest()}');
"""
            run('exec', '-T', 'postgres', 'psql', '-U', 'hookrelay', '-d', 'hookrelay', '-v', 'ON_ERROR_STOP=1', input=sql, capture_output=True)
            assert request('GET', '/v1/endpoints')[0] == 401
            status, headers, endpoint = request('POST', '/v1/endpoints', key_a, {'url':'https://8.8.8.8/compose-check'})
            assert status == 201 and headers['Cache-Control'] == 'no-store'
            path = '/v1/endpoints/' + endpoint['id']
            assert request('GET', path, key_a)[0] == 200
            assert request('GET', path, key_b)[0] == 404
            assert request('POST', '/v1/events', key_a, {'eventType':'test.compose','data':{'synthetic':True}})[0] == 202
            assert request('GET', '/actuator/metrics')[0] == 404
            assert request('DELETE', path, key_a)[0] == 204
            print('Compose image smoke: health, auth, signing, tenant isolation, ingestion, management exposure passed.', flush=True)
            if args.newman:
                newman = shutil.which(args.newman)
                if not newman:
                    raise RuntimeError('Requested Newman executable not found')
                env_file = temp / 'postman.json'
                env_file.write_text(json.dumps({'values':[{'key':key,'value':value,'enabled':True} for key,value in {'base_url':base,'api_key':key_a,'other_api_key':key_b,'project_id':project_a}.items()]}))
                env_file.chmod(0o600)
                for collection in ['HookRelay%20Smoke%20Tests', 'HookRelay%20API']:
                    subprocess.run([newman, 'run', str(ROOT/'postman'/f'{collection}.postman_collection.json'), '-e', str(env_file), '--bail', '--timeout-request', '15000'], check=True)
        finally:
            run('down', '--volumes', '--remove-orphans', '--timeout', '10')


if __name__ == '__main__':
    main()
