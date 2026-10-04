package com.processamento.imagens;

import com.rabbitmq.client.*;

import javax.imageio.ImageIO;
import java.awt.color.ColorSpace;
import java.awt.image.ColorConvertOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class ServidorConversao {
    private static final String QUEUE_ORIGINAIS = "fila-imagens-originais";
    private static final String EXCHANGE_FANOUT = "exchange-imagens-convertidas";

    public static void main(String[] args) throws Exception {
        String host = System.getenv().getOrDefault("RABBITMQ_HOST", "localhost");

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);

        Connection connection = conectarComRetry(factory);
        Channel channel = connection.createChannel();

        channel.queueDeclare(QUEUE_ORIGINAIS, true, false, false, null);
        channel.exchangeDeclare(EXCHANGE_FANOUT, BuiltinExchangeType.FANOUT, true);

        System.out.println("Servidor de Conversão aguardando imagens...");

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String filename = delivery.getProperties().getHeaders().get("filename").toString();
            System.out.println("Processando imagem: " + filename);

            try {
                byte[] imagemCinza = converterParaTonsDeCinza(delivery.getBody(), filename);

                Map<String, Object> headers = new HashMap<>();
                headers.put("filename", filename);

                AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                        .headers(headers)
                        .build();

                channel.basicPublish(EXCHANGE_FANOUT, "", props, imagemCinza);
                System.out.println("Imagem convertida e enviada ao Exchange Fanout: " + filename);
                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            } catch (Exception e) {
                System.err.println("Erro ao converter imagem: " + e.getMessage());
            }
        };

        channel.basicConsume(QUEUE_ORIGINAIS, false, deliverCallback, consumerTag -> {});
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

    private static byte[] converterParaTonsDeCinza(byte[] dadosOriginal, String filename) throws IOException {
        BufferedImage imgOriginal = ImageIO.read(new ByteArrayInputStream(dadosOriginal));
        
        BufferedImage imgCinza = new BufferedImage(
                imgOriginal.getWidth(), imgOriginal.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        
        ColorConvertOp op = new ColorConvertOp(ColorSpace.getInstance(ColorSpace.CS_GRAY), null);
        op.filter(imgOriginal, imgCinza);

        String formato = filename.substring(filename.lastIndexOf('.') + 1);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(imgCinza, formato, baos);
        return baos.toByteArray();
    }
}