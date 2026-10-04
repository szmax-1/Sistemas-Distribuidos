package com.detran;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.util.*;

public class VeiculoService {
    private static final String BROKER_URL = "tcp://mqtt-broker:1883";
    private static final String CLIENT_ID = "ServicoVeiculos";

    // Estrutura simples para armazenar veículos em memória
    static class Veiculo {
        String placa;
        String modelo;
        double valor;
        String cpfCondutor;
        int ano;

        public Veiculo(String placa, String modelo, double valor, String cpfCondutor, int ano) {
            this.placa = placa;
            this.modelo = modelo;
            this.valor = valor;
            this.cpfCondutor = cpfCondutor;
            this.ano = ano;
        }
    }

    private static final Map<String, Veiculo> veiculos = new HashMap<>();

    public static void main(String[] args) {
        try {
            MqttClient client = new MqttClient(BROKER_URL, CLIENT_ID);
            client.connect();
            System.out.println("Microsserviço de Veículos ativo!");

            // Emplacar veículo no formato "PLACA;MODELO;VALOR;CPF;ANO"
            client.subscribe("detran/veiculo/emplacar", (topic, msg) -> {
                String[] dados = new String(msg.getPayload()).split(";");
                if (dados.length == 5) {
                    Veiculo v = new Veiculo(dados[0], dados[1], Double.parseDouble(dados[2]), dados[3], Integer.parseInt(dados[4]));
                    veiculos.put(v.placa, v);
                    System.out.println("Veículo emplacado: " + v.placa + " (" + v.modelo + ")");
                }
            });

            // Calcular IPVA 
            client.subscribe("detran/veiculo/ipva", (topic, msg) -> {
                String placa = new String(msg.getPayload());
                Veiculo v = veiculos.get(placa);
                if (v != null) {
                    double ipva = v.valor * 0.02;
                    System.out.println("IPVA do veículo " + placa + ": R$ " + ipva);
                } else {
                    System.out.println("Veículo não encontrado: " + placa);
                }
            });

            // Transferir proprietário
            client.subscribe("detran/veiculo/transferir", (topic, msg) -> {
                String[] dados = new String(msg.getPayload()).split(";");
                if (dados.length == 2 && veiculos.containsKey(dados[0])) {
                    Veiculo v = veiculos.get(dados[0]);
                    v.cpfCondutor = dados[1];
                    System.out.println("Proprietário do veículo " + v.placa + " alterado para CPF: " + v.cpfCondutor);
                }
            });

            // Listar por ano
            client.subscribe("detran/veiculo/listar_por_ano", (topic, msg) -> {
                int ano = Integer.parseInt(new String(msg.getPayload()));
                System.out.println("--- Veículos emplacados no ano " + ano + " ---");
                veiculos.values().stream()
                        .filter(v -> v.ano == ano)
                        .forEach(v -> System.out.println("Placa: " + v.placa + " | Modelo: " + v.modelo + " | CPF: " + v.cpfCondutor));
            });

        } catch (MqttException e) {
            e.printStackTrace();
        }
    }
}
