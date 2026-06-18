# 🚀 JMeter - Quick Start Guide (GUI)

## Instalação Rápida

### Linux (Ubuntu/Debian)
```bash
# Instalar openjdk (requisito)
sudo apt-get install openjdk-17-jdk

# Download e instalação
cd ~/Downloads
wget https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-5.6.3.tgz
tar -xzf apache-jmeter-5.6.3.tgz
sudo mv apache-jmeter-5.6.3 /opt/jmeter
export PATH=$PATH:/opt/jmeter/bin
```

### macOS
```bash
brew install jmeter openjdk@17
```

### Verificar Instalação
```bash
jmeter -v
```

---

## ⚡ Uso Rápido (GUI)

### 1. **Iniciar Infraestrutura**

Terminal 1 - Gateway:
```bash
cd kvstore
mvn exec:java -Dexec.mainClass=com.victor.Gateway -Dexec.args="8000 HTTP"
```

Terminal 2 - Worker (responsável pelo KVStore):
```bash
cd kvstore
mvn exec:java -Dexec.mainClass=com.victor.WorkerComponent -Dexec.args="8001 localhost 8000 HTTP"
```

### 2. **Abrir JMeter GUI com Plano Carregado**

Terminal 4 (ou qualquer outro):
```bash
cd jmeter-tests
jmeter -t kvstore_load_test.jmx
```

Abrirá a GUI com testes HTTP pré-configurados (WRITE, READ, etc.).

### 3. **Ajustar Parâmetros (Opcional)**

No painel esquerdo da GUI, clique em **Test Plan** e veja as variáveis:
- `threads`: 50 (mudar para teste leve/pesado)
- `duration`: 300 (duração em segundos)
- `rampup`: 30 (tempo para atingir todos os threads)

Para alterar, clique em cada variável, edite o valor no painel direito e clique **Save**.

### 4. **Rodar Teste**

Clique em **Run** → **Start** ou pressione `F5`

Você verá:
- A GUI listará cada amostra sendo enviada
- No painel **Summary Report** (embaixo), as métricas aparecem em tempo real
- Quando terminar, clique em **Run** → **Stop** ou pressione `Ctrl+.`

### 5. **Ver Resultados**

No painel esquerdo, clique em **Summary Report** para ver a tabela com:
- **Samples** — Número de requisições executadas
- **Average** — Tempo médio de resposta
- **Min/Max** — Mínimo e máximo
- **Error %** — Taxa de erro
- **Throughput** — Requisições por segundo

---

## 📊 Cenários de Teste Rápidos (Ajuste na GUI)

### Teste Leve (Validação)
1. Clique em **Test Plan**
2. Mude `threads` para `10` e `duration` para `60`
3. Clique **Run** → **Start** (F5)
4. Deve completar em ~1 minuto

### Teste Normal (Performance)
1. Clique em **Test Plan**
2. Mude `threads` para `100` e `duration` para `300`
3. Clique **Run** → **Start** (F5)
4. Deve completar em ~5-10 minutos

### Teste Pesado (Stress)
1. Clique em **Test Plan**
2. Mude `threads` para `500` e `duration` para `600`
3. Clique **Run** → **Start** (F5)
4. Deve completar em ~15-20 minutos

### Teste de Pico (Spike)
1. Clique em **Test Plan**
2. Mude `threads` para `1000` e `rampup` para `5` (muito rápido!)
3. Clique **Run** → **Start** (F5)
4. Simula 1000 usuários em ~2 minutos

---

## 📊 Interpretando Resultados

### No Summary Report (Tabela)
| Métrica | Esperado | Ação se Fora |
|---------|----------|-------------|
| **Throughput** | 100-500 req/s | Se baixo: aumenta workers |
| **Average** | 10-50 ms | Se alto: possível gargalo no processamento |
| **Error %** | 0% | Se > 1%: sistema sobrecarregado |
| **Max** | < 500 ms | Se alto: picos de latência |

