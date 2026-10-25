# Plano — Banco, ambientes e deploy (OCI Always Free, Postgres)

> Status: **plano, nada criado na OCI ainda.** Não tenho acesso à sua conta OCI; os passos
> da seção 3 são seus (ou, se instalar a OCI CLI, eu aviso o que vou criar antes de criar).
> Escopo POC: sem testes automatizados.
> Cobre o deploy dos **dois** projetos: backend (`~/projects/langgraph`) e
> frontend (`~/projects/ucp-agent-web`).

## 1. O LangGraph precisa de banco?

**Para rodar, não. Para homologação, sim.**

- Hoje o checkpointer é `MemorySaver`: o estado vive na memória da JVM. Reiniciou o backend,
  toda thread pausada em `payment` some e o `/approve` falha com `Missing Checkpoint!`.
- Servidor reinicia (atualização, queda), então a homologação usa checkpointer persistente.
- **Só o checkpointer precisa de banco.** Catálogo e pedido ficam na Shopify; não criamos
  tabelas de negócio.
- Troca pequena: um bean `BaseCheckpointSaver` no lugar de `new MemorySaver()` em
  `OrderGraphConfig`. Nodes e edges não mudam.

**Decisão: Postgres** com `langgraph4j-postgres-saver` (1.9.7, mesma versão do core).

| Ambiente | Checkpointer |
|---|---|
| local | `MemorySaver` (padrão) |
| homologação (OCI) | `PostgresSaver` |

Escolha por propriedade: `app.checkpointer=memory|postgres`.

> A confirmar na implementação: se o saver cria as tabelas sozinho ou se preciso rodar um DDL.
> Verifico no código do saver antes de configurar.

## 2. Dois projetos, dois repositórios

```
~/projects/langgraph/        # BACKEND  (Spring Boot)
├── pom.xml, mvnw, src/
├── Dockerfile
├── deploy/                  # compose + Caddyfile da homologação (ver §5)
├── PLANO.md, PLANO-INFRA.md
└── application-local.yml    # gitignored

~/projects/ucp-agent-web/    # FRONTEND (Vite + React + assistant-ui)
├── package.json, src/
└── PLANO.md
```

Regras:

- O front só conhece o back por **uma URL relativa** (`/api`). Nenhum código ou DTO compartilhado.
- Cada projeto tem build, segredos e ciclo de deploy próprios.
- O back não serve o front. Em dev o Vite faz proxy de `/api`; em homologação o Caddy faz.
- O `deploy/` fica no backend porque o compose sobe o ambiente inteiro (Caddy + front + back +
  Postgres); o front entra como pasta de arquivos estáticos (`dist/`).

## 3. Passo a passo na OCI (Always Free)

### 3.1 Postgres no Always Free: como

O serviço gerenciado **OCI Database with PostgreSQL não faz parte do Always Free** (é pago;
confira na página de preços da sua região antes de usar). No Always Free você **roda o Postgres
você mesmo**, num container na VM Ampere. É o caminho deste plano.

Recursos Always Free que usamos:

- Compute Ampere **A1**: até 4 OCPU / 24 GB no total, dividíveis em VMs;
- Boot/Block volume: até 200 GB no total;
- Object Storage: 20 GB (para cópia dos backups);
- IP público reservado/efêmero e Load Balancer não são necessários aqui.

### 3.2 Criar a VM

1. Console OCI → **Compute → Instances → Create instance**.
2. Shape: **VM.Standard.A1.Flex**, 2 OCPU / 12 GB. Imagem: **Ubuntu 24.04 (aarch64)**.
3. Rede: VCN pública com IP público. Gere/guarde a chave SSH (nunca commitar).
4. Boot volume: 50 GB.
5. **Security List / NSG** da subnet: ingress TCP **22** (só o seu IP), **80** e **443**
   (`0.0.0.0/0`). **Não** abra 5432.
6. Criar. Se aparecer "Out of capacity", tente outro Availability Domain ou outro horário.
7. Anote o **IP público**.

### 3.3 Preparar a VM

