package ca.concordia.server;

import ca.concordia.filesystem.FileSystemManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FileServer - Multi-threaded file sharing server.
 * 
 * Features:
 * - Handles multiple concurrent clients using thread pool
 * - Supports CREATE, WRITE, READ, DELETE, LIST, QUIT commands
 * - Robust error handling (errors don't crash server)
 * - Thread-safe file operations via FileSystemManager
 * 
 * Protocol:
 * - Commands: COMMAND [arg1] [arg2]
 * - Responses: SUCCESS/ERROR messages or data
 * 
 * @author Mahmoud Elashri
 */
public class FileServer {

    private FileSystemManager fsManager;
    private int port;
    private ExecutorService threadPool;
    private ServerSocket serverSocket;

    /**
     * Creates a new file server.
     * 
     * @param port           Port number to listen on
     * @param fileSystemName Path to backing file for file system
     * @param totalSize      Total size of file system in bytes
     */
    public FileServer(int port, String fileSystemName, int totalSize) {
        try {
            // Initialize the FileSystemManager
            this.fsManager = new FileSystemManager(fileSystemName, totalSize);
            this.port = port;
            this.threadPool = Executors.newCachedThreadPool();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize FileSystemManager", e);
        }
    }

    /**
     * Starts the server and begins accepting client connections.
     * 
     * Each client connection is handled in a separate thread from the pool.
     * The server runs indefinitely until stop() is called.
     */
    public void start() {
        try {
            serverSocket = new ServerSocket(port);
            System.out.println("Server started. Listening on port " + port + "...");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Accepted client: " + clientSocket);

                // Handle each client in a separate thread
                threadPool.submit(new ClientHandler(clientSocket, fsManager));
            }
        } catch (Exception e) {
            if (!serverSocket.isClosed()) {
                e.printStackTrace();
                System.err.println("Could not start server on port " + port);
            }
        }
    }

    /**
     * Stops the server and releases all resources.
     * 
     * Closes the server socket, shuts down the thread pool, and closes the file
     * system.
     */
    public void stop() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            if (threadPool != null) {
                threadPool.shutdownNow();
            }
            if (fsManager != null) {
                fsManager.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Inner class to handle individual client connections
     */
    private static class ClientHandler implements Runnable {
        private final Socket clientSocket;
        private final FileSystemManager fsManager;

        public ClientHandler(Socket clientSocket, FileSystemManager fsManager) {
            this.clientSocket = clientSocket;
            this.fsManager = fsManager;
        }

        @Override
        public void run() {
            try (
                    BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("Received from client: " + line);

                    try {
                        String response = processCommand(line);
                        writer.println(response);
                        writer.flush();

                        // If QUIT command, close connection
                        if (line.trim().toUpperCase().equals("QUIT")) {
                            break;
                        }
                    } catch (Exception e) {
                        writer.println("ERROR: " + e.getMessage());
                        writer.flush();
                    }
                }
            } catch (Exception e) {
                System.err.println("Error handling client: " + e.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }

        /**
         * Process a command from the client and return the response
         */
        private String processCommand(String line) throws Exception {
            if (line == null || line.trim().isEmpty()) {
                return "ERROR: Empty command";
            }

            String[] parts = line.trim().split("\\s+", 3);
            String command = parts[0].toUpperCase();

            switch (command) {
                case "CREATE":
                    if (parts.length < 2) {
                        return "ERROR: CREATE requires a filename";
                    }
                    return handleCreate(parts[1]);

                case "WRITE":
                    if (parts.length < 3) {
                        return "ERROR: WRITE requires filename and content";
                    }
                    return handleWrite(parts[1], parts[2]);

                case "READ":
                    if (parts.length < 2) {
                        return "ERROR: READ requires a filename";
                    }
                    return handleRead(parts[1]);

                case "DELETE":
                    if (parts.length < 2) {
                        return "ERROR: DELETE requires a filename";
                    }
                    return handleDelete(parts[1]);

                case "LIST":
                    return handleList();

                case "QUIT":
                    return "SUCCESS: Disconnecting.";

                default:
                    return "ERROR: Unknown command";
            }
        }

        private String handleCreate(String filename) {
            try {
                fsManager.createFile(filename);
                return "SUCCESS: File '" + filename + "' created.";
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && msg.startsWith("ERROR:")) {
                    return msg;
                }
                return "ERROR: " + msg;
            }
        }

        private String handleWrite(String filename, String content) {
            try {
                fsManager.writeFile(filename, content.getBytes());
                return "SUCCESS: File '" + filename + "' written.";
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && msg.startsWith("ERROR:")) {
                    return msg;
                }
                return "ERROR: " + msg;
            }
        }

        private String handleRead(String filename) {
            try {
                byte[] content = fsManager.readFile(filename);
                return new String(content);
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && msg.startsWith("ERROR:")) {
                    return msg;
                }
                return "ERROR: " + msg;
            }
        }

        private String handleDelete(String filename) {
            try {
                fsManager.deleteFile(filename);
                return "SUCCESS: File '" + filename + "' deleted.";
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && msg.startsWith("ERROR:")) {
                    return msg;
                }
                return "ERROR: " + msg;
            }
        }

        private String handleList() {
            try {
                String[] files = fsManager.listFiles();
                if (files.length == 0) {
                    return "No files found.";
                }
                return String.join("\n", files);
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }
    }
}
