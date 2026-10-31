#!/usr/bin/env python3
"""Obtém um JWT do Keycloak usando a credencial exclusiva da empresa."""
import argparse
import base64
import getpass
import json
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('client_id', choices=['empresa-a', 'empresa-b'])
parser.add_argument('--server', default='https://keycloak.olivetree.dev.br')
parser.add_argument('--realm', default='desafio-ativacao')
parser.add_argument('--scope', default='activation:read activation:write')
parser.add_argument('--ca-cert', help='Certificado PEM da CA do home lab, se necessário')
args = parser.parse_args()
if urllib.parse.urlsplit(args.server).scheme != 'https':
    parser.error('--server precisa usar HTTPS')
secret = getpass.getpass(f'Client secret de {args.client_id}: ')
credentials = base64.b64encode(
    (urllib.parse.quote_plus(args.client_id) + ':' + urllib.parse.quote_plus(secret)).encode()
).decode()
request = urllib.request.Request(
    args.server.rstrip('/') + '/realms/' + urllib.parse.quote(args.realm, safe='')
    + '/protocol/openid-connect/token',
    data=urllib.parse.urlencode({'grant_type': 'client_credentials', 'scope': args.scope}).encode(),
    headers={'Authorization': 'Basic ' + credentials,
             'Content-Type': 'application/x-www-form-urlencoded'},
)
try:
    with urllib.request.urlopen(request, context=ssl.create_default_context(cafile=args.ca_cert), timeout=15) as response:
        token = json.load(response)['access_token']
except urllib.error.HTTPError as error:
    sys.exit(f'Keycloak recusou a solicitação: HTTP {error.code}. Confira a credencial e os escopos.')
except (urllib.error.URLError, OSError):
    sys.exit('Não foi possível conectar ao Keycloak. Confira o endereço, a rede e a CA (--ca-cert).')
print(token)
