# JMeter - Testes de Performance para KVStore Gateway

## Demo Path (4 passos)

1. **Subir o Gateway** (terminal 1):
   ```bash
   cd kvstore
   mvn exec:java -Dexec.mainClass=com.victor.Gateway -Dexec.args="8000 HTTP"
   ```

2. **Subir um Worker** (terminal 2):
   ```bash
   cd kvstore
   mvn exec:java -Dexec.mainClass=com.victor.WorkerComponent -Dexec.args="8001 localhost 8000 HTTP"
   ```

3. **Abrir o plano no JMeter GUI** (terminal 3):
   ```bash
   cd jmeter-tests
   jmeter -t kvstore_load_test.jmx
   ```

4. **Rodar**: clique em **Run → Start** (ou `Ctrl+R`). Acompanhe o
   **Summary Report** em tempo real. Para o exercício de Requisito 5
   (Knee/Usable Capacity), altere `threads` na GUI (10 → 50 → 200 → 400)
   e capture latência média / max / throughput em cada nível.

---

## Wire Format — JSON Envelope (Phase 5X)

Toda requisição ao Gateway trafega como um único envelope JSON via
`POST /<COMANDO`. O envelope é decodificado pelo `MarshalledServer` em
ambos os lados (cliente e servidor); o body é sempre um objeto JSON com
`verb`, `args` (lista) e `body` (objeto, livre para uso futuro).

### 1. WRITE

```http
POST /WRITE HTTP/1.1
Host: localhost:8000
Content-Type: application/json

{"verb":"WRITE","args":["key1","value_test"],"body":{}}
```

**Resposta esperada** (envelope JSON):
```json
{"verb":"OK","args":["Written key: key1, value: value_test, version: 1"],"body":{}}
```

### 2. READ

```http
POST /READ HTTP/1.1
Host: localhost:8000
Content-Type: application/json

{"verb":"READ","args":["key1"],"body":{}}
```

**Resposta esperada** (envelope JSON):
```json
{"verb":"OK","args":["value=value_test | Version: 1"],"body":{}}
```

Caso a chave não exista, a resposta vem como `{"verb":"UNKNOWN","args":["ERRO: Key not found: key1"]}`.

### 3. REGISTER

```http
POST /REGISTER HTTP/1.1
Host: localhost:8000
Content-Type: application/json

{"verb":"REGISTER","args":["127.0.0.1","8001"],"body":{}}
```

**Resposta esperada**:
```json
{"verb":"OK","args":["SUCESSO: Registrado."],"body":{}}
```

### 4. HEARTBEAT

```http
POST /HEARTBEAT HTTP/1.1
Host: localhost:8000
Content-Type: application/json

{"verb":"HEARTBEAT","args":["127.0.0.1","8001"],"body":{}}
```

**Resposta esperada**:
```json
{"verb":"OK","args":["OK"],"body":{}}
```

---

## Plano de Teste (`kvstore_load_test.jmx`)

Stock JMeter (sem plugins jp@gc). Estrutura:

- **KVStore Load Thread Group** — 50 threads padrão, ramp-up 10s,
  scheduler 60s. Configurável via `-Jthreads=N -Jrampup=N -Jduration=N`.
- **4 HTTPSamplerProxy**: `01 - WRITE`, `02 - READ`, `03 - REGISTER`,
  `04 - HEARTBEAT`. Cada um posta o envelope JSON da seção anterior.
  WRITE usa `${__threadNum}` e `${__time}` (built-ins do JMeter) para
  gerar chaves/valores únicos sem precisar de Counter.
- **4 ResponseAssertion** validam o envelope de resposta de cada rota.
- **View Results Tree** + **Summary Report** (sem jp@gc).

---

## Modo CLI (Opcional)

Para rodar headless e gerar `.jtl`:

```bash
cd jmeter-tests
jmeter -n -t kvstore_load_test.jmx -l results.jtl -e -o jmeter-report \
       -Jthreads=100 -Jrampup=20 -Jduration=120
```

| Parâmetro       | Padrão   | Descrição |
|-----------------|----------|-----------|
| `gateway_host`  | localhost| Host do Gateway |
| `gateway_port`  | 8000     | Porta do Gateway |
| `threads`       | 50       | Número de usuários simultâneos |
| `rampup`        | 10       | Tempo de ramp-up (segundos) |
| `duration`      | 60       | Duração total do teste (segundos) |

Para gerar o dashboard HTML de análise:
```bash
firefox jmeter-report/index.html
```

---

## Análise de Resultados

No **Summary Report**, observe:

- **Throughput**: aumenta linearmente = sistema saudável. Platô =
  limite encontrado (Knee Capacity).
- **Average / Max latency**: se `Average` ultrapassa 2× ou 3× o valor
  baseline, o sistema cruzou o **Usable Capacity** para a SLO escolhida.
- **Error %**: zero é o ideal. > 1% = sistema sobrecarregado.

Para Requisito 5: rode o teste com `threads` em {10, 50, 100, 200, 400},
anote `(threads, throughput, p95-latency)` em cada nível, e plote
`latency-vs-throughput`. O **Knee** é onde a curva inflecte; o
**Usable Capacity** é o throughput máximo antes da latência estourar
a SLO (tipicamente 2× a latência baseline).

---

## Troubleshooting

- **"Connection refused"** — verifique se o Gateway está rodando
  (`netstat -an | grep 8000`) e se um Worker foi iniciado.
- **Error rate alta** — adicione mais Workers (`WorkerComponent`
  em portas diferentes); um único Worker é gargalo intencional para a
  demo de Knee Capacity.
- **Out of Memory na GUI** — aumente o heap:
  ```bash
  export JVM_ARGS="-Xmx4g -Xms2g"
  jmeter -t kvstore_load_test.jmx
  ```

---

**Versão**: 3.0 (Phase 5X — JSON envelope)  
**Última Atualização**: 24 de Junho de 2026