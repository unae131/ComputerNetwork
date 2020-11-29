import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class FTPServer {
    static final String basePath = Paths.get("").toAbsolutePath().toString();
    static final Charset charset = StandardCharsets.UTF_8;

    static int cmdPort = 2020, dataPort = 2121;
    static SR senderSR, receiverSR;
    static String curPath;
    static File file;
    static long getFileSize;

    static String statusCode(int code) {
        String phrase;
        switch (code) {
            // success
            case 200: // cd
                phrase = "Moved to " + curPath;
                break;
            case 201: // list
                String response = "";
                for (File f : file.listFiles())
                    response += f.getName() + "," + (f.isDirectory() ? "-" : f.length()) + ",";

                phrase = "Comprising " + file.listFiles().length + " entries\n" + response;
                break;
            case 202: // get
                phrase = "Containing " + file.length() + " bytes in " + file.getName();
                break;
            case 203: // put
                phrase = "Ready to receive " + getFileSize + " bytes in " + file.getName();
                break;
            case 204: // SERVER DROP/TIMEOUT/BITERROR
                phrase = "Control server packets";
                break;
            // error
            case 400:
                phrase = "No such file/directory exists";
                break;
            case 401:
                phrase = "It's not a directory";
                break;
            case 402:
                phrase = "It's not a file";
                break;
            case 500:
                phrase = "Wrong argument (CD <dir>, LIST [dir], GET [file], PUT [file])";
                break;
            case 501:
                phrase = "Wrong command (CD <dir>, LIST [dir], GET [file], PUT [file], SERVER [DROP/TIMEOUT/BITERROR] [R#,R#...], QUIT)";
                break;
            default:
                code = 502;
                phrase = "Unknown reason";
        }
        return code + (code >= 400 ? " Failed -" : "") + " " + phrase;
    }

    static String processCommand(String cmd, String arg) throws IOException { // CD, LIST, GET, PUT

        Path givenPath = Paths.get(arg);;

        // make a file object
        if (cmd.equals("PUT"))
            file = new File(curPath, givenPath.getFileName().toString());
        else if (givenPath.isAbsolute())
            file = new File(givenPath.toString());
        else
            file = new File(curPath, givenPath.toString());

        // check file existence
        if (!cmd.equals("PUT") && !file.exists())
            return statusCode(400);

        switch (cmd) {
            case "CD":
                if (!file.isDirectory())
                    return statusCode(401);

                curPath = file.getCanonicalPath();
                return statusCode(200);
            case "LIST":
                if (!file.isDirectory())
                    return statusCode(401);

                return statusCode(201);
            case "GET":
                if (file.isDirectory())
                    return statusCode(402);

                return statusCode(202);
            default: // "PUT":
                return statusCode(203);
        }
    }

    static void sendResopnse(SocketChannel commandChannel, String response) throws IOException {
        System.out.println("Response: " + response);
        // send response size with response to Client
        ByteBuffer tmpBuff = charset.encode(response);
        int size = tmpBuff.capacity();

        ByteBuffer outBuffer = ByteBuffer.allocate(size + 4);
        outBuffer.putInt(size);
        outBuffer.put(tmpBuff);

        outBuffer.flip();
        commandChannel.write(outBuffer);
        outBuffer.clear();
    }

    static void runDataChannel(String cmd) throws Exception {
        ServerSocketChannel dataSSC = ServerSocketChannel.open();
        dataSSC.configureBlocking(true);
        dataSSC.bind(new InetSocketAddress(dataPort));

        SocketChannel dataChannel = dataSSC.accept();
        FileChannel fileChannel;

        switch (cmd) {
            case "GET":
                fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ);

                senderSR.sendData(dataChannel, fileChannel);

                System.out.println("\nCompleted...");
                senderSR.clearAllList();

                fileChannel.close();
                break;

            case "PUT":
                fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

                receiverSR.receiveData(dataChannel, fileChannel);

                fileChannel.close();
        }

        dataChannel.close();
        dataSSC.close();
    }

    public static void main(String[] argv) throws Exception {
        // run arg
        if (argv.length == 2) {
            cmdPort = Integer.parseInt(argv[0]);
            dataPort = Integer.parseInt(argv[1]);
        } else if (argv.length > 0) {
            System.out.println("Enter just 'FTPServer' to open control channel, data channel in port 2020, 2121.\n" +
                    "Or enter 'FTPServer [control port no] [data port no]'");
            return;
        }

        // open server socket channel
        ServerSocketChannel serverSC = ServerSocketChannel.open();
        serverSC.configureBlocking(true);
        serverSC.bind(new InetSocketAddress(cmdPort));

        while (true) {
            // wait for a client
            System.out.println("\n...Waiting for a client in port " + cmdPort);
            SocketChannel commandChannel = serverSC.accept();
            if (commandChannel == null) {
                System.out.println("Failed connection");
                break;
            }

            // success to connect
            System.out.println("Connected to " + commandChannel.getRemoteAddress());

            // init server directory
            curPath = basePath;
            receiverSR = new SR();
            senderSR = new SR();

            ByteBuffer requestBuffer = ByteBuffer.allocate(500);
            String request;
            while (true) {
                // read requests from Client
                if (commandChannel.read(requestBuffer) == -1) {
                    commandChannel.close();
                    break;
                }
                requestBuffer.flip();
                request = charset.decode(requestBuffer).toString();
                requestBuffer.clear();

                // print requests in server's screen
                String[] lines = request.split("\n");
                for (String i : lines)
                    System.out.println("Request: " + i);

                // process request
                String[] line0 = lines[0].split(" ", 2);
                String cmd = line0[0].toUpperCase();
                String arg = (line0.length == 1) ? "." : line0[1]; // length == 1 -> "CD"

                switch (cmd) {
                    case "PUT":
                        getFileSize = Long.parseLong(lines[1]);
                    case "GET":
                        sendResopnse(commandChannel, processCommand(cmd, arg));
                        runDataChannel(cmd);
                        break;
                    case "CD":
                    case "LIST":
                        sendResopnse(commandChannel, processCommand(cmd, arg));
                        break;
                    case "SV_DROP":
                    case "SV_TIMEOUT":
                    case "SV_BITERROR":
                        senderSR.addToArray(cmd, SR.availableArg(arg)); // always available
                        sendResopnse(commandChannel, statusCode(204) + " -> " + cmd);
                        break;
                    default: // wrong command -> never caused
                        sendResopnse(commandChannel, statusCode(501));
                }

            }

        }
    }
}