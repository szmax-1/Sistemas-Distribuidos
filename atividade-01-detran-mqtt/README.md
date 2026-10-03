# Atividade da disciplina de Sistemas Distribuídos (DETRAN - Atividade 01 – Pub/Sub)

Este projeto implementa uma arquitetura de microsserviços desacoplados utilizando a biblioteca Eclipse Paho para comunicação assíncrona orientada a eventos via protocolo MQTT e orquestração por Docker.

---

## Tecnologia e Arquitetura

- **Linguagem:** Java 17 / Maven
- **Protocolo de Mensageria:** MQTT (Broker Mosquitto)
- **Containerização:** Docker e Docker Compose
- **Microsserviços:**
  - `servico-condutores`: Gestão de condutores e CNH.
  - `servico-veiculos`: Emplacamento, cálculo de IPVA, transferência e consulta.
  - `servico-multas`: Registro e consulta de infrações.

---

## Comandos para executar a aplicação

### Pré-requisitos
- Docker Desktop instalado e em execução.

### Passo 1: Subir a infraestrutura e serviços
Na pasta do projeto, execute o comando abaixo para compilar e iniciar todos os contêineres:

```bash
docker compose up --build



## Alguns exemplos para de testes após iniciar os contêineres

### 1. Serviço de Veículos
```bash
# 1. Emplacar um novo veículo (Formato: PLACA;MODELO;VALOR;CPF;ANO)
docker exec -it detran-mosquitto mosquitto_pub -t "detran/veiculo/emplacar" -m "ABC1D23;Civic;50000;12345678900;2022"

# 2. Calcular o IPVA do veículo (Formato: PLACA)
docker exec -it detran-mosquitto mosquitto_pub -t "detran/veiculo/ipva" -m "ABC1D23"

# 3. Transferir propriedade (Formato: PLACA;NOVO_CPF)
docker exec -it detran-mosquitto mosquitto_pub -t "detran/veiculo/transferir" -m "ABC1D23;98765432100"

# 4. Listar veículos por ano (Formato: ANO)
docker exec -it detran-mosquitto mosquitto_pub -t "detran/veiculo/listar_por_ano" -m "2022"