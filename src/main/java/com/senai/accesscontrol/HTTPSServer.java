package com.senai.accesscontrol;

import com.sun.net.httpserver.*;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.stream.Collectors;

public class HTTPSServer {

    private HttpsServer server;

    public HTTPSServer() {
        startHttpsServer();
    }

    public void startHttpsServer() {
        try {
            System.out.println("Iniciando o servidor HTTPS...");

            // SSL Context Config
            SSLContext sslContext = SSLContext.getInstance("TLS");

            // Keystore Config
            char[] password = "1234abcd".toCharArray();  // Defina a senha do seu keystore
            KeyStore ks = KeyStore.getInstance("JKS");
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream("security/keystoreSenai.jks");

            if (inputStream == null) {
                System.out.println("Erro: Keystore não encontrado no caminho especificado em 'src/main/resources/security/keystoreSenai.jks'.");
                throw new FileNotFoundException("Keystore não encontrado.");
            }

            ks.load(inputStream, password);
            inputStream.close();

            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("SunX509");
            keyManagerFactory.init(ks, password);

            sslContext.init(keyManagerFactory.getKeyManagers(), null, null);

            // HttpsServer configuration with address "0.0.0.0" to allow external access
            server = HttpsServer.create(new InetSocketAddress("0.0.0.0", 8000), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                public void configure(HttpsParameters params) {
                    try {
                        SSLContext context = getSSLContext();
                        SSLEngine engine = context.createSSLEngine();
                        params.setNeedClientAuth(false);
                        params.setCipherSuites(engine.getEnabledCipherSuites());
                        params.setProtocols(engine.getEnabledProtocols());

                        SSLParameters sslParameters = context.getDefaultSSLParameters();
                        params.setSSLParameters(sslParameters);
                    } catch (Exception ex) {
                        System.out.println("Erro ao configurar o SSLContext para HTTPS.");
                        ex.printStackTrace();
                    }
                }
            });

            // Configure Handlers to routes
            server.createContext("/", new HomeHandler());
            server.createContext("/server-ip", new ServerIpHandler());
            server.createContext("/atualizacao", new updateHandler());
            server.createContext("/cadastros", new CadastroListHandler());
            server.createContext("/cadastro", new CadastroHandler());
            server.createContext("/iniciarRegistroTag", new IniciarRegistroTagHandler());
            server.createContext("/verificarStatusTag", new VerificarStatusTagHandler());
            server.createContext("/imagens", new ImagemHandler());
            server.createContext("/cadastro/atualizar/", new UpdateCadastroHandler());
            server.createContext("/cadastro/deletar/", new DeleteCadastroHandler());
            server.createContext("/imagens/deletar/", new ImagemDeleteHandler());


            server.setExecutor(null); // Without personalized thread pool
            server.start();
            System.out.println("Servidor HTTPS iniciado na porta 8000 e acessível em todas as interfaces de rede.");

        } catch (Exception e) {
            System.out.println("Erro ao iniciar o servidor HTTPS.");
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    // Method to stop server
    public void stopHTTPSServer() {
        if (server != null) {
            server.stop(0);
            System.out.println("Servidor HTTP parado.");
        }
    }

    // Handler to serve HTML, CSS & JS files from the specified directory
    private class HomeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            System.out.println("Requisição recebida na rota: " + exchange.getRequestURI().getPath());

            String requestedPath = exchange.getRequestURI().getPath();

            // Relative path to webapp folder
            String relativeWebappPath = "webapp";
            String requestedFilePath;

            if ("/".equals(requestedPath) || requestedPath.startsWith("/index.html")) {
                requestedFilePath = relativeWebappPath + "/index.html";
            } else {
                requestedFilePath = relativeWebappPath + requestedPath;
            }

            // Tria to load the file as a resource inside JAR
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream(requestedFilePath);

            if (inputStream == null) {
                System.out.println("Arquivo não encontrado no classpath. Tentando carregar do sistema de arquivos.");

                // Alternative path to the files system for the development environment
                File arquivoLocal = new File("src/main/" + requestedFilePath);
                if (arquivoLocal.exists() && arquivoLocal.isFile()) {
                    inputStream = new FileInputStream(arquivoLocal);
                    System.out.println("Arquivo encontrado no sistema de arquivos: " + arquivoLocal.getPath());
                }
            }

            if (inputStream == null) {
                System.out.println("Arquivo não encontrado, retornando página 404.");
                // "Page not found" (Serves a HTML error page)
                String html404 = """
                        <html>
                            <head>
                                <title>Página não encontrada</title>
                                <style>
                                    body { font-family: Arial, sans-serif; text-align: center; padding: 50px; }
                                    h1 { color: #ff0000; }
                                </style>
                            </head>
                            <body>
                                <h1>404 - Página não encontrada</h1>
                                <p>A página que você está tentando acessar não foi encontrada no servidor.</p>
                            </body>
                        </html>
                        """;
                byte[] bytesResposta = html404.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(404, bytesResposta.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytesResposta);
                }
            } else {
                // Reads the content of the file and sends a response
                byte[] bytesResposta = inputStream.readAllBytes();
                inputStream.close();

                // Sets the MIME type based on the file extension
                String mimeType = Files.probeContentType(Paths.get(requestedFilePath));
                exchange.getResponseHeaders().set("Content-Type", mimeType != null ? mimeType : "application/octet-stream");
                exchange.sendResponseHeaders(200, bytesResposta.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytesResposta);
                }
            }
            exchange.close();
        }
    }

    // Handler to provide the server IP to frontend
    private class ServerIpHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String serverIp = "https://" + exchange.getLocalAddress().getAddress().getHostAddress() + ":8000";

            String jsonResponse = "{ \"serverIp\": \"" + serverIp + "\" }";
            byte[] responseBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }

    // Handler to the route "/atualizacao"
    private class updateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json");

            // Access Main.arrayStudents
            String jsonResponse = Main.registersArray.isEmpty()
                    ? "[]"
                    : "[" +
                    Main.registersArray.stream()
                            .map(student -> {
                                String delaysJson = student.arrayDelays.stream()
                                        .map(delay -> String.format("\"%s\"", delay))
                                        .collect(Collectors.joining(","));
                                return String.format(
                                        "{\"student\":\"%s\",\"classroom\":\"%s\",\"delays\":[%s]}",
                                        student.user.toString(),
                                        student.classroom,
                                        delaysJson
                                );
                            })
                            .collect(Collectors.joining(",")) +
                    "]";

            // Send response
            byte[] bytesResposta = jsonResponse.getBytes();
            exchange.sendResponseHeaders(200, bytesResposta.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytesResposta);
            os.close();
        }
    }

    // Handler para listar todos os cadastros
    private class CadastroListHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            JSONArray jsonArray = new JSONArray();

            // Loop through the ArrayList instead of the array
            for (Student student : Main.arrayStudents) {
                if (student != null) { // Verify that the student is not null
                    JSONObject json = new JSONObject();
                    json.put("id", student.accessId); // Replace with the actual ID attribute
                    json.put("idAcesso", student.accessId > 0 ? String.valueOf(student.accessId) : "-");
                    json.put("nome", student.user.toString()); // Assuming user has a proper `toString` method
                    json.put("imagem", !student.arrayDelays.isEmpty() ? "Has Delays" : "-");   // Placeholder logic for image

                    jsonArray.put(json);
                }
            }

            // Send response as JSON
            byte[] response = jsonArray.toString().getBytes();
            exchange.sendResponseHeaders(200, response.length);
            OutputStream os = exchange.getResponseBody();
            os.write(response);
            os.close();
        }
    }



    // Handler para cadastrar um novo usuário
    private class CadastroHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");

                // Read request body
                InputStreamReader inputStreamReader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
                BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
                StringBuilder corpoDaRequisicao = new StringBuilder();
                String linha;
                while ((linha = bufferedReader.readLine()) != null) {
                    corpoDaRequisicao.append(linha);
                }

                // Parse JSON from the request
                JSONObject json = new JSONObject(corpoDaRequisicao.toString());
                String nome = json.getString("nome");
                String identifier = json.getString("número de matrícula");
                String password = json.getString("senha");
                String imagem = json.getString("imagem");
                String nomeImagem = imagem.equals("-") ? "-" : "img_" + Main.arrayStudents.size();

                // Save image if provided
                if (!imagem.equals("-")) {
                    salvarImagem(imagem, nomeImagem);
                }

                // Logs
                System.out.println("nome: " + nome + " | " + "número de matrícula: " + identifier);

                // Create User and Student objects
                User newUser = new User(nome, identifier, password);
                newUser.identifier = identifier;

                Student newStudent = new Student(newUser, "DefaultClass", 0000); // Use appropriate class assignment
                newStudent.accessId = Main.arrayStudents.size(); // Set unique access ID
                newStudent.arrayDelays = new ArrayList<>(); // Initialize the delays list

                // Add new student to the ArrayList
                Main.arrayStudents.add(newStudent);

                // Persist changes (if needed, implement Main.salvarDados() for ArrayList)
                Main.salvarDados();

                // Send response
                String responseMessage = "Cadastro recebido com sucesso!";
                exchange.sendResponseHeaders(200, responseMessage.length());
                exchange.getResponseBody().write(responseMessage.getBytes());
                exchange.close();
            } else {
                exchange.sendResponseHeaders(405, -1); // Method not allowed
            }
        }
    }



    // Handler para atualizar um cadastro existente (PUT)
    private class UpdateCadastroHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("PUT".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");

                // Read request body
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8));
                StringBuilder corpoDaRequisicao = new StringBuilder();
                String linha;
                while ((linha = bufferedReader.readLine()) != null) {
                    corpoDaRequisicao.append(linha);
                }
                JSONObject json = new JSONObject(corpoDaRequisicao.toString());

                // Extract ID from the URI path
                String path = exchange.getRequestURI().getPath();
                String[] parts = path.split("/");
                int id = Integer.parseInt(parts[parts.length - 1]);
                System.out.println("id: " + id);

                // Check if the ID is valid
                if (id >= 0 && id < Main.arrayStudents.size()) {
                    // Retrieve the existing Student object
                    Student student = Main.arrayStudents.get(id);

                    // Update fields if present in the JSON body
                    if (json.has("nome")) {
                        student.user.name = json.getString("nome");
                    }
                    if (json.has("número de matrícula")) {
                        student.user.identifier = json.getString("número de matrícula");
                    }

                    // Update or save the image if provided
                    String nomeImagem = json.has("nomeImagem") ? json.getString("nomeImagem") : "-";
                    if (json.has("imagem") && !json.getString("imagem").equals("-")) {
                        salvarImagem(json.getString("imagem"), id + student.user.name);
                        student.user.imagePath = id + student.user.name + ".png";
                    } else if (!student.user.name.equals(json.optString("nome", student.user.name))) {
                        student.user.imagePath = nomeImagem;
                    }

                    // Logs
                    System.out.println("Edição: nome: " + student.user.name +
                            " | número de matrícula: " + student.user.identifier);

                    // Persist changes (if needed, implement Main.salvarDados for ArrayList)
                    Main.salvarDados();

                    // Send success response
                    String response = "{\"status\":\"Cadastro atualizado com sucesso.\"}";
                    byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, responseBytes.length);
                    exchange.getResponseBody().write(responseBytes);
                } else {
                    System.out.println("ID não encontrado");
                    // Send error response if the ID does not exist
                    String response = "{\"status\":\"ID não encontrado.\"}";
                    byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(404, responseBytes.length);
                    exchange.getResponseBody().write(responseBytes);
                }
            } else {
                exchange.sendResponseHeaders(405, -1); // Method not allowed
            }
            exchange.close();
        }
    }

    // Helper method to save the image
    private void salvarImagem(String imagemBase64, String nomeImagem) throws IOException {
        byte[] dados = Base64.getDecoder().decode(imagemBase64);
        // Complete path to save the image
        File arquivoNovaImagem = new File(Main.pastaImagens, nomeImagem + ".png");
        try (FileOutputStream fileOutputStream = new FileOutputStream(arquivoNovaImagem)) {
            fileOutputStream.write(dados);
        }
    }

    // Handler para deletar um cadastro existente (DELETE)
    private class DeleteCadastroHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            System.out.println("Iniciando processamento no DeleteCadastroHandler");

            if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                System.out.println("Método DELETE confirmado e CORS habilitado.");

                String response;
                int statusCode;

                try {
                    // Extrai o ID da URI
                    String idPath = exchange.getRequestURI().getPath().replaceFirst("/cadastro/deletar/", "").trim();
                    System.out.println("ID extraído da URI: " + idPath);

                    int id = Integer.parseInt(idPath);
                    System.out.println("ID convertido para inteiro: " + id);

                    // Verifica se o ID é válido e existe na lista
                    if (id >= 0 && id < Main.arrayStudents.size() && Main.arrayStudents.get(id) != null) {
                        // Deleta o usuário da lista
                        Main.idUsuarioRecebidoPorHTTP = id;
                        Main.deletarUsuario(2);

                        response = "{\"status\":\"Cadastro deletado com sucesso.\"}";
                        statusCode = 200;
                        System.out.println("Usuário deletado com sucesso.");
                    } else {
                        response = "{\"status\":\"ID não encontrado.\"}";
                        statusCode = 404;
                        System.out.println("ID não encontrado ou inválido.");
                    }
                } catch (NumberFormatException e) {
                    response = "{\"status\":\"ID inválido.\"}";
                    statusCode = 400;
                    System.err.println("Erro ao converter o ID para número: " + e.getMessage());
                }

                // Envia a resposta
                byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(statusCode, responseBytes.length);
                exchange.getResponseBody().write(responseBytes);
                exchange.close(); // Fecha o canal de resposta
                System.out.println("Processamento da requisição encerrado.");
            } else {
                System.out.println("Método não permitido: " + exchange.getRequestMethod());
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
            }
        }
    }

    // Classe para lidar com requisições de imagens
    private class ImagemHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Log para indicar o início do processamento da requisição
            System.out.println("Iniciando processamento no ImagemHandler");

            // Recupera o caminho completo da imagem solicitado pela URI
            String imagePath = exchange.getRequestURI().getPath().replace("/imagens/", "") + ".png";
            System.out.println("Caminho da imagem requisitada: " + imagePath);

            // Verifica se a imagem está na lista de imagens
            if (Main.arrayListImagens.contains(imagePath)) {
                String fullImagePath = Main.pastaImagens.getAbsolutePath() + "\\" + imagePath;
                File imageFile = new File(fullImagePath);

                // Verifica se o arquivo da imagem existe e não é um diretório
                if (imageFile.exists() && !imageFile.isDirectory()) {
                    // Define o tipo de conteúdo com base no tipo da imagem e ajusta o cabeçalho
                    String contentType = Files.probeContentType(Paths.get(fullImagePath));
                    exchange.getResponseHeaders().set("Content-Type", contentType);
                    System.out.println("Tipo de conteúdo da imagem: " + contentType);

                    // Configura o cabeçalho da resposta com o código 200 e o tamanho da imagem
                    exchange.sendResponseHeaders(200, imageFile.length());
                    System.out.println("Imagem encontrada. Enviando resposta com código 200 e tamanho: " + imageFile.length());

                    // Envia a imagem para o cliente
                    try (OutputStream os = exchange.getResponseBody();
                         FileInputStream fs = new FileInputStream(imageFile)) {
                        fs.transferTo(os);
                        System.out.println("Imagem enviada ao cliente com sucesso.");
                    } catch (IOException e) {
                        System.err.println("Erro ao enviar a imagem ao cliente: " + e.getMessage());
                    }
                } else {
                    // Caso a imagem não seja encontrada, envia o código 404
                    System.out.println("Imagem não encontrada. Enviando resposta 404.");
                    exchange.sendResponseHeaders(404, -1);
                }
            } else {
                // Se o caminho da imagem não estiver na lista, envia 404
                System.out.println("Imagem não encontrada na lista. Enviando resposta 404.");
                exchange.sendResponseHeaders(404, -1);
            }

            // Fecha o canal de resposta
            exchange.close();
            System.out.println("Processamento da requisição encerrado.");
        }
    }


    public class ImagemDeleteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            System.out.println("Recebida uma requisição para o ImagemDeleteHandler");

            String response;
            int statusCode;

            if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                String path = exchange.getRequestURI().getPath();
                System.out.println("Caminho da URI: " + path);

                String nomeImagem = path.replaceFirst("/imagens/deletar/", "") + ".png";
                System.out.println("Nome da imagem extraído: " + nomeImagem);

                File arquivoImagem = new File(Main.pastaImagens.getAbsolutePath(), nomeImagem);
                System.out.println("Caminho completo do arquivo: " + arquivoImagem.getAbsolutePath());

                if (arquivoImagem.exists() && arquivoImagem.isFile()) {
                    if (arquivoImagem.delete()) {
                        response = "{ \"status\": \"sucesso\", \"mensagem\": \"Imagem excluída com sucesso.\" }";
                        statusCode = HttpURLConnection.HTTP_OK;
                        System.out.println("Imagem excluída com sucesso.");
                    } else {
                        response = "{ \"status\": \"erro\", \"mensagem\": \"Falha ao excluir a imagem.\" }";
                        statusCode = HttpURLConnection.HTTP_INTERNAL_ERROR;
                        System.out.println("Erro: Falha ao excluir a imagem.");
                    }
                } else {
                    response = "{ \"status\": \"erro\", \"mensagem\": \"Imagem não encontrada.\" }";
                    statusCode = HttpURLConnection.HTTP_NOT_FOUND;
                    System.out.println("Erro: Imagem não encontrada.");
                }
            } else {
                response = "{ \"status\": \"erro\", \"mensagem\": \"Método não permitido.\" }";
                statusCode = HttpURLConnection.HTTP_BAD_METHOD;
                System.out.println("Erro: Método não permitido. Apenas DELETE é suportado.");
            }

            // Envia resposta
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(responseBytes);
            os.flush(); // Garante que todos os bytes sejam enviados antes de fechar
            os.close();
            exchange.close(); // Fecha o canal de resposta
            System.out.println("Resposta enviada ao cliente: " + response);
        }
    }


    private class IniciarRegistroTagHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                // Receber o ID do usuário e o dispositivo que solicitou o registro
                String requestBody = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                        .lines().collect(Collectors.joining());
                JSONObject json = new JSONObject(requestBody);
                int usuarioId = json.getInt("usuarioId");
                String dispositivo = json.getString("dispositivo"); // Novo campo do dispositivo

                // Iniciar o processo de registro da tag
                Main.idUsuarioRecebidoPorHTTP = usuarioId;
                Main.modoCadastrarIdAcesso = true;

                // Publicar no broker qual dispositivo foi habilitado
                Main.conexaoMQTT.publicarMensagem("cadastro/disp", dispositivo);

                // Criação da resposta JSON
                String response = new JSONObject()
                        .put("mensagem", "Registro de tag iniciado para o usuário " + usuarioId + " no " + dispositivo)
                        .toString();

                // Envio da resposta com cabeçalho de conteúdo JSON
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(200, response.getBytes().length);

                // Escrita e fechamento do corpo da resposta
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
                exchange.close();
            }
        }
    }

    private class VerificarStatusTagHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Recupera o ID do usuário a partir da URI
            int usuarioId = Integer.parseInt(exchange.getRequestURI().getPath().split("/")[2]);

            // Verifica se o usuário existe na lista
            if (usuarioId >= 0 && usuarioId < Main.arrayStudents.size()) {
                // Obtém o registro do usuário com base no ID
                Student usuario = Main.arrayStudents.get(usuarioId);

                // Verifica o status, assumindo que a lógica de status seja baseada no campo idAcesso
                String status = usuario.arrayDelays().isEmpty() ? "aguardando" : "sucesso";

                // Prepara a resposta JSON
                String response = "{\"status\":\"" + status + "\"}";

                // Define o cabeçalho de resposta e envia os dados
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length());
                exchange.getResponseBody().write(response.getBytes());
            } else {
                // Caso o ID não exista na lista, envia 404
                String response = "{\"status\":\"ID não encontrado\"}";
                exchange.sendResponseHeaders(404, response.length());
                exchange.getResponseBody().write(response.getBytes());
            }

            // Fecha a resposta
            exchange.close();
        }
    }

}