#!/usr/bin/env python3
"""Instala o cofre do projeto e guarda recuperação somente em arquivos locais privados."""
import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import shlex
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--host', default='ochannel@192.168.1.80')
args = parser.parse_args()
repo = Path(__file__).resolve().parents[1]
private = Path.home() / '.config/ativacao-homelab/openbao-ativacao'
private.mkdir(parents=True, exist_ok=True, mode=0o700)
os.chmod(private, 0o700)
os.umask(0o077)
ns = 'openbao-ativacao'
kube = 'KUBECONFIG=$HOME/.kube/config kubectl '


def remote(command, data=None, codes=(0,), sensitive=False):
    result = subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=8', args.host, command], input=data, text=True, capture_output=True)
    if result.returncode not in codes:
        detail = 'operação com credenciais; detalhes omitidos' if sensitive else result.stderr.strip()
        raise RuntimeError(f'Operação remota falhou ({result.returncode}): {detail}')
    return result.stdout


def apply(obj):
    data = obj if isinstance(obj, str) else json.dumps(obj)
    return remote(kube + 'apply --server-side --field-manager=ativacao-bootstrap -f -', data, sensitive=True)


def write_private(path, value):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as output:
        json.dump(value, output, indent=2)
        output.write('\n')
        output.flush()
        os.fsync(output.fileno())


def openssl(*argv):
    subprocess.run(['openssl', *argv], cwd=private, capture_output=True, check=True)


apply((repo / 'k8s/bootstrap/namespaces.yaml').read_text())
if not (private / 'ca.crt').exists():
    if (private / 'ca.key').exists():
        raise RuntimeError('CA parcialmente criada; preservar e revisar arquivos privados antes de continuar.')
    openssl('req', '-x509', '-newkey', 'rsa:3072', '-nodes', '-keyout', 'ca.key', '-out', 'ca.crt', '-days', '3650', '-subj', '/CN=OpenBao Ativacao Homelab CA', '-addext', 'basicConstraints=critical,CA:TRUE', '-addext', 'keyUsage=critical,keyCertSign,cRLSign')
if not (private / 'tls.crt').exists():
    openssl('req', '-newkey', 'rsa:3072', '-nodes', '-keyout', 'tls.key', '-out', 'tls.csr', '-subj', '/CN=openbao.openbao-ativacao.svc')
    (private / 'tls.ext').write_text('subjectAltName=DNS:openbao,DNS:openbao.openbao-ativacao.svc,DNS:openbao.openbao-ativacao.svc.cluster.local,DNS:localhost,IP:127.0.0.1\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n')
    openssl('x509', '-req', '-in', 'tls.csr', '-CA', 'ca.crt', '-CAkey', 'ca.key', '-CAcreateserial', '-out', 'tls.crt', '-days', '365', '-extfile', 'tls.ext')
apply({'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': 'openbao-tls', 'namespace': ns}, 'type': 'kubernetes.io/tls', 'data': {key: base64.b64encode((private / file).read_bytes()).decode() for key, file in [('tls.crt', 'tls.crt'), ('tls.key', 'tls.key'), ('ca.crt', 'ca.crt')]}})
apply((repo / 'k8s/bootstrap/openbao-auth.yaml').read_text())
rendered = subprocess.run(['kubectl', 'kustomize', str(repo / 'k8s/openbao')], text=True, capture_output=True, check=True).stdout
apply(rendered)
# API HTTPS com CA validada, atravessando somente um túnel SSH local.
import atexit
import socket
import ssl
import urllib.request
import urllib.error
with socket.socket() as listener:
    listener.bind(('127.0.0.1', 0))
    port = listener.getsockname()[1]
tunnel = subprocess.Popen(['ssh', '-o', 'BatchMode=yes', '-o', 'ExitOnForwardFailure=yes', '-L', f'127.0.0.1:{port}:127.0.0.1:{port}', args.host, kube + f'port-forward -n {ns} svc/openbao {port}:8200 --address=127.0.0.1'], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
def close_tunnel():
    tunnel.terminate()
    try:
        tunnel.wait(timeout=5)
    except subprocess.TimeoutExpired:
        tunnel.kill()
atexit.register(close_tunnel)
context = ssl.create_default_context(cafile=str(private / 'ca.crt'))
http = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context))
root_token = None

