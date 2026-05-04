# JMeter - Testes de Performance para KVStore Gateway

## 🎯 Comece Aqui

Para usar **apenas GUI** (recomendado para começar):
1. Leia [START_HERE.md](START_HERE.md) — 5 minutos para começar
2. Ou [QUICKSTART.md](QUICKSTART.md) — Guia completo com exemplos

Se quiser **automação via CLI** (opcional):
- Veja a seção "Modo CLI" abaixo na documentação

---

## Estrutura de Rotas HTTP

O Gateway expõe as seguintes rotas via HTTP. O comando é extraído do **path** (`/COMANDO`) e o payload do **body** (POST).

### 1. **ROUTE_TO_WORKER** (Rota para Passer)
Acessa um componente Passer para processar transações.

**Requisição:**
```
POST /ROUTE_TO_WORKER HTTP/1.1
Host: localhost:8000
Content-Type: text/plain
Content-Length: 20

WRITE|key1|value1
```

**Resposta esperada:** OK ou erro

---

### 2. **WRITE** (Escrita no KVStore)
Escreve um valor na chave especificada.

**Requisição:**
```
POST /WRITE HTTP/1.1
Host: localhost:8000
Content-Type: text/plain
Content-Length: 17

key1|value_test
```

**Resposta esperada:** Confirmação de escrita ou erro

---

### 3. **READ** (Leitura no KVStore)
Lê o valor de uma chave.

**Requisição:**
```
POST /READ HTTP/1.1
Host: localhost:8000
Content-Type: text/plain
Content-Length: 4

key1
```

**Resposta esperada:** valor armazenado ou "NOT_FOUND"

---

### 4. **REGISTER** (Registro de Componente)
Registra um novo componente Worker/Passer no Gateway.

**Requisição:**
```
POST /REGISTER HTTP/1.1
Host: localhost:8000
Content-Type: text/plain
Content-Length: 28

127.0.0.1|8001|PASSER_ON
```

**Formato:** `IP|PORTA|TIPO_COMPONENTE`
- **TIPO_COMPONENTE:** `WORKER`, `PASSER_ON`

**Resposta esperada:** "SUCESSO: Registrado."

---

### 5. **HEARTBEAT** (Verificação de Vitalidade)
Atualiza o timestamp do heartbeat de um componente registrado.

**Requisição:**
```
POST /HEARTBEAT HTTP/1.1
Host: localhost:8000
Content-Type: text/plain
Content-Length: 28

127.0.0.1|8001|PASSER_ON
```

**Formato:** `IP|PORTA|TIPO_COMPONENTE`

**Resposta esperada:** "OK"

---

## Configuração de Teste JMeter

### Arquivos Disponíveis
- `kvstore_load_test.jmx` — Plano JMeter com 5 testes de rotas (READ, WRITE, ROUTE_TO_WORKER, REGISTER, HEARTBEAT)

