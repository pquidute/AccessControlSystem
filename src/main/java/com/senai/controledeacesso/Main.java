package com.senai.controledeacesso;

import java.io.*;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Scanner;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Main {
    private static final File pastaControleDeAcesso = new File(System.getProperty("user.home"), "ControleDeAcesso");

    private static final File database = new File(pastaControleDeAcesso, "database.txt");
    private static final File accessRegistersDatabase = new File(pastaControleDeAcesso, "accessRegistersDatabase.txt");
    public static final File imagesFolder = new File(pastaControleDeAcesso, "images");
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
        conexaoMQTT = new CLienteMQTT(brokerUrl, topico, Main::processarMensagemMQTTRecebida);
        servidorHTTPS = new ServidorHTTPS();
        menuPrincipal();

        scanner.close();
        executorIdentificarAcessos.shutdown();
        executorCadastroIdAcesso.shutdown();
        conexaoMQTT.desconectar();
        servidorHTTPS.pararServidorHTTPS();
    }

    private static void menuPrincipal() {
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
                    exibirCadastro();
                    break;
                case 2:
                    cadastrarUsuario();
                    break;
                case 3:
                    atualizarUsuario();
                    break;
                case 4:
                    deletarUsuario();
                    break;
                case 5:
                    aguardarCadastroDeIdAcesso();
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

    private static void aguardarCadastroDeIdAcesso() {
        modoCadastrarIdAcesso = true;
        System.out.println("Aguardando nova tag ou cartão para associar ao usuário");
        Future<?> future = executorCadastroIdAcesso.submit(() -> {
            while (modoCadastrarIdAcesso) {
                try {
                    Thread.sleep(100); // Evita uso excessivo de CPU
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

    private static void processarMensagemMQTTRecebida(String mensagem) {
        if (!modoCadastrarIdAcesso) {
            executorIdentificarAcessos.submit(() -> criarNovoRegistroDeAcesso(mensagem));
        } else {
            cadastrarNovoIdAcesso(mensagem);
            modoCadastrarIdAcesso = false;
            idUsuarioRecebidoPorHTTP = 0;
        }
    }

    // Função que busca e atualiza a tabela com o ID recebido
    private static void criarNovoRegistroDeAcesso(String accessIdReceived) {
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

    private static void cadastrarNovoIdAcesso(String novoIdAcesso) {
        boolean encontrado = false; // Variável para verificar se o usuário foi encontrado
        String idUsuarioEscolhido = String.valueOf(idUsuarioRecebidoPorHTTP);
        String dispositivoEscolhido = dispositivoRecebidoPorHTTP;

        if (idUsuarioRecebidoPorHTTP == 0) {
            // Exibe a lista de usuários para o administrador escolher
            for (String[] usuario : matrizCadastro) {
                System.out.println(usuario[0] + " - " + usuario[2]); // Exibe ID e Nome do usuário
            }
            // Pede ao administrador que escolha o ID do usuário
            System.out.print("Digite o ID do usuário para associar ao novo idAcesso: ");
            idUsuarioEscolhido = scanner.nextLine();
            conexaoMQTT.publicarMensagem(topico, dispositivoEscolhido);
        }

        modoCadastrarIdAcesso = true;
        // Verifica se o ID do usuário existe na matriz
        for (int linhas = 1; linhas < matrizCadastro.length; linhas++) {
            if (matrizCadastro[linhas][0].equals(idUsuarioEscolhido)) { // Coluna 0 é o idUsuario
                matrizCadastro[linhas][1] = novoIdAcesso; // Atualiza a coluna 1 com o novo idAcesso
                System.out.println("id de acesso " + novoIdAcesso + " associado ao usuário " + matrizCadastro[linhas][2]);
                conexaoMQTT.publicarMensagem("cadastro/disp", "CadastroConcluido");
                encontrado = true;
                salvarDadosNoArquivo();
                break;
            }
        }

        // Se não encontrou o usuário, imprime uma mensagem
        if (!encontrado) {
            System.out.println("Usuário com id" + idUsuarioEscolhido + " não encontrado.");
        }
    }

    // Funções de CRUD
    private static void exibirCadastro() {
        if (arrayRegisters.isEmpty()) {
            System.out.println("Não há cadastros registrados no sistema!");
            return;
        }
        for (int i = 0; i < arrayRegisters.size(); i++) {
            System.out.println(arrayRegisters.get(i).toString());
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

    private static void cadastrarUsuario() {
        System.out.print("Digite a quantidade de usuarios que deseja cadastrar:");
        int qtdUsuarios = scanner.nextInt();
        scanner.nextLine();

        String[][] novaMatriz = new String[matrizCadastro.length + qtdUsuarios][matrizCadastro[0].length];

        for (int linhas = 0; linhas < matrizCadastro.length; linhas++) {
            novaMatriz[linhas] = Arrays.copyOf(matrizCadastro[linhas], matrizCadastro[linhas].length);
        }

        System.out.println("\nPreencha os dados a seguir:");
        for (int linhas = matrizCadastro.length; linhas < novaMatriz.length; linhas++) {
            System.out.println(matrizCadastro[0][0] + "- " + linhas);
            novaMatriz[linhas][0] = String.valueOf(linhas);// preenche o campo id com o numero gerado pelo for
            novaMatriz[linhas][1] = "-"; //preenche o campo idCadastro com "-"

            for (int colunas = 2; colunas < matrizCadastro[0].length - 1; colunas++) {
                System.out.print(matrizCadastro[0][colunas] + ": ");
                novaMatriz[linhas][colunas] = scanner.nextLine();
            }
            novaMatriz[linhas][matrizCadastro[0].length - 1] = "-";//preenche o campo imagem com "-"

            System.out.println("-----------------------Inserido com sucesso------------------------\n");
        }
        matrizCadastro = novaMatriz;
        salvarDadosNoArquivo();
    }

    private static void atualizarUsuario() {

        exibirCadastro();
        System.out.println("Escolha um id para atualizar o cadastro:");
        int idUsuario = scanner.nextInt();
        scanner.nextLine();
        System.out.println("\nAtualize os dados a seguir:");

        System.out.println(matrizCadastro[0][0] + "- " + idUsuario);
        for (int dados = 2; dados < matrizCadastro[0].length; dados++) {
            System.out.print(matrizCadastro[0][dados] + ": ");
            matrizCadastro[idUsuario][dados] = scanner.nextLine();
        }

        System.out.println("---------Atualizado com sucesso-----------");
        exibirCadastro();
        salvarDadosNoArquivo();
    }

    public static void deletarUsuario() {
        String[][] novaMatriz = new String[matrizCadastro.length - 1][matrizCadastro[0].length];
        int idUsuario = idUsuarioRecebidoPorHTTP;
        if (idUsuarioRecebidoPorHTTP == 0) {
            exibirCadastro();
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

    // Funções para persistência de dados
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
}


