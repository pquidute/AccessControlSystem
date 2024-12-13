package com.senai.controledeacesso;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Main {
    private static final File pastaControleDeAcesso = new File(System.getProperty("user.home"), "ControleDeAcesso");

    private static final File database = new File(pastaControleDeAcesso, "database.txt");
    private static final File accessRegistersDatabase = new File(pastaControleDeAcesso, "accessRegistersDatabase.txt");
    public static final File imagesFolder = new File(pastaControleDeAcesso, "images");
    static String header = String.format("| %-5s | %-15s | %-20s | %-10s | %-10s | %-6s | %-10s |",
            "ID", "NOME", "NÚMERO DE TELEFONE", "EMAIL", "IMAGE", "ATRASOS", "ID ACESSO");

    static ArrayList<Register> registersArray = new ArrayList<>();
    static ArrayList<accessRegister> accessRegistersArray = new ArrayList<>();

    static volatile boolean modoCadastrarIdAcesso = false;
    static int idUsuarioRecebidoPorHTTP = 0;
    static String dispositivoRecebidoPorHTTP = "Disp1";
    static LocalDateTime currentTime = LocalDateTime.parse(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
    static String brokerUrl = "tcp://localhost:1883";  // Exemplo de
    static String topico = "IoTKIT1/UID";

    static CLienteMQTT conexaoMQTT;
    static ServidorHTTPS servidorHTTPS;
    static Scanner scanner = new Scanner(System.in);
    static ExecutorService executorIdentificarAcessos = Executors.newFixedThreadPool(4);
    static ExecutorService executorCadastroIdAcesso = Executors.newSingleThreadExecutor();

    public static void main(String[] args) {
        verificarEstruturaDeDiretorios();
        carregarDadosDoArquivo();
        conexaoMQTT = new CLienteMQTT(brokerUrl, topico, Main::processMQTTmessageReceived);
        servidorHTTPS = new ServidorHTTPS();
        mainMenu();

        scanner.close();
        executorIdentificarAcessos.shutdown();
        executorCadastroIdAcesso.shutdown();
        conexaoMQTT.desconectar();
        servidorHTTPS.pararServidorHTTPS();
    }

    private static void mainMenu() {
        int opcao;
        System.out.println("            ------BEM VINDO------          ");
        do {
            String menu = """
                    _________________________________________________________
                    |   Escolha uma opção:                                  |
                    |       1- Exibir cadastros                             |
                    |       2- Inserir novo cadastro                        |
                    |       3- Atualizar cadastro                           |
                    |       4- Deletar um cadastro                          |
                    |       5- Associar TAG/cartão de acesso à usuário      |
                    |       6- Deletar registros de acesso                  |
                    |       7- Sair                                         |
                    _________________________________________________________
                    """;
            System.out.println(menu);
            opcao = scanner.nextInt();
            scanner.nextLine();

            switch (opcao) {
                case 1:
                    displayRegisters();
                    break;
                case 2:
                    registerUser();
                    break;
                case 3:
                    updateUser();
                    break;
                case 4:
                    deletarUsuario();
                    break;
                case 5:
                    waitAccessIdRegister();
                    break;
                case 6:
                    apagarRegistrosDeAcesso();
                    break;
                case 7:
                    System.out.println("Fim do programa!");
                    break;
                default:
                    System.out.println("Opção inválida!");
            }

        } while (opcao != 7);
    }

    private static void waitAccessIdRegister() {
        modoCadastrarIdAcesso = true;
        System.out.println("Aguardando nova tag ou cartão para associar ao usuário");
        Future<?> future = executorCadastroIdAcesso.submit(() -> {
            while (modoCadastrarIdAcesso) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        try {
            future.get();
        } catch (Exception e) {
            System.err.println("Erro ao aguardar cadastro: " + e.getMessage());
        }
    }
    private static void processMQTTmessageReceived(String message) {
        if (!modoCadastrarIdAcesso) {
            executorIdentificarAcessos.submit(() -> createNewAccessRegister(message));
        } else {
            registerNewAccessId(message);
            modoCadastrarIdAcesso = false;
            idUsuarioRecebidoPorHTTP = 0;
        }
    }
    private static void createNewAccessRegister(String accessIdReceived) {
        boolean usuarioEncontrado = false;
        for (int i = 0; i < registersArray.size(); i++) {
            if (registersArray.get(i).accessRegister.accessId == Integer.parseInt(accessIdReceived)){
                registersArray.get(i).accessRegister.registerNewAccess(String.valueOf(currentTime));
                usuarioEncontrado = true;
                break;
            }
            if (!usuarioEncontrado){
                System.out.println("O ID de acesso recebido não está associado à nenhum usuário!");
            }
            break;
        }
    }
    private static void registerNewAccessId(String newAccessId) {
        boolean found = false;
        String userID= String.valueOf(idUsuarioRecebidoPorHTTP);
        String chosenDevice = dispositivoRecebidoPorHTTP;

        if (idUsuarioRecebidoPorHTTP == 0) {
            displayRegisters();
            System.out.print("Digite o ID do usuário para associar ao novo idAcesso: ");
            userID = scanner.nextLine();
            conexaoMQTT.publicarMensagem(topico, chosenDevice);
        }
        modoCadastrarIdAcesso = true;
        for (int i = 0; i < registersArray.size(); i++) {
            if (registersArray.get(i).ID == Integer.parseInt(userID)){
                System.out.println("ID de acesso " + newAccessId + " associado ao usuário " + registersArray.get(i).name);
                conexaoMQTT.publicarMensagem("cadastro/disp", "CadastroConcluido");
                found = true;
                salvarDadosNoArquivo();
                break;
            }
        }
        System.out.println("Usuário não encontrado!");
        }

    private static void displayRegisters() {
        if (registersArray.isEmpty()) {
            System.out.println("Não há cadastros registrados no sistema!");
            return;
        }
        System.out.println(header);
        for (int i = 0; i < registersArray.size(); i++) {
            System.out.println(registersArray.get(i).toString());
        }
        System.out.println("Exibir registros de acesso?\n1. Sim\n2. Não");
        int menu = scanner.nextInt();
        switch (menu) {
            case 1:
                exibirRegistrosDeAcesso();
                break;
            case 2:
                break;
            default:
                System.out.println("Opção inválida");
                break;
        }
    }
    private static void registerUser() {
        System.out.print("Digite a quantidade de usuarios que deseja cadastrar:");
        int users = scanner.nextInt();
        scanner.nextLine();

        for (int i = 0; i < users; i++) {
            System.out.print("ID: ");
            int id = scanner.nextInt();
            scanner.nextLine();
            System.out.print("NOME: ");
            String name = scanner.nextLine();
            System.out.print("TELEFONE: ");
            String phoneNumber = scanner.nextLine();
            System.out.print("EMAIL: ");
            String email = scanner.nextLine();
            System.out.println("Cadastrar ID de acesso?\n1. Sim\t\t\t2. Não");
            int menu = scanner.nextInt();
            scanner.nextLine();
            switch (menu) {
                case 1:
                    System.out.print("ID de acesso: ");
                    int accessId = scanner.nextInt();
                    registersArray.add(new Register(id, name, phoneNumber, email));
                    for (int j = 0; j < registersArray.size(); j++) {
                        if (registersArray.get(i).ID == id) {
                            registersArray.get(i).accessRegister.accessId = accessId;
                        }
                    }
                    System.out.println("Cadastro concluído!");
                    break;
                case 2:
                    registersArray.add(new Register(id, name, phoneNumber, email));
                    System.out.println("Cadastro concluído!");
                    break;
                default:
                    System.out.println("Opção inválida");
            }
        }
    }
    private static void updateUser() {
        displayRegisters();
        System.out.print("Digite o ID do usuário que será atualizado: ");
        int userID = scanner.nextInt();
        scanner.nextLine();
        for (int i = 0; i < registersArray.size(); i++) {
            if (registersArray.get(i).ID == userID) {
                System.out.println("\nAtualize os dados a seguir:");
                System.out.print("ID: ");
                int id = scanner.nextInt();
                scanner.nextLine();
                System.out.print("NOME: ");
                String name = scanner.nextLine();
                System.out.print("TELEFONE: ");
                String phoneNumber = scanner.nextLine();
                System.out.print("EMAIL: ");
                String email = scanner.nextLine();
                System.out.print("ID DE ACESSO: ");
                int accessId = scanner.nextInt();
                registersArray.get(i).ID = id;
                registersArray.get(i).name = name;
                registersArray.get(i).phoneNumber = phoneNumber;
                registersArray.get(i).email = email;
                registersArray.get(i).accessRegister.accessId = accessId;
                System.out.println("Usuário atualizado com sucesso!");
                return;
            }
            System.out.println("Usuário não encontrado!");
        }
    }
    public static void deletarUsuario() {
        String[][] novaMatriz = new String[matrizCadastro.length - 1][matrizCadastro[0].length];
        int idUsuario = idUsuarioRecebidoPorHTTP;
        if (idUsuarioRecebidoPorHTTP == 0) {
            displayRegisters();
            System.out.println("Escolha um id para deletar o cadastro:");
            idUsuario = scanner.nextInt();
            scanner.nextLine();
        }

        for (int i = 1, j = 1; i < matrizCadastro.length; i++) {
            if (i == idUsuario)
                continue;
            novaMatriz[j] = matrizCadastro[i];
            novaMatriz[j][0] = String.valueOf(j);
            j++;
        }

        matrizCadastro = novaMatriz;
        matrizCadastro[0] = cabecalho;
        salvarDadosNoArquivo();
        System.out.println("-----------------------Deletado com sucesso------------------------\n");
        idUsuarioRecebidoPorHTTP = 0;
    }

    private static void carregarDadosDoArquivo() {

        try (BufferedReader reader = new BufferedReader(new FileReader(arquivoBancoDeDados))) {
            String linha;
            StringBuilder conteudo = new StringBuilder();

            while ((linha = reader.readLine()) != null) {
                if (!linha.trim().isEmpty()) {
                    conteudo.append(linha).append("\n");
                }
            }

            if (!conteudo.toString().trim().isEmpty()) {
                String[] linhasDaTabela = conteudo.toString().split("\n");
                matrizCadastro = new String[linhasDaTabela.length][cabecalho.length];
                for (int i = 0; i < linhasDaTabela.length; i++) {
                    matrizCadastro[i] = linhasDaTabela[i].split(",");
                }
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        matrizCadastro[0] = cabecalho;
    }

    public static void salvarDadosNoArquivo() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(arquivoBancoDeDados))) {
            for (String[] linha : matrizCadastro) {
                writer.write(String.join(",", linha) + "\n");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(databaseRegistrosDeAcesso))) {
            for (String[] linha : matrizRegistrosDeAcesso) {
                writer.write(String.join(",", linha) + "\n");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void verificarEstruturaDeDiretorios() {
        // Verifica se a pasta ControleDeAcesso existe, caso contrário, cria
        if (!pastaControleDeAcesso.exists()) {
            if (pastaControleDeAcesso.mkdir()) {
                System.out.println("Pasta ControleDeAcesso criada com sucesso.");
            } else {
                System.out.println("Falha ao criar a pasta ControleDeAcesso.");
            }
        }

        // Verifica se o arquivo bancoDeDados.txt existe, caso contrário, cria
        if (!arquivoBancoDeDados.exists()) {
            try {
                if (arquivoBancoDeDados.createNewFile()) {
                    System.out.println("Arquivo bancoDeDados.txt criado com sucesso.");
                } else {
                    System.out.println("Falha ao criar o arquivo bancoDeDados.txt.");
                }
            } catch (IOException e) {
                System.out.println("Erro ao criar arquivo bancoDeDados.txt: " + e.getMessage());
            }
        }

        if (!databaseRegistrosDeAcesso.exists()) {
            try {
                if (databaseRegistrosDeAcesso.createNewFile()) {
                    System.out.println("Arquivo registrosDeAcesso.txt criado com sucesso.");
                } else {
                    System.out.println("Falha ao criar o arquivo registrosDeAcesso.txt.");
                }
            } catch (IOException e) {
                System.out.println("Erro ao criar arquivo registrosDeAcesso.txt: " + e.getMessage());
            }
        }

        // Verifica se a pasta imagens existe, caso contrário, cria
        if (!pastaImagens.exists()) {
            if (pastaImagens.mkdir()) {
                System.out.println("Pasta imagens criada com sucesso.");
            } else {
                System.out.println("Falha ao criar a pasta imagens.");
            }
        }
    }

    private static void apagarRegistrosDeAcesso() {
        System.out.println("---REMOÇÃO DE REGISTROS DE ACESSO---");
        System.out.println("Tem certeza que deseja apagar TODOS os registros de acesso?\n1. Sim\n2. Não");
        int menu = scanner.nextInt();
        switch (menu) {
            case 1:
                String[][] novaMatriz = new String[][]{{"ID", "DATA", "HORA"}};
                matrizRegistrosDeAcesso = novaMatriz;
                break;
            case 2:
                break;
            default:
                System.out.println("Opção inválida!");
                break;
        }
    }

    private static void exibirRegistrosDeAcesso() {
        if (arrayRegisters.isEmpty()) {
            System.out.println("Não há registros de acesso no sistema!");
            return;
        }
        for (int i = 0; i < arrayAccessRegisters.size(); i++) {
            System.out.println(arrayAccessRegisters.get(i).toString());
        }
    }