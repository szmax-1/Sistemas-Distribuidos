package com.processamento.imagens;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public class ClienteProdutor {
    private static final String QUEUE_NAME = "fila-imagens-originais";

    public static void main(String[] args) throws Exception {
        String host = System.getenv().getOrDefault("RABBITMQ_HOST", "localhost");
        String pastaCliente = args.length > 0 ? args[0] : "./clientes/cliente1";

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);

        Connection connection = conectarComRetry(factory);
        try (connection; Channel channel = connection.createChannel()) {

            channel.queueDeclare(QUEUE_NAME, true, false, false, null);

            File pasta = new File(pastaCliente);
            File[] ficheiros = pasta.listFiles((dir, name) -> 
                name.toLowerCase().endsWith(".jpg") || name.toLowerCase().endsWith(".png"));

            if (ficheiros == null || ficheiros.length == 0) {
                System.out.println("Nenhuma imagem encontrada em: " + pastaCliente);
                return;
            }

            for (File imagem : ficheiros) {
                byte[] conteudoImagem = Files.readAllBytes(imagem.toPath());

                Map<String, Object> headers = new HashMap<>();
                headers.put("filename", imagem.getName());

                AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                        .headers(headers)
                        .build();

                channel.basicPublish("", QUEUE_NAME, props, conteudoImagem);
                System.out.println("Imagem enviada para processamento: " + imagem.getName());
            }
        }
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