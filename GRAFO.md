# GRAFO — como o LangGraph4j orquestra o pedido

Documento complementar ao [PLANO.md](PLANO.md). Explica o grafo de estados (`OrderGraphConfig` +
`OrderTopology`): o que cada etapa faz, quais características do LangGraph o código usa, e por que
o fluxo é determinístico mesmo usando LLM.

## 1. Fluxo

```
START
  │
  ▼
┌─────────────┐   ok    ┌──────────┐        ┌──────────────┐   ok   ┌─────────────────┐
│  discovery  │────────▶│  select  │───────▶│   checkout    │───────▶│  payment (⏸)    │
│    (LLM)    │         │  (LLM)   │        │ (determinist.) │       │ (determinist.)  │
└─────────────┘         └──────────┘        └──────────────┘        └────────┬────────┘
       │ fail                                      │ fail                    │
       ▼                                            ▼                        ▼
┌──────────────┐  retry                      ┌──────────────┐          ┌─────────┐
│ errorHandler │◀─────────────────────────────┘              │  order  │
│    (LLM)     │                                              │(determ.)│
└──────┬───────┘                                              └────┬────┘
       │ giveUp                                                    ▼
       ▼                                                     ┌──────────────┐
      END                                                    │ fulfillment  │
                                                               │    (LLM)     │
                                                               └──────┬───────┘
                                                                      ▼
                                                                     END
```

`⏸` = `INTERRUPT_BEFORE` (ver §4) — grafo para ali, espera retomada explícita via
`POST /orders/{threadId}/approve`.

Fonte única da topologia: [`OrderTopology.java`](src/main/java/com/example/ucpagent/graph/OrderTopology.java).
O grafo (`OrderGraphConfig`) e o endpoint `/api/graph` (consumido pelo front) são montados a partir
dos mesmos `NODES`/`EDGES`/`ROUTERS` — desenho do front nunca diverge do código.

## 2. Pra que serve cada etapa

| Node | Tipo | Faz |
|---|---|---|
| `discovery` | LLM | Texto livre do cliente → `SearchFilter` estruturado. Dispara `search_catalog` **em paralelo em todas as lojas configuradas** (fan-out em virtual threads — ver [`StoreRegistry`](src/main/java/com/example/ucpagent/ucp/StoreRegistry.java)), junta os candidatos |
| `select` | determinístico | O grafo **pausa antes** deste nó (`INTERRUPT_BEFORE`): o cliente marca vários produtos (com quantidade) nos cards e a escolha entra no estado (`selectedVariantIds`, `selectedQuantities`). O nó só confere que os ids existem entre os candidatos |
| `checkout` | determinístico | `create_cart` + `create_checkout` **um par por loja**, com todos os itens escolhidos daquela loja (`carts` e `checkouts` são mapas storeId → id) |
| `payment` | determinístico | SIMULA pagamento: monta payload, loga, marca `PAYMENT_SIMULATED`. **Nunca chama `complete_checkout`** (cobrança real) |
| `order` | determinístico | Marca pedido como feito (simulado — sem criar pedido real na loja) |
| `fulfillment` | LLM | Para cada loja, pega as opções de frete e o LLM escolhe o melhor equilíbrio custo × prazo (`shippingOptions`: storeId → optionId) |
| `errorHandler` | LLM | Lê o erro, decide `RETRY` (reformula a busca) ou `GIVE_UP`. Máximo 2 retries (`MAX_RETRIES`) |

Cada node implementa `NodeAction<OrderState>`: função pura `Map<String,Object> apply(OrderState state)`
— recebe o estado atual, devolve só o **diff** a aplicar. Ver pasta
[`graph/nodes/`](src/main/java/com/example/ucpagent/graph/nodes/).

## 3. Características do LangGraph usadas no código

- **Topologia como dado** — `OrderTopology.NODES`/`EDGES`/`ROUTERS`. `OrderGraphConfig` monta o
  `StateGraph` iterando essas listas; `GraphController` (`GET /api/graph`) devolve o mesmo dado pro
  front. Uma única fonte de verdade.
- **`StateGraph` + `addConditionalEdges`** — roteamento depois de um node é uma função pura
  `OrderState -> String (label)` (`ROUTERS`, em `OrderTopology`), nunca texto livre decidido pelo LLM.
- **`Channels` (`OrderState.SCHEMA`)** — cada campo do estado declara sua semântica de merge:
  `CANDIDATES` acumula (`Channels.appender`), o resto é last-write-wins (default).
- **`BaseCheckpointSaver`** (Postgres) — o estado persiste por `threadId`. O grafo pausa/retoma entre
  requisições HTTP diferentes, sobrevive a restart do processo.
- **`CompileConfig.interruptBefore("payment")`** — human-in-the-loop: execução para antes do node de
  pagamento e só continua com uma chamada explícita de aprovação.
- **`node_async` + `addWrapCallNodeHook`** (`TraceHook`) — observabilidade (eventos
  `STEP_STARTED`/`CUSTOM`/`STEP_FINISHED` via AG-UI) plugada por fora do node, sem tocar a lógica de
  negócio. `CallRecorder` coleta as chamadas UCP/LLM feitas dentro do node.
- **`NodeAction<State>`** — contrato funcional de node: estado → diff de estado.

## 4. Por que é determinístico (mesmo usando LLM)

1. **Topologia fixa, declarada como dado.** O LLM nunca decide "qual node roda depois" — isso é
   `ROUTERS`, funções Java simples lendo `state.error()` / `state.status()`.
2. **Nodes determinísticos não chamam LLM nenhum.** `checkout`, `payment`, `order` são só
   chamada de API ou lógica simulada: mesma entrada → mesma saída, sempre reproduzível.
3. **Fallback determinístico nos nodes LLM.** `llm.structured(prompt, Tipo.class, () -> fallback)` —
   se não tem API key ou o LLM falha, cai num heurístico fixo (ex.: primeiro candidato da lista,
   primeira opção de frete). O grafo nunca trava por indisponibilidade de modelo.
4. **Checkpoint = replay determinístico.** Estado persiste no Postgres; retomar um `threadId` sempre
   continua do mesmo ponto exato — não depende de estado em memória efêmero.
5. **Regra de segurança é código, não prompt.** `payment` **nunca** chama `complete_checkout`
   (cobrança real) — garantia hard-coded no node, independente do LLM "se comportar bem".

**Resumo:** o LLM só preenche *valores* dentro do estado (filtro de busca, produto escolhido, opção
de frete, decisão de retry). *Quem* roda depois e *em que ordem* é sempre decidido por código
determinístico, lido do mesmo `OrderState` que o LLM só escreve.

## 5. Multi-loja (fan-out paralelo)

Desde a integração com múltiplas lojas Shopify (ver commit relacionado), `discovery` não consulta
mais 1 endpoint fixo: `StoreRegistry` mantém 1 `UcpMcpClient` por loja (`ucp.stores` em
`application.yml`), e `DiscoveryNode` dispara `search_catalog` em todas simultaneamente usando
virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`). Uma loja lenta ou fora do ar não
derruba a busca — só falha se **todas** falharem. O produto escolhido em `select` carrega o
`storeId` de origem, que os nodes seguintes (`checkout`, `fulfillment`) usam para resolver o
`UcpMcpClient` certo via `StoreRegistry.get(storeId)`.
