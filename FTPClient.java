import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;

public class FTPClient {
    static InetAddress serverIP;
    static int cmdPort = 2020, dataPort = 2121;
    static String putFilePath;
    static ArrayList<Integer> DROP = new ArrayList<>();
    static ArrayList<Integer> TIMEOUT = new ArrayList<>();
    static ArrayList<Integer> BITERROR = new ArrayList<>();

    static void processResponse(String cmd, int statusCode, String phrase) throws Exception {
        // error
        if (statusCode >= 400) {
            System.out.println(phrase);
            return;
        }

        // success
        switch (cmd) {
            case "CD":
                System.out.println(phrase.split(" ", 3)[2]);
                return;

            case "LIST":
                String[] list = phrase.split("\n")[1].split(",");

                for (int i = 0; i < list.length; i += 2)
                    System.out.println(list[i] + "," + list[i + 1]);

                return;

            case "GET":
                String[] tmp = phrase.split(" ", 5);
                String fileName = tmp[4];
                long fileSize = Long.parseLong(tmp[1]);

                // open data channel
                SocketChannel dataChannel = SocketChannel.open();
                dataChannel.configureBlocking(true);
                dataChannel.connect(new InetSocketAddress(serverIP, dataPort));

                // create new file
                FileChannel fileChannel = FileChannel.open(Paths.get(fileName),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                System.out.println("Received " + fileName + " / " + fileSize + " bytes");

                FTPServer.receiveData(dataChannel, fileChannel);

                System.out.println("Completed...");
                fileChannel.close();
                dataChannel.close();
                return;

            case "PUT":
                tmp = phrase.split(" ", 7);
                fileName = tmp[6];
                fileSize = Long.parseLong(tmp[3]);

                // open data channel
                dataChannel = SocketChannel.open();
                dataChannel.configureBlocking(true);
                dataChannel.connect(new InetSocketAddress(serverIP, dataPort));

                // open file channel
                fileChannel = FileChannel.open(Paths.get(putFilePath), StandardOpenOption.READ);
                System.out.println(fileName + " transferred / " + fileSize + " bytes");

                FTPServer.sendData(dataChannel, fileChannel, DROP, TIMEOUT, BITERROR);

                DROP.clear();
                TIMEOUT.clear();
                BITERROR.clear();

                System.out.println("\nCompleted...");
                fileChannel.close();
                dataChannel.close();
        }
    }

    public static void main(String[] argv) throws Exception {
        if (argv.length == 0)
            serverIP = InetAddress.getByName("127.0.0.1");
        else {
            serverIP = InetAddress.getByName(argv[0]);
            if (argv.length == 3) {
                cmdPort = Integer.parseInt(argv[1]);
                dataPort = Integer.parseInt(argv[2]);
            } else {
                System.out.println("Enter just 'FTPClient' to connect to '127.0.0.1', 2020(control ch.), 2121(data ch.)" +
                        "\nOr enter 'FTPClient [ftp server host] [control port number] [data port number]'");
                return;
            }
        }

        BufferedReader inFromUser = new BufferedReader(new InputStreamReader(System.in));
        SocketChannel commandChannel = SocketChannel.open();
        commandChannel.configureBlocking(true);
        commandChannel.connect(new InetSocketAddress(serverIP, cmdPort));

        if (commandChannel.isConnected())
            System.out.println("Control channel is connected to " + serverIP.getHostAddress() + " " + cmdPort
                    + "\nEnter commands (CD <dir>, LIST [dir], GET [file], PUT [file], QUIT)" +
                    "\nPacket control : (DROP / TIMEOUT / BITERROR) [R#, R# ...]" +
                    "\nServer packet control : SERVER (DROP / TIMEOUT / BITERROR) [R#, R# ...]");
        else {
            System.out.println("Failed connection");
            commandChannel.close();
            return;
        }

        Charset charset = StandardCharsets.UTF_8;
        ByteBuffer responseBuffer, requestBuffer;
        String request, response;

        while (true) {
            File file;
            System.out.print(">> ");
            request = inFromUser.readLine();
            String[] cmds = request.split(" ", 2);

            // request
            switch (cmds[0]) {
                case "PUT":  // PUT
                    if (cmds.length < 2) {
                        System.out.println("Please enter 'PUT [fileName]'");
                        continue;
                    }

                    file = new File(cmds[1]);
                    if (!file.exists()) {
                        System.out.println("Such file does not exist!");
                        continue;
                    }

                    if (file.isDirectory()) {
                        System.out.println("It is a directory! You can only send a file.");
                        continue;
                    }

                    putFilePath = file.getCanonicalPath();
                    requestBuffer = charset.encode(request + "\n" + file.length() + "\n");
                    commandChannel.write(requestBuffer);

                    break;
                case "DROP":
                    if (cmds.length == 1) {
                        System.out.println("Arguments are needed! DROP / TIMEOUT / BITERROR [R#, R#...]");
                        continue;
                    }
                    String[] args = cmds[1].split(",");
                    for (String arg : args) {
                        DROP.add(Integer.parseInt(arg.trim().substring(1)));
                    }
                    continue;
                case "TIMEOUT":
                    if (cmds.length == 1) {
                        System.out.println("Arguments are needed! DROP / TIMEOUT / BITERROR [R#, R#...]");
                        continue;
                    }
                    args = cmds[1].split(",");
                    for (String arg : args) {
                        TIMEOUT.add(Integer.parseInt(arg.trim().substring(1)));
                    }
                    continue;
                case "BITERROR":
                    if (cmds.length == 1) {
                        System.out.println("Arguments are needed! DROP / TIMEOUT / BITERROR [R#, R#...]");
                        continue;
                    }
                    args = cmds[1].split(",");
                    for (String arg : args) {
                        BITERROR.add(Integer.parseInt(arg.trim().substring(1)));
                    }
                    continue;
                case "SERVER":
                    if (cmds.length < 2) {
                        System.out.println("Arguments are needed! SERVER DROP / TIMEOUT / BITERROR [R#, R#...]");
                        continue;
                    }
                    if (cmds[1].split(" ", 2).length == 2) {
                        String[] packets = cmds[1].split(" ", 2)[1].split(",");
                        boolean error = false;
                        for (String p : packets) {
                            p = p.trim();
                            if (p.length() == 0 || p.charAt(0) != 'R') {
                                System.out.println("Arguments are needed! SERVER DROP / TIMEOUT / BITERROR [R#, R#...]");
                                error = true;
                                break;
                            }
                            try {
                                Integer.parseInt(p.substring(1));
                            } catch (NumberFormatException nfe) {
                                System.out.println("Arguments are needed! SERVER DROP / TIMEOUT / BITERROR [R#, R#...]. # is an integer");
                                error = true;
                                break;
                            }
                        }
                        if (error)
                            continue;
                    } else {
                        System.out.println("Arguments are needed! SERVER DROP / TIMEOUT / BITERROR [R#, R#...]");
                        continue;
                    }
                default:  // other commands
                    requestBuffer = charset.encode(request);
                    commandChannel.write(requestBuffer);
                    // quit
                    if (request.equals("QUIT")) {
                        commandChannel.shutdownInput();
                        commandChannel.shutdownOutput();
                        inFromUser.close();
                        commandChannel.close();
                        System.exit(0);
                        return;
                    }
                    break;
            }

            // read response size
            int responseSize;
            ByteBuffer sizeBuff = ByteBuffer.allocate(4);
            commandChannel.read(sizeBuff);
            sizeBuff.flip();
            responseSize = sizeBuff.getInt();

            // read response
            responseBuffer = ByteBuffer.allocate(responseSize);
            commandChannel.read(responseBuffer);
            responseBuffer.flip();

            // bytes to string
            response = charset.decode(responseBuffer).toString();

            String[] parsedResponse = response.split(" ", 2);

            processResponse(cmds[0], Integer.parseInt(parsedResponse[0]), parsedResponse[1]);
        }
    }
}