```bash
ssh -i <chave> ubuntu@<IP>
# firewall da imagem Ubuntu da Oracle vem fechado: liberar 80/443
sudo iptables -I INPUT 6 -p tcp --dport 80  -j ACCEPT
sudo iptables -I INPUT 6 -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
# Docker + compose
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu   # reconectar a sessão depois
sudo mkdir -p /opt/app/{pgdata,backups,web} && sudo chown -R ubuntu /opt/app
```

### 3.4 DNS genérico + HTTPS (Let's Encrypt)

Sem comprar domínio, use um DNS curinga que resolve para o seu IP:

- **sslip.io:** `129-151-10-20.sslip.io` resolve para `129.151.10.20` (substitua os pontos do
  IP por hífens). Sem cadastro.
- **DuckDNS:** subdomínio fixo `meuapp.duckdns.org`, grátis, com token.

O **Caddy** pede e renova o certificado Let's Encrypt sozinho (desafio HTTP-01 na porta 80).
Não precisa de certbot nem de cron.

Cuidados:

- O desafio exige **porta 80 aberta de fora** e o nome já apontando para o IP.
- Domínios curinga compartilhados (sslip.io) dividem o limite de emissões do Let's Encrypt com
  todo mundo; se bater no limite, o Caddy tenta a ZeroSSL sozinho. DuckDNS ou domínio seu é mais
  previsível.
- Use o ambiente *staging* do Let's Encrypt nos testes iniciais para não queimar o limite
  (`acme_ca https://acme-staging-v02.api.letsencrypt.org/directory` no Caddyfile).

### 3.5 Postgres + backend + Caddy (compose da homologação)

`deploy/docker-compose.hml.yml` (esboço):

```yaml
services:
  postgres:
    image: postgres:17
    environment:
      POSTGRES_DB: langgraph
      POSTGRES_USER: lg_app
      POSTGRES_PASSWORD: ${PG_PASSWORD}
    volumes: ["/opt/app/pgdata:/var/lib/postgresql/data"]
    healthcheck: { test: ["CMD-SHELL", "pg_isready -U lg_app -d langgraph"], interval: 10s }
    restart: unless-stopped            # sem "ports": só a rede interna enxerga

  backend:
    build: ..
    env_file: /opt/app/.env
    environment:
      SPRING_PROFILES_ACTIVE: hml
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/langgraph
      SPRING_DATASOURCE_USERNAME: lg_app
      SPRING_DATASOURCE_PASSWORD: ${PG_PASSWORD}
    depends_on: { postgres: { condition: service_healthy } }
    restart: unless-stopped

  caddy:
    image: caddy:2
    ports: ["80:80", "443:443"]
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile
      - /opt/app/web:/srv/web:ro
      - caddy_data:/data
    depends_on: [backend]
    restart: unless-stopped

volumes: { caddy_data: {} }
```

`deploy/Caddyfile`:

```
129-151-10-20.sslip.io {
    encode gzip
    handle /api/* {
        reverse_proxy backend:8080 {
            flush_interval -1          # SSE/AG-UI chega em tempo real
        }
    }
    handle {
        root * /srv/web
        try_files {path} /index.html
        file_server
    }
}
```

`/opt/app/.env` (`chmod 600`, fora do git): `DEEPSEEK_API_KEY`, `PG_PASSWORD`,
`UCP_AGENT_PROFILE_URL`, `APP_CHECKPOINTER=postgres`.

### 3.6 Backup do Postgres

- Cron diário na VM: `docker exec postgres pg_dump -U lg_app langgraph | gzip > /opt/app/backups/lg-$(date +%F).sql.gz`, mantendo 7 dias.
- Opcional: copiar para um bucket do Object Storage (20 GB grátis).
- Como é só checkpoint de homologação, perder o banco é aceitável; o backup é conveniência.

### 3.7 O que me passar quando estiver pronto

Só isto (nunca chave SSH nem a senha do Postgres no chat):

- IP público da VM e o nome DNS escolhido;
- confirmação de que a VM abre 80/443 por fora (`curl http://<ip>` responde algo ou "connection refused" do próprio Caddy).

## 4. Configuração por ambiente (backend)

Nada de segredo no repositório. Mesmos nomes de variável em todos os ambientes.

