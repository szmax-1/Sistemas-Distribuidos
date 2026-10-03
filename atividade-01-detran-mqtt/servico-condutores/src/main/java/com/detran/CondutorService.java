package com.detran;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;

import java.util.HashMap;
import java.util.Map;

public class CondutorService {
    private static final String BROKER_URL = "tcp://mqtt-broker:1883";
    private static final String CLIENT_ID = "ServicoCondutores";

    static class Condutor {
        String cpf;
        String nome;

        public Condutor(String cpf, String nome) {
            this.cpf = cpf;
            this.nome = nome;
        }
    }

    private static final Map<String, Condutor> condutores = new HashMap<>();

    public static void main(String[] args) {
        try {
            MqttClient client = new MqttClient(BROKER_URL, CLIENT_ID);
            client.connect();
            System.out.println("Microsserviço de Condutores ativo!");

            // Cadastrar condutor: formato "CPF;NOME"
            client.subscribe("detran/condutor/cadastrar", (topic, msg) -> {
                String[] dados = new String(msg.getPayload()).split(";");
                if (dados.length == 2) {
                    Condutor c = new Condutor(dados[0], dados[1]);
                    condutores.put(c.cpf, c);
                    System.out.println("Condutor cadastrado com sucesso: " + c.nome + " (CPF: " + c.cpf + ")");
                }
            });

        } catch (MqttException e) {
            e.printStackTrace();
        }
    }
}