### Pré-requisitos
1. **JMeter** instalado (download: https://jmeter.apache.org/download_jmeter.cgi)
2. **Gateway rodando:** `mvn exec:java -Dexec.mainClass=com.victor.Gateway -Dexec.args="8000 HTTP"`
3. **Workers/Passers registrados** (ou configure o teste para auto-registrar)

---

## Modo GUI (Recomendado para Começar)

### Abrir Plano no JMeter GUI

```bash
cd jmeter-tests
jmeter -t kvstore_load_test.jmx
```

Abrirá a GUI com o plano pré-carregado. Você verá:
- **Thread Group** — Configuração de carga (threads, duração, ramp-up)
- **HTTP Requests** — 6 rotas pré-configuradas
- **Assertions** — Validações de resposta
- **Listeners** — Summary Report (resultados em tempo real)

### Ajustar Parâmetros na GUI

No painel esquerdo, clique em **Test Plan**. No painel direito, edite as variáveis:
- `threads`: 50 → altere para 10, 100, 500, 1000, etc.
- `duration`: 300 → duração em segundos
- `rampup`: 30 → tempo para atingir todos os threads
- `gateway_host`: localhost → endereço do gateway
- `gateway_port`: 8000 → porta do gateway

### Rodar Teste

1. Clique em **Run** → **Start** (ou pressione `F5`)
2. Acompanhe em tempo real no painel **Summary Report**
3. Quando terminar, clique em **Run** → **Stop** (ou `Ctrl+.`)

### Ver Resultados

No painel esquerdo, clique em **Summary Report** para ver a tabela com:
- **Samples** — Número de requisições
- **Average** — Tempo médio de resposta
- **Min/Max** — Mínimo e máximo
- **Error %** — Taxa de erro
- **Throughput** — Requisições/segundo

---

## Modo CLI (Opcional)

### Executar Testes Automatizados

Se preferir automação via CLI (scripts incluídos), use:

```bash
cd jmeter-tests

# Teste rápido (1 minuto)
./run_test.sh baseline

# Teste padrão (5 minutos)
./run_test.sh normal

# Teste pesado (10 minutos)
./run_test.sh stress
```

Os scripts geram:
- `.jtl` — Arquivo XML com resultados brutos
- `jmeter-report-*/` — Dashboard HTML com gráficos

Abrir resultados:
```bash
firefox jmeter-report-baseline-*/index.html
```

### Parâmetros Configuráveis via CLI

Você pode sobrescrever via `-Jkey=value`:

```bash
jmeter -n -t kvstore_load_test.jmx \
        -l results.jtl -e -o jmeter-report \
        -Jgateway_host=192.168.1.100 \
        -Jgateway_port=8080 \
        -Jthreads=200 \
        -Jduration=600 \
        -Jrampup=60
```

| Parâmetro       | Padrão | Descrição |
|-----------------|--------|-----------|
| `gateway_host`  | localhost | Host do Gateway |
| `gateway_port`  | 8000   | Porta do Gateway |
| `threads`       | 50     | Número de usuários simultâneos |
| `rampup`        | 30     | Tempo de ramp-up (segundos) |
| `duration`      | 300    | Duração total do teste (segundos) |
| `think_time`    | 0      | Tempo entre requisições (ms) |

---

## Análise de Resultados

### Na GUI (Modo Gráfico)
Após o teste, os resultados aparecem em tempo real no painel **Summary Report**. Você verá uma tabela com métricas por rota.

### O Que Observar
- **Throughput:** Aumenta linearmente = sistema saudável. Platô = limite encontrado.
- **Response Time:** Aumenta com carga? Possível gargalo no Gateway ou Workers.
- **Error %:** De zero? Error rate > 1% = sistema sobrecarregado.
- **Average (Média):** Tempo médio. Se > 100ms, possível problema.
- **Max:** Pico máximo. Se > 1000ms, procurar por gargalos.

### No Dashboard HTML (Modo CLI)
Se usando scripts CLI, abra `jmeter-report-*/index.html` no navegador para ver:
- Gráficos de tempo de resposta
- Distribuição de latências (percentis)
- Throughput ao longo do tempo
- Taxa de erros

### Comparação Entre Testes
```bash
# Gerar CSV para comparação
jmeter -g results_baseline.jtl -o report_baseline
jmeter -g results_normal.jtl -o report_normal
# Copiar aggregate report dos resultados para planilha
```

---

## Troubleshooting

### Erro: "Connection refused"
- Verifique se o Gateway está rodando: `netstat -an | grep 8000`
- Verifique se Workers/Passers estão conectados

### Taxa de Erro Alta
- Aumentar Workers/Passers registrados
- Verificar logs do Gateway para erros
- Aumentar `rampup` para evitar picos iniciais

### Throughput Muito Baixo
- É possível que seja o limite esperado para a carga
- Para aumentar, adicionar mais Workers/Passers
- Ou reduzir payload para testar apenas overhead de rede

### Out of Memory (GUI Crash)
- Aumentar heap JVM antes de rodar:
```bash
export JVM_ARGS="-Xmx4g -Xms2g"
jmeter -t kvstore_load_test.jmx
```

---

## Scripts Auxiliares (Opcionais)

### Monitor Sistema (Opcional)
Se quiser monitorar recursos enquanto o teste GUI roda, abra outro terminal:

```bash
cd jmeter-tests
./monitor_system.sh 600  # Monitora por 10 minutos
```

Mostra: CPU, Memória, Threads Java, Conexões ao Gateway

### Validar Rotas Antes de Testar (Opcional)
Para fazer um teste rápido das rotas com curl:

```bash
cd jmeter-tests
./test_routes.sh
# Testa REGISTER, HEARTBEAT, WRITE, READ, ROUTE_TO_WORKER
```

---

## Próximos Passos

1. **Testar com TCP/gRPC** — Criar novos planos `.jmx` com samplers TCP/gRPC
2. **Alterar Plano** — Salve o `.jmx` customizado com **File → Save**
3. **Automatizar** — Use scripts CLI (opcionais) para testes em CI/CD

---

**Versão:** 2.0 (GUI-First)  
**Última Atualização:** 29 de Abril de 2026
