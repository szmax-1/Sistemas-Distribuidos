# DComp Drive — Sistema Distribuído de Armazenamento de Arquivos

Sistema distribuído de armazenamento de arquivos inspirado em **HDFS** e **GFS**, desenvolvido em Java 17 para a disciplina de Sistemas Distribuídos (UFS).

## Arquitetura e Papéis

O sistema separa o **plano de controle** do **plano de dados** para evitar gargalos no controlador:

- **Controlador + Nó de Metadados (`ControllerServer`)**: Gerencia a árvore de diretórios, metadados de arquivos e blocos, *leases* de concorrência para escrita, descoberta automática de nós via *heartbeats*, monitoramento de espaço livre, verificação periódica de integridade (*scrubbing*), re-replicação automática e persistência durável em disco via **WAL (`wal.jsonl`) + Snapshot (`snapshot.json`)**.
- **Nós de Armazenamento (`StorageNodeServer`)**: Gerenciam um ou mais diretórios de disco simulados, armazenam blocos com escrita atômica, validam hashes SHA-256 na gravação e sob demanda, enviam *heartbeats* periódicos com métricas de espaço e executam cópias diretas de blocos entre nós durante recuperações.
- **Cliente CLI (`DriveCli`)**: Divide arquivos em blocos (*chunks*), calcula hashes SHA-256 (do bloco e do arquivo completo), aplica criptografia opcional **AES-256-GCM** e transfere os dados diretamente para os nós de armazenamento em paralelo.

## Requisitos e Execução com Docker

Pré-requisitos: **Docker** e **Docker Compose**.

### 1. Subir o cluster (1 Controlador + 5 Nós de Armazenamento)
Na pasta `atividade-04-dcomp-drive`, execute:

```bash
docker compose up -d --build
```

O ambiente inicia 6 contêineres em rede isolada:
- `dcomp-controller` (porta `7000`): Controlador e Nó de Metadados.
- `dcomp-node1` (`maquina-1`, porta `7001`): 2 discos simulados (`disk1`, `disk2`).
- `dcomp-node2` (`maquina-2`, porta `7002`): 1 disco simulado (`disk1`).
- `dcomp-node3` (`maquina-3`, porta `7003`): 1 disco simulado (`disk1`).
- `dcomp-node4` (`maquina-4`, porta `7004`): 2 discos simulados (`disk1`, `disk2`).
- `dcomp-node5` (`maquina-5`, porta `7005`): 1 disco simulado (`disk1`).

### 2. Configuração
Os parâmetros ficam em `config/drive-config.json` e podem ser sobrescritos por variáveis de ambiente no `docker-compose.yml` sem recompilar o código:
- `replicationFactor` (`3`): número de réplicas por bloco ($N$).
- `writeQuorum` (`2`): número mínimo de réplicas confirmadas antes do *commit* ($W$).
- `blockSizeBytes` (`4194304`): tamanho máximo de cada bloco (4 MB).
- `heartbeatIntervalMs` (`3000`) e `heartbeatTimeoutMs` (`9000`): detecção de nós suspeitos e mortos.
- `scrubbingIntervalMs` (`15000`): intervalo de verificação periódica de integridade em segundo plano.
- `maxDiskUsagePercent` (`90.0`): limite de ocupação de disco para alocação de novas réplicas.
- `encryptionEnabled` (`false`): habilita ou desabilita cifra **AES-256-GCM** antes do envio dos blocos.

## Exemplos de Uso da CLI (`DriveCli`)

A pasta local `./workspace` é mapeada em `/workspace` dentro do contêiner do controlador:

```bash
# 1. Visualizar estado dos nós, espaço dos discos e saúde dos arquivos (RF11)
docker compose exec controller java -cp app.jar com.dcomp.drive.DriveCli status

# 2. Criar e listar diretórios (RF1)
docker compose exec controller java -cp app.jar com.dcomp.drive.DriveCli mkdir /docs
docker compose exec controller java -cp app.jar com.dcomp.drive.DriveCli ls /docs

# 3. Enviar arquivo com replicação N=3 e verificação SHA-256 (RF1, RF3, RF4)
docker compose exec controller java -cp app.jar com.dcomp.drive.DriveCli put /workspace/exemplo.txt /docs/exemplo.txt

# 4. Consultar metadados, localização das réplicas e estado de saúde (RF1)
docker compose exec controller java -cp app.jar com.dcomp.drive.DriveCli stat /docs/exemplo.txt

# 5. Baixar arquivo com verificação de integridade SHA-256 (RF1, RF4)
docker compose exec controller java -cp app.jar com.dcomp.drive.DriveCli get /docs/exemplo.txt /workspace/baixado.txt

# 6. Mover/renomear e remover arquivos ou diretórios vazios (RF1)
docker compose exec controller java -cp app.jar com.dcomp.drive.DriveCli mv /docs/exemplo.txt /docs/relatorio.txt
docker compose exec controller java -cp app.jar com.dcomp.drive.DriveCli rm /docs/relatorio.txt
docker compose exec controller java -cp app.jar com.dcomp.drive.DriveCli rmdir /docs

# 7. Esvaziar um nó de forma planejada antes de manutenção (RF7)
docker compose exec controller java -cp app.jar com.dcomp.drive.DriveCli drain node3

# 8. Consultar métricas de observabilidade em JSON
docker compose exec controller java -cp app.jar com.dcomp.drive.DriveCli metrics
```

## Simulação de Tolerância a Falhas

```bash
# Simular queda de um nó de armazenamento (RF5)
docker stop dcomp-node4

# Aguardar timeout do heartbeat (9s) e verificar recuperação automática
docker compose exec controller java -cp app.jar com.dcomp.drive.DriveCli status

# Reiniciar o controlador para validar recuperação persistente dos metadados (RF10)
docker compose restart controller
docker compose exec controller java -cp app.jar com.dcomp.drive.DriveCli ls /docs
```

## Vídeo de Demonstração

A demonstração prática do cluster em execução (descoberta de nós, gravação com replicação $N=3$, leitura com verificação SHA-256, tolerância a falhas e detecção de corrupção) está disponível no Google Drive:

- [Assistir à Demonstração Prática (Google Drive)](https://drive.google.com/file/d/1L4sWDF_R8jaqSHfOBEwvEr3mklsMkQjI/view?usp=sharing)