def api(path, body=None, method=None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {'Content-Type': 'application/json'}
    if root_token:
        headers['X-Vault-Token'] = root_token
    req = urllib.request.Request(f'https://127.0.0.1:{port}/v1/' + path, data=data, headers=headers, method=method or ('POST' if body is not None else 'GET'))
    try:
        with http.open(req, timeout=15) as response:
            content = response.read()
            return json.loads(content) if content else {}
    except urllib.error.HTTPError as error:
        raise RuntimeError(f'API OpenBao {path}: HTTP {error.code}; conteúdo omitido.') from None

for attempt in range(60):
    try:
        status = api('sys/seal-status')
        break
    except (urllib.error.URLError, RuntimeError):
        if tunnel.poll() is not None:
            raise RuntimeError('Túnel para o OpenBao não iniciou.')
        time.sleep(1)
else:
    raise RuntimeError('OpenBao não respondeu em 60 tentativas.')
recovery_file = private / 'recuperacao.json'
if not status['initialized']:
    if recovery_file.exists():
        raise RuntimeError('Arquivo de recuperação já existe, mas o volume não está inicializado. Não sobrescrever chaves.')
    initialized = api('sys/init', {'secret_shares': 3, 'secret_threshold': 2}, method='PUT')
    write_private(recovery_file, initialized)
    print('Chaves e token inicial salvos em arquivo privado antes do unseal.', flush=True)
if not recovery_file.exists():
    raise RuntimeError('Cofre já inicializado e arquivo de recuperação ausente. Interrompido sem alterar o cofre.')
recovery = json.loads(recovery_file.read_text())
if (recovery_file.stat().st_mode & 0o777) != 0o600:
    raise RuntimeError('Arquivo de recuperação precisa ter permissão 0600.')
if status['sealed']:
    api('sys/unseal', {'reset': True})
    for key in recovery.get('unseal_keys_b64', recovery.get('keys_base64', [])):
        status = api('sys/unseal', {'key': key})
        if not status['sealed']:
            break
if status['sealed']:
    raise RuntimeError('Não foi possível desselar o novo cofre.')
print('Novo OpenBao desselado; material de recuperação salvo.', flush=True)
root_token = recovery['root_token']
mounts = api('sys/mounts')['data']
if 'secret/' not in mounts:
    api('sys/mounts/secret', {'type': 'kv', 'options': {'version': '2'}})
auths = api('sys/auth')['data']
if 'kubernetes/' not in auths:
    api('sys/auth/kubernetes', {'type': 'kubernetes'})
ca = json.loads(remote(kube + 'get configmap kube-root-ca.crt -n ' + ns + ' -o json'))['data']['ca.crt']
api('auth/kubernetes/config', {'kubernetes_host': 'https://kubernetes.default.svc', 'kubernetes_ca_cert': ca, 'disable_iss_validation': True})
api('sys/policies/acl/ativacao-hml', {'policy': 'path "secret/data/ativacao/hml/config" { capabilities = ["read"] }'}, method='PUT')
api('auth/kubernetes/role/ativacao-hml', {'bound_service_account_names': ['openbao-reader'], 'bound_service_account_namespaces': ['ativacao-hml'], 'token_policies': ['ativacao-hml'], 'audience': 'openbao', 'token_ttl': '1h'})
app_file = private / 'aplicacao.json'
if not app_file.exists():
    write_private(app_file, {'DB_USER': 'ativacao_hml', 'DB_PASSWORD': secrets.token_hex(32), 'AUTH_ISSUER': 'http://localhost/local', 'AUTH_AUDIENCE': 'ativacao-api', 'AUTH_HMAC_KEY': secrets.token_hex(32)})
app = json.loads(app_file.read_text())
# Identificadores fixos e senha hexadecimal gerada localmente; nenhum dado externo é interpolado no SQL.
if app['DB_USER'] != 'ativacao_hml' or len(app['DB_PASSWORD']) != 64 or not all(c in '0123456789abcdef' for c in app['DB_PASSWORD']):
    raise RuntimeError('Arquivo de credenciais tem formato inesperado; não executar SQL.')
sql = """select format('CREATE ROLE %%I LOGIN PASSWORD %%L', 'ativacao_hml', '%s') where not exists (select from pg_roles where rolname='ativacao_hml') \\gexec
select format('CREATE DATABASE %%I OWNER %%I', 'ativacao_hml', 'ativacao_hml') where not exists (select from pg_database where datname='ativacao_hml') \\gexec
REVOKE CONNECT ON DATABASE ativacao_hml FROM PUBLIC;
GRANT CONNECT ON DATABASE ativacao_hml TO ativacao_hml;
""" % app['DB_PASSWORD']
psql = 'sh -c ' + shlex.quote('PGPASSWORD="$POSTGRES_PASSWORD" exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres')
remote(kube + 'exec -i -n databases-hml postgresql-0 -- ' + psql, sql, sensitive=True)
api('secret/data/ativacao/hml/config', {'data': app}, method='PUT')
apply({'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': {'name': 'openbao-ca', 'namespace': 'ativacao-hml'}, 'data': {'ca.crt': (private / 'ca.crt').read_text()}})
apply((repo / 'k8s/bootstrap/external-secrets.yaml').read_text())
remote(kube + 'wait -n ativacao-hml --for=condition=Ready secretstore/openbao --timeout=90s')
remote(kube + 'wait -n ativacao-hml --for=condition=Ready externalsecret/ativacao-config --timeout=90s')
print('Credenciais próprias do banco e da API sincronizadas por External Secrets.', flush=True)
print('Arquivo de recuperação: ' + str(recovery_file), flush=True)

# Credencial de pull da plataforma já existente: copiar sem exibir seu conteúdo.
pull = json.loads(remote(kube + 'get secret -n pedidobasecode-hml ghcr-pull -o json', sensitive=True))
apply({'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': 'ghcr-pull', 'namespace': 'ativacao-hml'}, 'type': pull['type'], 'data': pull['data']})
print('Credencial de leitura do registry preparada no namespace da aplicação.', flush=True)
