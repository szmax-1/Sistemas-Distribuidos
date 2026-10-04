package com.processamento.imagens;

import com.rabbitmq.client.*;

import java.io.File;
import java.nio.file.Files;

public class ServidorArmazenamento {
    private static final String EXCHANGE_FANOUT = "exchange-imagens-convertidas";

    public static void main(String[] args) throws Exception {
        String host = System.getenv().getOrDefault("RABBITMQ_HOST", "localhost");
        String pastaServidor = args.length > 0 ? args[0] : "./servidores/servidor1";

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);

        Connection connection = conectarComRetry(factory);
        Channel channel = connection.createChannel();

        channel.exchangeDeclare(EXCHANGE_FANOUT, BuiltinExchangeType.FANOUT, true);
        
        String queueName = channel.queueDeclare().getQueue();
        channel.queueBind(queueName, EXCHANGE_FANOUT, "");

        File pastaDestino = new File(pastaServidor);
        if (!pastaDestino.exists()) {
            pastaDestino.mkdirs();
        }

        System.out.println("Servidor de Armazenamento ativo em: " + pastaServidor);

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String filename = delivery.getProperties().getHeaders().get("filename").toString();
            File arquivoSaida = new File(pastaDestino, filename);

            Files.write(arquivoSaida.toPath(), delivery.getBody());
            System.out.println("Imagem armazenada com sucesso: " + arquivoSaida.getAbsolutePath());
        };

        channel.basicConsume(queueName, true, deliverCallback, consumerTag -> {});
    }

    private static Connection conectarComRetry(ConnectionFactory factory) {
        while (true) {
            try {
                return factory.newConnection();
            } catch (Exception e) {
                System.out.println("Aguardando RabbitMQ ficar disponível na porta 5672...");
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            }
        }
    }
}