### Exemplo de Bom Resultado
```
Rota                 Samples  Average  Min   Max   Error %  Throughput
REGISTER             1000     5 ms     2 ms  20 ms  0%       100 req/s
HEARTBEAT            1000     3 ms     1 ms  10 ms  0%       100 req/s
WRITE                5000     25 ms    5 ms  150 ms 0%       500 req/s
READ                 5000     20 ms    4 ms  120 ms 0%       500 req/s

```

---

## 🔧 Customizações na GUI

### Habilitar "View Results in Table" (Para Debug)

Quando teste está rodando, você pode querer ver cada requisição individual:

1. No painel esquerdo, procure por **View Results in Table**
2. Clique no checkbox ao lado para habilitar
3. A tabela mostra cada requisição em tempo real (⚠️ Lento com muitos threads!)
4. Desabilite novamente quando terminar o debug

### Adicionar Mais Validações

Para adicionar uma assertion customizada a uma rota:

1. No painel esquerdo, clique em uma rota (ex: **04 - WRITE**)
2. Botão direito → **Add** → **Assertions** → **Response Assertion**
3. Em **Patterns to Test**, adicione o padrão esperado
4. **Apply** e rodar novamente

### Ver Response Time Over Time

Para visualizar latência ao longo do tempo (debug de degradação):

1. Botão direito em **Thread Group**
2. **Add** → **Listener** → **Response Time Graph**
3. Rodar teste novamente
4. Gráfico mostra latência vs tempo

---

## ⏰ Dicas de Timing

### Ramp-Up (Rampa de Aumento)
- `threads`: 100, `rampup`: 30 = início gradual, ~3 threads/segundo
- `threads`: 100, `rampup`: 5 = spike rápido, ~20 threads/segundo

### Duration (Duração Total)
- Teste leve: 60s
- Teste normal: 300-600s
- Teste de carga: 600-1800s

### Think Time
- Deixe em 0 para teste de throughput máximo
- Aumente para 1000ms (1s) se quiser simular usuário reais com pausa

---

## 🐛 Troubleshooting

### Erro: "Connection refused"
- ✅ Verificar se Gateway está rodando: `netstat -tlnp | grep 8000`
- ✅ Verificar se componentes Worker estão conectados (devem aparecer logs no Gateway)

### Erro: "Out of Memory"
- Antes de rodar jmeter, aumente heap:
```bash
export JVM_ARGS="-Xmx4g -Xms2g"
jmeter -t kvstore_load_test.jmx
```

### Error Rate > 1%
- Verificar logs do Gateway/Workers para erros
- Aumentar `rampup` para evitar pico inicial
- Adicionar mais Workers

### Throughput plateou
- É o limite de throughput esperado
- Para aumentar, adicione mais componentes (Workers)
- Ou reduza payload para testar apenas overhead de rede

---

## 📚 Estrutura de Rotas HTTP

| Rota | Descrição | Body Exemplo | Resposta |
|------|-----------|--------------|----------|
| `/REGISTER` | Registrar componente | `127.0.0.1\|8001` | `SUCESSO` |
| `/HEARTBEAT` | Verificar vitalidade | `127.0.0.1\|8001` | `OK` |
| `/WRITE` | Escrever chave-valor | `key\|value` | confirmação |
| `/READ` | Ler valor de chave | `key` | valor ou `NOT_FOUND` |

---

## ✨ Scripts Auxiliares (Opcionais)

Se quiser análise mais detalhada após o teste na GUI:

### Exportar Resultados
- No JMeter, clique em **View Results in Table**
- Menu → **File** → **Save Table Data** → `.csv`
- Abra em Excel/LibreOffice

### Monitorar Sistema Durante Teste
Em outro terminal (enquanto GUI roda):
```bash
cd jmeter-tests
./monitor_system.sh 600  # Monitorar por 10 minutos
```

Mostra em tempo real: CPU, Memória, Threads Java, Conexões ao Gateway

---

## 📞 Próximos Passos

1. **Testar TCP/GRPC** — Crie novos planos `.jmx` com TCP/gRPC Samplers
2. **Salvar Resultados** — File → Save Plan para guardar configurações customizadas
3. **Integração CI/CD** — Use scripts CLI opcionais para testes automatizados

---

**Última Atualização:** 29 de Abril de 2026  
**Versão:** 2.0 (GUI-Only)
