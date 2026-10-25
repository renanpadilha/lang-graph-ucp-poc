# lang-graph-ucp-poc

POC: fluxo de compra (discovery até fulfillment) em LangGraph4j + Spring AI contra o endpoint UCP
da Boca Rosa. Sem testes automatizados (POC). Planos: [PLANO.md](PLANO.md), [PLANO-INFRA.md](PLANO-INFRA.md).

## Rodar local

```bash
# chave DeepSeek (opcional; sem ela os nodes usam fallback determinístico)
printf 'spring:\n  ai:\n    deepseek:\n      api-key: <sua-chave>\n' > application-local.yml
./mvnw spring-boot:run
curl -X POST localhost:8080/orders -H 'content-type: application/json' -d '{"query":"quero uma base para pele oleosa"}'
curl -X POST localhost:8080/orders/<threadId>/approve
```

`application-local.yml` está no `.gitignore`. Nunca commitar chaves.

## CI/CD

- **CI** (`.github/workflows/ci.yml`): a cada push na `main` e PR, compila e empacota.
- **CD** (`.github/workflows/cd.yml`): manual (*Run workflow*) ou ao criar uma tag `v*`. Constrói a
  imagem arm64, envia por SSH para a VM da OCI e sobe o `docker compose`. Fica **desligado** até
  `DEPLOY_ENABLED=true`.

### Ligar o CD (depois de criar a VM, ver PLANO-INFRA.md)

1. Gere uma chave só para deploy: `ssh-keygen -t ed25519 -f oci_deploy -C gha-deploy -N ""`.
   Coloque `oci_deploy.pub` em `~/.ssh/authorized_keys` da VM.
2. Em *Settings > Secrets and variables > Actions*:
   - Secrets: `OCI_HOST` (IP), `OCI_USER` (`ubuntu`), `OCI_SSH_KEY` (conteúdo de `oci_deploy`);
   - Variable: `DEPLOY_ENABLED=true`.
3. Na VM, crie `/opt/app/.env` a partir de `deploy/.env.example` (`chmod 600`).
4. Apague a chave privada `oci_deploy` local depois de copiá-la para o secret.