| Variável | Local | Homologação |
|---|---|---|
| `DEEPSEEK_API_KEY` | `application-local.yml` (gitignored) | `/opt/app/.env` |
| `APP_CHECKPOINTER` | `memory` | `postgres` |
| `SPRING_DATASOURCE_*` | — | compose (do `.env`) |
| `UCP_AGENT_PROFILE_URL` | URL jsDelivr fixada no commit | idem |

CORS: **não é necessário**. Em dev o Vite faz proxy; em homologação o Caddy serve tudo na mesma
origem.

Perfis Spring: padrão (local) e `hml`.

## 5. CI/CD (GitHub Actions) e deploy

Repositórios privados: `renanpadilha/lang-graph-ucp-poc` (backend) e
`renanpadilha/lang-graph-frontend-poc` (frontend). **Já criados, CI verde nos dois.**

### 5.1 CI (automático)

Push na `main` e PR: backend roda `./mvnw -DskipTests package`; frontend roda
`npm ci && npm run build` (pula enquanto não existe `package.json`). Sem testes (POC).

### 5.2 CD (manual ou tag `v*`; desligado por padrão)

Só executa se a variable `DEPLOY_ENABLED=true`. Evita falha enquanto a VM não existe.

- **Backend:** compila o jar, monta a imagem **arm64** (jar sem dependência de arquitetura;
  qemu só monta a camada), envia com `docker save | ssh ... docker load` (sem registry),
  copia `deploy/` para `/opt/app/deploy` e roda `docker compose up -d`.
- **Frontend:** `npm run build` e `rsync dist/` para `/opt/app/web`. O Caddy já serve essa pasta.

Secrets em ambos os repositórios: `OCI_HOST`, `OCI_USER`, `OCI_SSH_KEY` (chave **só de deploy**).
Passo a passo no README de cada repo. O `/opt/app/.env` da VM nasce de `deploy/.env.example`
e nunca passa pelo Git.

### 5.3 Rotina de atualização

```
código → push na main → CI valida
quando quiser publicar: git tag v0.1.0 && git push --tags   (ou "Run workflow")
rollback: rodar o CD de uma tag anterior
```

Observação: o app ainda **não lê** `APP_CHECKPOINTER`/perfil `hml` (passo 1 e 2 da §7). Até
lá o deploy sobe, mas o estado continua em memória.

### 5.4 Checklist de promoção (local → homologação)

- [ ] fluxo completo no local com a chave DeepSeek real;
- [ ] `APP_CHECKPOINTER=postgres` testado local (Postgres em contêiner na sua máquina);
- [ ] thread pausada sobrevive a reiniciar o backend (`/approve` continua funcionando);
- [ ] `/opt/app/.env` preenchido, `chmod 600`;
- [ ] 80/443 abertos na Security List e no `iptables`; 5432 **fechado**;
- [ ] certificado válido (testar primeiro com o *staging*);
- [ ] `complete_checkout` continua ausente do código.

## 6. Segurança (resumo)

- **Chave DeepSeek:** foi colada no chat e no editor. Está em `application-local.yml`
  (gitignored, `chmod 600`). **Rotacione a chave** no painel da DeepSeek e use a nova só nos
  arquivos de segredo.
- Nunca colocar chave no front (`VITE_*` vai para o bundle).
- Postgres sem porta publicada; só a rede do compose o enxerga.
- SSH só do seu IP.
- Homologação com checkout real da Boca Rosa: só leitura + `create_cart` + `create_checkout`,
  com dados fictícios. Sem `complete_checkout`.

## 7. Ordem de execução (infra)

1. Perfis Spring + `app.checkpointer`; `memory` continua padrão.
2. Adicionar `postgres-saver`; testar local com Postgres em contêiner: pausar, reiniciar o
   back, aprovar.
3. ~~`Dockerfile` do back; `deploy/`; CI/CD~~ — **feito** (falta só validar na VM).
4. **Você:** criar a VM e preparar (3.2 e 3.3), escolher o DNS (3.4) e me passar IP/DNS (3.7).
5. Primeiro deploy com o certificado *staging*; depois o de produção.
6. Rodar o checklist 5.4.
