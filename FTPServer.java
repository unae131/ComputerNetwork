import java.io.*;
import java.net.*;
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
    final static String basePath = Paths.get("").toAbsolutePath().toString();
    static String curPath;
    static int cmdPort = 2020, dataPort = 2121;

    static File file;
    static long fileSize;

    static String statusCode(int code) {
        String phrase;
        switch (code) {
            // success
            case 200: // cd
                phrase = "Moved to " + curPath;
                break;
            case 201: // list
                phrase = "Comprising " + file.listFiles().length + " entries";
                break;
            case 202: // get
                phrase = "Containing " + file.length() + " bytes in " + file.getName();
                break;
            case 203: // put
                phrase = "Ready to receive " + fileSize + " bytes in " + file.getName();
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
                phrase = "Wrong argument (CD <dir>, LIST [dir], GET [file], PUT [file], QUIT)";
                break;
            case 501:
                phrase = "Wrong command (CD <dir>, LIST [dir], GET [file], PUT [file], QUIT)";
                break;
            default:
                code = 502;
                phrase = "Unknown reason";
        }
        return code + (code >= 400 ? " Failed -" : "") + " " + phrase;
    }

    static String processCommand(String cmd, String[] arg) throws IOException {
        Path argPath;
        // wrong command
        if (!cmd.equals("CD") && !cmd.equals("LIST") && !cmd.equals("PUT") && !cmd.equals("GET"))
            return statusCode(501);

        // arg is always needed, except "CD"
        if (arg == null && cmd.equals("CD"))
            return statusCode(200);
        else if (arg == null)
            return statusCode(500);
        else
            argPath = Paths.get(arg[0]);

        // make a File
        if (cmd.equals("PUT"))
            file = new File(curPath, argPath.getFileName().toString());
        else if (argPath.isAbsolute())
            file = new File(argPath.toString());
        else
            file = new File(curPath, argPath.toString());

        // File should exist, except "PUT"
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

                String response = "\n";
                for (File f : file.listFiles())
                    response += f.getName() + "," + (f.isDirectory() ? "-" : f.length()) + ",";

                return statusCode(201) + response;

            case "GET":
                if (file.isDirectory())
                    return statusCode(402);

                return statusCode(202);

            case "PUT":
                fileSize = Long.parseLong(arg[1]);
                return statusCode(203);

            default:
                return statusCode(501);
        }
    }

    /* Message formats:
     * {SeqNo(1byte), CHKsum(2bytes), Size(2byte), data chunk(1000bytes)}
     * {SeqNo(1byte), CHKsum(2bytes)}
     */
    static void processData(String cmd) throws IOException {
        ServerSocketChannel dataSSC = ServerSocketChannel.open();
        dataSSC.configureBlocking(true);
        dataSSC.bind(new InetSocketAddress(dataPort));

        SocketChannel dataChannel = dataSSC.accept();
        FileChannel fileChannel;

        ByteBuffer dataMessage = ByteBuffer.allocate(1005);
        ByteBuffer controlMessage = ByteBuffer.allocate(3);

        byte seqNo;
        short chkSum, msgSize;

        switch (cmd) {
            case "GET":
                fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ);

                seqNo = 1;
                chkSum = 0;
                ByteBuffer chunk = ByteBuffer.allocate(1000);

                while ((msgSize = (short) fileChannel.read(chunk)) != -1) { // read file
                    // write data message to buffer
                    dataMessage.put(seqNo);
                    dataMessage.putShort(chkSum);
                    dataMessage.putShort(msgSize);

                    chunk.flip();
                    dataMessage.put(chunk);
                    chunk.clear();

                    // send message
                    dataMessage.flip();
                    dataChannel.write(dataMessage);
                    dataMessage.clear();

                    // read control msg
                    dataChannel.read(controlMessage);
                    /* do something */
//                    controlMessage.flip();
//                    seqNo = controlMessage.get();
//                    chkSum = controlMessage.getShort();
                    controlMessage.clear();
                }
                fileChannel.close();
                break;

            case "PUT":
                fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);

                while (dataChannel.read(dataMessage) != -1) {
                    // read message
                    dataMessage.flip();
                    seqNo = dataMessage.get();
                    chkSum = dataMessage.getShort();
                    /* do something */

                    msgSize = dataMessage.getShort();
                    dataMessage.limit(dataMessage.position() + msgSize);

                    // write to file
                    fileChannel.write(dataMessage);
                    dataMessage.clear();

                    // send control message
                    /* do something */
//                    seqNo += 1;
                    controlMessage.put(seqNo);
                    controlMessage.putShort(chkSum);
                    controlMessage.flip();
                    dataChannel.write(controlMessage);
                    controlMessage.clear();
                }
                fileChannel.close();
        }

        dataChannel.close();
        dataSSC.close();

    }

    public static void main(String[] argv) throws Exception {

        if (argv.length == 2) {
            cmdPort = Integer.parseInt(argv[0]);
            dataPort = Integer.parseInt(argv[1]);
        } else if (argv.length > 0) {
            System.out.println("Enter just 'FTPServer' to open control channel, data channel in port 2020, 2121.\n" +
                    "Or enter 'FTPServer [control port no] [data port no]'");
            return;
        }

        ServerSocketChannel serverSC = ServerSocketChannel.open();
        serverSC.configureBlocking(true);
        serverSC.bind(new InetSocketAddress(cmdPort));

        Charset charset = StandardCharsets.UTF_8;
        ByteBuffer inBuffer, outBuffer;

        String inputString, outputString;

        while (true) {
            System.out.println("\n...Waiting for a client in port " + cmdPort);

            SocketChannel commandChannel = serverSC.accept();
            if (commandChannel == null) {
                System.out.println("Failed connection");
                break;
            }
            System.out.println("Connected to " + commandChannel.getRemoteAddress());

            // init server directory
            curPath = basePath;

            inBuffer = ByteBuffer.allocate(500);
            while (true) {
                // read requests in bytes from Client
                try {
                    commandChannel.read(inBuffer);

                    inBuffer.flip();

                    // bytes to string
                    inputString = charset.decode(inBuffer).toString();
                    inBuffer.clear();

                    // print requests in server's screen
                    for (String i : inputString.split("\n"))
                        System.out.println("Request: " + i);

                    // QUIT
                    if (inputString.equals("QUIT")) {
                        System.out.println();
                        break;
                    }

                    // process request
                    String[] tmp = inputString.split(" ", 2);
                    String cmd = tmp[0];
                    String[] arg = tmp.length > 1 ? tmp[1].split("\n") : null;

                    outputString = processCommand(cmd, arg);

                    // send response size with response to Client
                    ByteBuffer tmpBuff = charset.encode(outputString);
                    int size = tmpBuff.capacity();

                    outBuffer = ByteBuffer.allocate(size + 4);
                    outBuffer.putInt(size);
                    outBuffer.put(tmpBuff);

                    outBuffer.flip();
                    commandChannel.write(outBuffer);
                    outBuffer.clear();

                    // print response in server's screen
                    for (String o : outputString.split("\n"))
                        System.out.println("Response: " + o);

                    // process data (get, put)
                    if ((cmd.equals("GET") || cmd.equals("PUT"))
                            && Integer.parseInt(outputString.split(" ")[0]) < 400) {
                        try {

                            processData(cmd);

                        } catch (BindException be) { // when can't use 2121
                            System.out.println(dataPort + " port is being occupied. Trying to connect in " + ++dataPort);
                            processData(cmd);
                        }
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                    break;
                }
            }
            commandChannel.close();
        }
        serverSC.close();
    }
}