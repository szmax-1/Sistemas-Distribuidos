# Atividade 02 - Processamento de Imagens com RabbitMQ

Este projeto implementa um pipeline distribuído de processamento de imagens em escala de cinza com armazenamento redundante, utilizando Java, RabbitMQ e Docker.

---

## Arquitetura do sistema

A solução foi estruturada em três componentes principais:

1. Clientes (Produtores):
   Lêem as imagens das pastas locais (`clientes/cliente1` e `clientes/cliente2`) e enviam para a fila de trabalho `fila-imagens-originais`.

2. Servidores de Conversão (Trabalhadores / Work Queue):
   Consomem as imagens da fila original, realizam a conversão para escala de cinza preservando o nome original do arquivo e publicam o resultado em um Exchange do tipo Fanout (`exchange-imagens-convertidas`).

3. Servidores de Armazenamento (Consumidores Fanout / Redundância):
   Cada servidor de armazenamento possui uma fila própria vinculada ao Exchange Fanout. Isso garante que todos os servidores recebam uma cópia da imagem convertida e a salvem em seus respectivos diretórios (`servidores/servidor1` e `servidores/servidor2`).

---

## Estrutura de pastas

```text
atividade-02-rabbitmq-imagens/
├── clientes/
│   ├── cliente1/
│   └── cliente2/
├── servidores/
│   ├── servidor1/
│   └── servidor2/
├── src/
├── Dockerfile
├── docker-compose.yml
├── pom.xml
└── README.md
```
## Como executar a aplicação

1. Adicione pelo menos uma imagem (no formato .jpg ou .png) dentro da pasta clientes/cliente1 ou clientes/cliente2.

2. No terminal, navegue até a pasta desta atividade:
```bash
cd atividade-02-rabbitmq-imagens
```

3. Suba os containers com o Docker Compose:
```bash
docker compose up --build
```

4. Acompanhe os logs no terminal. O RabbitMQ será inicializado, os serviços de conversão e armazenamento ficarão aguardando, e os clientes enviarão as imagens.

5. Após a execução, verifique as pastas servidores/servidor1 e servidores/servidor2. As imagens enviadas pelos clientes deverão estar presentes em ambas as pastas, totalmente convertidas para tons de cinza.