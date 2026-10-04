package com.detran;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;

import java.util.*;
import java.util.stream.Collectors;

public class MultaService {
    private static final String BROKER_URL = "tcp://mqtt-broker:1883";
    private static final String CLIENT_ID = "ServicoMultas";

    static class Multa {
        int ano;
        String descricao;
        int pontuacao;
        String placa;
        String cpfCondutor;

        public Multa(int ano, String descricao, int pontuacao, String placa, String cpfCondutor) {
            this.ano = ano;
            this.descricao = descricao;
            this.pontuacao = pontuacao;
            this.placa = placa;
            this.cpfCondutor = cpfCondutor;
        }
    }

    private static final List<Multa> multas = new ArrayList<>();

    public static void main(String[] args) {
        try {
            MqttClient client = new MqttClient(BROKER_URL, CLIENT_ID);
            client.connect();
            System.out.println("Microsserviço de Multas ativo!");

            // Lançar multa, o formato é "ANO;DESCRICAO;PONTUACAO;PLACA;CPF_CONDUTOR"
            client.subscribe("detran/multa/lancar", (topic, msg) -> {
                String[] dados = new String(msg.getPayload()).split(";");
                if (dados.length == 5) {
                    Multa m = new Multa(
                        Integer.parseInt(dados[0]),
                        dados[1],
                        Integer.parseInt(dados[2]),
                        dados[3],
                        dados[4]
                    );
                    multas.add(m);
                    System.out.println("Multa registrada no valor de " + m.pontuacao + " pontos para o veículo " + m.placa);
                }
            });

            // Multas cometidas por um veículo num ano
            client.subscribe("detran/multa/listar_por_veiculo", (topic, msg) -> {
                String[] dados = new String(msg.getPayload()).split(";");
                if (dados.length == 2) {
                    String placa = dados[0];
                    int ano = Integer.parseInt(dados[1]);
                    System.out.println("--- Multas do veículo " + placa + " no ano " + ano + " ---");
                    multas.stream()
                            .filter(m -> m.placa.equals(placa) && m.ano == ano)
                            .forEach(m -> System.out.println("Descrição: " + m.descricao + " | Pontos: " + m.pontuacao + " | Condutor (CPF): " + m.cpfCondutor));
                }
            });

            // Multas de um condutor num ano
            client.subscribe("detran/multa/listar_por_condutor", (topic, msg) -> {
                String[] dados = new String(msg.getPayload()).split(";");
                if (dados.length == 2) {
                    String cpf = dados[0];
                    int ano = Integer.parseInt(dados[1]);
                    System.out.println("--- Multas do condutor CPF " + cpf + " no ano " + ano + " ---");
                    multas.stream()
                            .filter(m -> m.cpfCondutor.equals(cpf) && m.ano == ano)
                            .forEach(m -> System.out.println("Placa: " + m.placa + " | Descrição: " + m.descricao + " | Pontos: " + m.pontuacao));
                }
            });

            // Multas lançadas num ano
            client.subscribe("detran/multa/listar_por_ano", (topic, msg) -> {
                int ano = Integer.parseInt(new String(msg.getPayload()));
                System.out.println("--- Todas as multas do ano " + ano + " ---");
                multas.stream()
                        .filter(m -> m.ano == ano)
                        .forEach(m -> System.out.println("Placa: " + m.placa + " | CPF: " + m.cpfCondutor + " | Descrição: " + m.descricao + " | Pontos: " + m.pontuacao));
            });

            // Ranking dos 5 condutores com maiores pontuações
            client.subscribe("detran/multa/top5", (topic, msg) -> {
                System.out.println("--- Top 5 Condutores com Maior Pontuação ---");
                Map<String, Integer> pontuacaoPorCondutor = new HashMap<>();
                for (Multa m : multas) {
                    pontuacaoPorCondutor.put(m.cpfCondutor, pontuacaoPorCondutor.getOrDefault(m.cpfCondutor, 0) + m.pontuacao);
                }

                pontuacaoPorCondutor.entrySet().stream()
                        .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                        .limit(5)
                        .forEach(e -> System.out.println("CPF: " + e.getKey() + " | Pontos Totais: " + e.getValue()));
            });

        } catch (MqttException e) {
            e.printStackTrace();
        }
    }
}
