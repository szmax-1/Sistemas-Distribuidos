# RELATÓRIO DE DESEMPENHO: CLIENTE-SERVIDOR VS. PEER-TO-PEER (P2P)

**Disciplina:** Sistemas Distribuidos  
**Atividade:** Atividade 03 - Benchmark de Arquiteturas Distribuídas  
**Ambiente de Testes:** Docker (Eclipse Temurin JDK 17)

---

## 1. Introdução
Este relatório apresenta a análise comparativa de desempenho entre a arquitetura Cliente-Servidor (avaliada sob três abordagens de concorrência) e a arquitetura Peer-to-Peer (P2P) em malha. O objetivo é mensurar o impacto do tamanho dos arquivos e do número de nós concorrentes na latência de transferência de dados.

---

## 2. Metodologia e Cenários de Teste
Os testes foram executados de forma automatizada via Docker isolado, simulando as seguintes variáveis:
- **Tamanhos de Arquivo:** 5 MB, 50 MB e 500 MB.
- **Nós Clientes Concorrentes:** 1, 5 e 10 nós.
- **Modelos Avaliados:**
  1. Cliente-Servidor Iterativo (Atendimento sequencial)
  2. Cliente-Servidor Multithread (1 Thread por cliente)
  3. Cliente-Servidor Thread Pool (Pool fixo com N=5)
  4. Arquitetura P2P (Rede descentralizada em malha)

---

## 3. Tabela Comparativa de Resultados

| Arquivo | Clientes | Modelo | Mínimo (ms) | Médio (ms) | Máximo (ms) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **5 MB** | 1 | CS Iterativo | 24 | 24 | 24 |
| | | CS 1 Thread/Cliente | 6 | 6 | 6 |
| | | CS Thread Pool (N=5) | 6 | 6 | 6 |
| | | Arquitetura P2P | 7 | 7 | 7 |
| | 5 | CS Iterativo | 5 | 15 | 25 |
| | | CS 1 Thread/Cliente | 9 | 10 | 12 |
| | | CS Thread Pool (N=5) | 11 | 12 | 14 |
| | | Arquitetura P2P | 8 | 12 | 16 |
| | 10 | CS Iterativo | 12 | 47 | 76 |
| | | CS 1 Thread/Cliente | 9 | 16 | 25 |
| | | CS Thread Pool (N=5) | 22 | 29 | 34 |
| | | Arquitetura P2P | 7 | 10 | 14 |
| **50 MB** | 1 | CS Iterativo | 33 | 33 | 33 |
| | | CS 1 Thread/Cliente | 32 | 32 | 32 |
| | | CS Thread Pool (N=5) | 35 | 35 | 35 |
| | | Arquitetura P2P | 36 | 36 | 36 |
| | 5 | CS Iterativo | 34 | 89 | 143 |
| | | CS 1 Thread/Cliente | 61 | 63 | 66 |
| | | CS Thread Pool (N=5) | 54 | 61 | 73 |
| | | Arquitetura P2P | 51 | 59 | 69 |
| | 10 | CS Iterativo | 34 | 175 | 302 |
| | | CS 1 Thread/Cliente | 86 | 101 | 117 |
| | | CS Thread Pool (N=5) | 53 | 82 | 114 |
| | | Arquitetura P2P | 67 | 90 | 109 |
| **500 MB**| 1 | CS Iterativo | 282 | 282 | 282 |
| | | CS 1 Thread/Cliente | 260 | 260 | 260 |
| | | CS Thread Pool (N=5) | 286 | 286 | 286 |
| | | Arquitetura P2P | 278 | 278 | 278 |
| | 5 | CS Iterativo | 267 | 810 | 1343 |
| | | CS 1 Thread/Cliente | 584 | 592 | 606 |
| | | CS Thread Pool (N=5) | 574 | 580 | 587 |
| | | Arquitetura P2P | 528 | 547 | 569 |
| | 10 | CS Iterativo | 281 | 1487 | 2697 |
| | | CS 1 Thread/Cliente | 950 | 1059 | 1123 |
| | | CS Thread Pool (N=5) | 538 | 840 | 1150 |
| | | Arquitetura P2P | 943 | 1038 | 1079 |

---

## 4. Análise Crítica dos Resultados

1. **Servidor Iterativo vs. Concorrente:**
   O servidor iterativo apresentou degradação linear de desempenho à medida que a quantidade de conexões simultâneas aumentou. Com 10 nós requisitando 500 MB, a discrepância entre o tempo mínimo (281 ms) e máximo (2.697 ms) evidencia a retenção da fila de requisições.

2. **1 Thread/Cliente vs. Thread Pool:**
   O modelo de Thread por Cliente atendeu adequadamente em cargas baixas. Contudo, em cargas elevadas (500 MB / 10 clientes), a abordagem com Thread Pool (N=5) superou a criação individual de threads (tempo médio de 840 ms contra 1.059 ms), comprovando a redução do *overhead* de escalonamento do sistema operacional.

3. **Desempenho da Arquitetura P2P:**
   A arquitetura P2P manteve latências médias inferiores ou equiparáveis ao Thread Pool em quase todos os cenários. A descentralização eliminou o ponto único de saturação de banda do servidor, distribuindo o tráfego de forma otimizada entre os pares.

---

## 5. Conclusão
Os testes comprovam que arquiteturas centralizadas iterativas são inadequadas para ambientes distribuídos de alta concorrência. Embora abordagens concorrentes melhorem a vazão no modelo Cliente-Servidor, o uso da malha P2P demonstra ser a solução mais escalável e estável para a transferência distribuída de grandes volumes de dados.