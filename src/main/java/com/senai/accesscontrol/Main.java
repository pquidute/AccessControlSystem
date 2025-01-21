package com.senai.accesscontrol;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Main {
    private static final File accessControlFolder = new File(System.getProperty("user.home"), "ControleDeAcesso");

    private static final File database = new File(accessControlFolder, "database.txt");
    private static final File accessRegistersDatabase = new File(accessControlFolder, "accessRegistersDatabase.txt");
    public static final File imagesFolder = new File(accessControlFolder, "images");
    static String header = String.format("| %-5s | %-15s | %-20s | %-10s | %-10s | %-6s | %-10s |",
            "ID", "NOME", "NÚMERO DE TELEFONE", "EMAIL", "IMAGEM", "ATRASOS", "ID DE ACESSO");

    static ArrayList<Register> registersArray = new ArrayList<>();
    static ArrayList<AccessRegister> accessRegistersArray = new ArrayList<>();

    static volatile boolean registerAccessIdMode = false;
    static int userIdReceivedByHTTP = 0;
    static String deviceReceivedByHTTP = "Disp1";
    static LocalDateTime currentTime = LocalDateTime.parse(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
    static String brokerUrl = "tcp://localhost:1883";  // Exemplo de
    static String topic = "IoTKIT1/UID";

    static MQTTClient mqttConnection;
    static HTTPSServer httpsServer;
    static Scanner scanner = new Scanner(System.in);
    static ExecutorService accessIdentifierExecutor = Executors.newFixedThreadPool(4);
    static ExecutorService accessIdRegisterExecutor = Executors.newSingleThreadExecutor();

    public static void main(String[] args) {
        checkDirectoriesStructure();
        loadData();
        mqttConnection = new MQTTClient(brokerUrl, topic, Main::processMQTTreceivedMessage);
        httpsServer = new HTTPSServer();
        mainMenu();

        scanner.close();
        accessIdentifierExecutor.shutdown();
        accessIdRegisterExecutor.shutdown();
        mqttConnection.desconectar();
        httpsServer.stopHTTPSServer();
    }

    private static void mainMenu() {
        int option;
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
            option = scanner.nextInt();
            scanner.nextLine();

            switch (option) {
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
                    removeUser();
                    break;
                case 5:
                    waitAccessIdRegister();
                    break;
                case 6:
                    removeAccessRegisters();
                    break;
                case 7:
                    System.out.println("Fim do programa!");
                    break;
                default:
                    System.out.println("Opção inválida!");
            }

        } while (option != 7);
    }

    private static void waitAccessIdRegister() {
        registerAccessIdMode = true;
        System.out.println("Aguardando nova tag ou cartão para associar ao usuário");
        Future<?> future = accessIdRegisterExecutor.submit(() -> {
            while (registerAccessIdMode) {
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
    private static void processMQTTreceivedMessage(String message) {
        if (!registerAccessIdMode) {
            accessIdentifierExecutor.submit(() -> createNewAccessRegister(message));
        } else {
            registerNewAccessId(message);
            registerAccessIdMode = false;
            userIdReceivedByHTTP = 0;
        }
    }
    private static void createNewAccessRegister(String accessIdReceived) {
        boolean userFound = false;
        for (int i = 0; i < registersArray.size(); i++) {
            if (registersArray.get(i).accessRegister.accessId == Integer.parseInt(accessIdReceived)){
                registersArray.get(i).accessRegister.registerNewAccess(String.valueOf(currentTime));
                userFound = true;
                break;
            }
            if (!userFound){
                System.out.println("O ID de acesso recebido não está associado à nenhum usuário!");
            }
            break;
        }
    }
    private static void registerNewAccessId(String newAccessId) {
        boolean found = false;
        String userID= String.valueOf(userIdReceivedByHTTP);
        String chosenDevice = deviceReceivedByHTTP;

        if (userIdReceivedByHTTP == 0) {
            displayRegisters();
            System.out.print("Digite o ID do usuário para associar ao novo idAcesso: ");
            userID = scanner.nextLine();
            mqttConnection.publicarMensagem(topic, chosenDevice);
        }
        registerAccessIdMode = true;
        for (int i = 0; i < registersArray.size(); i++) {
            if (registersArray.get(i).ID == Integer.parseInt(userID)){
                System.out.println("ID de acesso " + newAccessId + " associado ao usuário " + registersArray.get(i).name);
                mqttConnection.publicarMensagem("cadastro/disp", "CadastroConcluido");
                found = true;
                saveData();
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
                showAccessRegister();
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
        int numberOfUsers = scanner.nextInt();
        scanner.nextLine();

        for (int i = 0; i < numberOfUsers; i++) {
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
    public static void removeUser() {
        int userID = userIdReceivedByHTTP;
        if (userIdReceivedByHTTP == 0) {
            displayRegisters();
            System.out.println("Escolha um id para deletar o cadastro:");
            userID = scanner.nextInt();
            scanner.nextLine();
        }
        for (int i = 0; i < registersArray.size(); i++) {
            if (registersArray.get(i).ID == userID){
                registersArray.remove(i);
                saveData();
                System.out.println("-----------------------Deletado com sucesso------------------------\n");
                break;
            }
        }
        System.out.println("Usuário não encontrado");
        userIdReceivedByHTTP = 0;
    }

    private static void loadData() {
        try (BufferedReader reader = new BufferedReader(new FileReader(database))) {
            String line;
            reader.readLine();
            while ((line = reader.readLine()) != null) {
                String[] data = line.split(",");
                if (data.length >= 7) {
                    int id = Integer.parseInt(data[0]);
                    String name = data[1];
                    String email = data[2];
                    String phoneNumber = data[3];
                    String image = data[4];
                    int delays = Integer.parseInt(data[5]);
                    int accessId = Integer.parseInt(data[6]);

                    Register register = new Register(id, name, phoneNumber, email);

                    try (BufferedReader reader2 = new BufferedReader(new FileReader(accessRegistersDatabase))) {
                        String line2;
                        while ((line2 = reader2.readLine()) != null) {
                            String[] parts = line2.split(",", 3);
                            if (parts.length == 3) {
                                int accessId2 = Integer.parseInt(parts[0]);
                                if (accessId == accessId2) {
                                    String[] accesses = parts[1].split(";");
                                    String delays2 = parts[2];
                                    AccessRegister accessRegister = new AccessRegister(accessId2);
                                    for (String access : accesses) {
                                        accessRegister.accessesArray.add(access);
                                    }
                                    accessRegister.delays = delays2;
                                    register.accessRegister = accessRegister;
                                }
                            }
                        }
                    } catch (IOException e) {
                        throw new RuntimeException("Erro ao ler dados de registros de acesso: " + e.getMessage(), e);
                    }
                } else {
                    System.err.println("Erro: Linha com número insuficiente de campos: " + line);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Erro ao ler os dados de cadastros: " + e.getMessage(), e);
        }
    }
    public static void saveData() {
        //save registers data
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(database))) {
            for (Register register : registersArray) {
                StringBuilder line = new StringBuilder();
                line.append(register.ID).append(",");
                line.append(register.name).append(",");
                line.append(register.email).append(",");
                line.append(register.phoneNumber).append(",");
                line.append(register.image).append(",");
                line.append(register.accessRegister.delays).append(",");
                line.append(register.accessRegister.accessId).append(",");
                writer.write(line.toString());
                writer.newLine();
            }
        } catch (IOException e) {
            throw new RuntimeException("Erro ao salvar dados de cadastros: " + e.getMessage(), e);
        }
        //save access registers data
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(accessRegistersDatabase))) {
            for (AccessRegister accessRegister : accessRegistersArray) {
                StringBuilder line = new StringBuilder();
                line.append(accessRegister.accessId).append(",");
                line.append(accessRegister.delays).append(",");
                String accesses = String.join(";", accessRegister.accessesArray);
                line.append(accesses).append(",");

                writer.write(line.toString());
                writer.newLine();
            }
        } catch (IOException e) {
            throw new RuntimeException("Erro ao salvar dados de registro de acesso: " + e.getMessage(), e);
        }

    }
    private static void checkDirectoriesStructure() {
        if (!accessControlFolder.exists()) {
            if (accessControlFolder.mkdir()) {
                System.out.println("Pasta ControleDeAcesso criada com sucesso.");
            } else {
                System.out.println("Falha ao criar a pasta ControleDeAcesso.");
            }
        }
        if (!database.exists()) {
            try {
                if (database.createNewFile()) {
                    System.out.println("Arquivo database.txt criado com sucesso.");
                } else {
                    System.out.println("Falha ao criar o arquivo database.txt.");
                }
            } catch (IOException e) {
                System.out.println("Erro ao criar arquivo database.txt: " + e.getMessage());
            }
        }
        if (!accessRegistersDatabase.exists()) {
            try {
                if (accessRegistersDatabase.createNewFile()) {
                    System.out.println("Arquivo accessRegistersDatabase.txt criado com sucesso.");
                } else {
                    System.out.println("Falha ao criar o arquivo accessRegistersDatabase.txt.");
                }
            } catch (IOException e) {
                System.out.println("Erro ao criar arquivo accessRegistersDatabase.txt: " + e.getMessage());
            }
        }
        if (!imagesFolder.exists()) {
            if (imagesFolder.mkdir()) {
                System.out.println("Pasta imagesFolder criada com sucesso.");
            } else {
                System.out.println("Falha ao criar a pasta imagesFolder.");
            }
        }
    }

    private static void removeAccessRegisters() {
        System.out.println("---REMOÇÃO DE REGISTROS DE ACESSO---");
        //TODO - allow user to remove access registers of specific users
        System.out.println("Tem certeza que deseja apagar TODOS os registros de acesso?\n1. Sim\n2. Não");
        int menu = scanner.nextInt();
        switch (menu) {
            case 1:
                for (int i = 0; i < registersArray.size(); i++) {
                    registersArray.get(i).accessRegister.accessesArray.clear();
                    registersArray.get(i).accessRegister.delays = String.valueOf(0);
                }
                System.out.println("Registros de acesso apagados com sucesso!");
                break;
            case 2:
                break;
            default:
                System.out.println("Opção inválida!");
                break;
        }
    }

    private static void showAccessRegister() {
        if (accessRegistersArray.isEmpty()) {
            System.out.println("Não há registros de acesso no sistema!");
            return;
        }
        for (int i = 0; i < accessRegistersArray.size(); i++) {

        }
    }
}