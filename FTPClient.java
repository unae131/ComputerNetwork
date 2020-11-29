import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;

public class FTPClient {
    static final String CMD = "Command : CD <dir>, LIST [dir], GET [file], PUT [file], QUIT";
    static final String CLIENT_PACKET_CMD = "Client Packet Control : DROP/TIMEOUT/BITERROR [R#,R# ...]";
    static final String SERVER_PACKET_CMD = "Server Packet Control : SV_DROP/SV_TIMEOUT/SV_BITERROR [R#,R# ...]";
    static final String ALLCMD = CMD + "\n" + CLIENT_PACKET_CMD + "\n" + SERVER_PACKET_CMD;
    static final Charset charset = StandardCharsets.UTF_8;

    static InetAddress serverIP;
    static int cmdPort = 2020, dataPort = 2121;
    static SR senderSR, receiverSR;
    static File putFile;

    static String readResponse(SocketChannel commandChannel) throws IOException {
        int responseSize;

        // read response size
        ByteBuffer sizeBuff = ByteBuffer.allocate(4);
        commandChannel.read(sizeBuff);
        sizeBuff.flip();
        responseSize = sizeBuff.getInt();

        // read response
        ByteBuffer responseBuffer = ByteBuffer.allocate(responseSize);
        commandChannel.read(responseBuffer);
        responseBuffer.flip();

        // bytes to string
        return charset.decode(responseBuffer).toString();
    }

    static void processResponse(String cmd, String response) throws Exception {
        String[] lines = response.split("\n");
        String[] parsedLine0 = lines[0].split(" ", 2);

        int statusCode = Integer.parseInt(parsedLine0[0]);
        String phrase = parsedLine0[1];

        // error
        if (statusCode >= 400) {
            System.out.println(phrase.split(" ", 3)[2]);
            return;
        }

        // success
        switch (cmd) {
            case "SV_DROP":
            case "SV_TIMEOUT":
            case "SV_BITERROR":
            case "CD":
                System.out.println(phrase);
                return;
            case "LIST":
                String[] parsedPhrase = lines[1].split(",");
                for (int i = 0; i < parsedPhrase.length; i += 2)
                    System.out.println(parsedPhrase[i] + "," + parsedPhrase[i + 1]);
                return;
            case "GET":
                parsedPhrase = phrase.split(" ", 5);
                String fileName = parsedPhrase[4];
                long fileSize = Long.parseLong(parsedPhrase[1]);

                processGET(fileName, fileSize);
                return;
            case "PUT":
                parsedPhrase = phrase.split(" ", 7);
                fileName = parsedPhrase[6];
                fileSize = Long.parseLong(parsedPhrase[3]);

                processPUT(fileName, fileSize);
        }
    }

    static void processGET(String fileName, long fileSize) throws Exception {
        // open & connect data channel
        SocketChannel dataChannel = SocketChannel.open();
        dataChannel.configureBlocking(true);
        dataChannel.connect(new InetSocketAddress(serverIP, dataPort));

        // create new file
        FileChannel fileChannel = FileChannel.open(Paths.get(fileName),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("Received " + fileName + " / " + fileSize + " bytes");

        receiverSR.receiveData(dataChannel, fileChannel);

        System.out.println("Completed...");
        fileChannel.close();
        dataChannel.close();
    }

    static void processPUT(String fileName, long fileSize) throws IOException {
        // open data channel
        SocketChannel dataChannel = SocketChannel.open();
        dataChannel.configureBlocking(true);
        dataChannel.connect(new InetSocketAddress(serverIP, dataPort));

        // open file channel
        FileChannel fileChannel = FileChannel.open(Paths.get(putFile.getCanonicalPath()),
                StandardOpenOption.READ);
        System.out.println(fileName + " transferred / " + fileSize + " bytes");

        senderSR.sendData(dataChannel, fileChannel);
        senderSR.clearAllList();

        System.out.println("\nCompleted...");
        fileChannel.close();
        dataChannel.close();
    }

    public static void main(String[] argv) throws Exception {
        // Run arg
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

        // open a command channel
        SocketChannel commandChannel = SocketChannel.open();
        commandChannel.configureBlocking(true);

        // connect to server
        if (commandChannel.connect(new InetSocketAddress(serverIP, cmdPort)))
            System.out.println("Control channel is connected to " + serverIP.getHostAddress() + " " + cmdPort
                    + "\n" + ALLCMD);
        else {
            System.out.println("Failed connection");
            inFromUser.close();
            commandChannel.close();
            return;
        }

        // read user input & communicate with server
        String userInput;
        while (true) {
            receiverSR = new SR();
            senderSR = new SR();

            System.out.print(">> ");
            userInput = inFromUser.readLine();

            if ((userInput.toUpperCase()).equals("QUIT")) {
                commandChannel.shutdownOutput();
                commandChannel.close();
                inFromUser.close();
                return;
            }

            String[] parsedInput = userInput.split(" ", 2);
            String cmd = parsedInput[0].toUpperCase();
            String arg;

            if (parsedInput.length == 1 && !cmd.equals("CD")) {
                System.out.println("Wrong Command!");
                System.out.println(ALLCMD);
                continue;
            }
            else if(parsedInput.length == 1){
                arg = null;
            }
            else
                arg = parsedInput[1];

            switch (cmd) {
                case "PUT":
                    putFile = new File(arg);
                    if (!putFile.exists()) {
                        System.out.println("Such file does not exist!");
                        break;
                    }
                    if (putFile.isDirectory()) {
                        System.out.println("It is a directory! You can only send a file.");
                        break;
                    }
                    userInput += "\n" + putFile.length();
                case "CD":
                case "LIST":
                case "GET":
                    // send a request
                    ByteBuffer requestBuffer = charset.encode(userInput);
                    commandChannel.write(requestBuffer);

                    // read & process response
                    processResponse(cmd, readResponse(commandChannel));
                    break;

                case "SV_DROP":
                case "SV_TIMEOUT":
                case "SV_BITERROR":
                    if (SR.availableArg(arg) == null) {
                        System.out.println(CLIENT_PACKET_CMD);
                        break;
                    }

                    // send a request
                    requestBuffer = charset.encode(userInput);
                    commandChannel.write(requestBuffer);

                    // read & process response
                    processResponse(userInput, readResponse(commandChannel));
                    break;

                case "DROP":
                case "TIMEOUT":
                case "BITERROR":
                    ArrayList<Integer> arr;
                    if ((arr = SR.availableArg(arg)) == null) {
                        System.out.println(CLIENT_PACKET_CMD);
                        break;
                    }
                    senderSR.addToArray(cmd, arr);
                    break;

                default:
                    System.out.println(ALLCMD);

            }

        }

    }
}
