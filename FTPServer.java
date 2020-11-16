import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class FTPServer {
    final static String basePath = Paths.get("").toAbsolutePath().toString();
    static String curPath = basePath;
    static int cmdPort = 2020, dataPort = 2121;

    static File file;
    static long fileSize;

    static String statusCode(int code) {
        String phrase;
        switch (code) {
            case 200: // 성공적인 cd
                phrase = "Moved to " + curPath;
                break;
            case 201: // 성공적인 list
                phrase = "Comprising " + file.listFiles().length + " entries";
                break;
            case 202: // 성공적인 get. 보낼 준비 되었다.
                phrase = "Containing " + file.length() + " bytes in " + file.getName();
                break;
            case 203: // put할 준비 되었다.
                phrase = "Ready to receive " + fileSize + " bytes in " + file.getName();
                break;
            case 400: // 그런 file,dir 없다
                phrase = "No such file/directory exists";
                break;
            case 401: // cd,list에서 이건 dir이 아니다
                phrase = "It's not a directory";
                break;
            case 402: // get, put에서 이건 file이 아니다
                phrase = "It's not a file";
                break;
            case 403: // put에서 인자에 주어진 파일의 상위 디렉토리가 존재하지 않는다.
                phrase = "Parent directory doesn't exist";
                break;
            case 500: // arg가 부족하다
                phrase = "File/Directory name is needed";
                break;
            case 501: // wrong cmd;
                phrase = "Wrong command";
                break;
            default: // unknown reason
                code = 502;
                phrase = "Unknown reason";
        }
        return code + (code >= 400 ? " Failed -" : "") + " " + phrase;
    }

    static String processCommand(String cmd, String arg) throws IOException {
        Path argPath;

        // "CD"를 제외하곤, arg가 없는 것을 허용하지 않는다.
        if (arg == null && cmd.equals("CD")) {
            return statusCode(200);
        } else if (arg == null) {
            return statusCode(500);
        } else if (cmd.equals("PUT")) {
            argPath = Paths.get(arg.split("\n")[0]);
        } else {
            argPath = Paths.get(arg);
        }

        // check that it's an absolute path
        if (argPath.isAbsolute()) { // absolute
            file = new File(argPath.toString());
        } else { // non-absolute
            file = new File(curPath, argPath.toString());
        }

        // "PUT"을 제외하곤, 존재하는 file, dir이어야 한다.
        if (!cmd.equals("PUT") && !file.exists()) {
            return statusCode(400);
        }

        switch (cmd) {
            case "CD":
                if (!file.isDirectory()) {
                    return statusCode(401);
                }
                curPath = file.getCanonicalPath();
                return statusCode(200);

            case "LIST":
                if (!file.isDirectory()) {
                    return statusCode(401);
                }

                File[] files = file.listFiles();

                String response = "\n";
                for (File f : files) {
                    response += f.getName() + "," + (f.isDirectory() ? "-" : f.length()) + ",";
                }

                return statusCode(201) + response;

            case "GET":
                if (file.isDirectory()) {
                    return statusCode(402);
                }
                return statusCode(202);

            case "PUT":
                if (file.isDirectory()) {
                    return statusCode(402);
                }
                // /dir/file 이런식으로 주어졌을 경우
                if (!file.getParentFile().exists()) {
                    return statusCode(403);
                }
                fileSize = Long.parseLong(arg.split("\n")[1]);
                return statusCode(203);

            default:
                return statusCode(501);
        }
    }

    static void processData(String cmd) throws IOException {
        ServerSocketChannel dataSSC = ServerSocketChannel.open();
        dataSSC.configureBlocking(true);
        dataSSC.bind(new InetSocketAddress(dataPort));

        SocketChannel dataChannel = dataSSC.accept();
        FileChannel fileChannel;

        // {SeqNo(1byte), CHKsum(2bytes), Size(2byte), 데이터청크(1000bytes)}
        // {SeqNo(1byte), CHKsum(2bytes)}
        ByteBuffer dataMessage, controlMessage, chunk;
        dataMessage = ByteBuffer.allocate(1005);
        controlMessage = ByteBuffer.allocate(3);

        byte seqNo;
        short chkSum, msgSize;

        switch (cmd) {
            case "GET":
                fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
                seqNo = 1;
                chkSum = 0;

                chunk = ByteBuffer.allocate(1000);

                while ((msgSize = (short) fileChannel.read(chunk)) != -1) {
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
//                    System.out.println("data message is sent");

                    // read control msg
                    dataChannel.read(controlMessage);
                    controlMessage.flip();
//                    System.out.println("control message is received");

                    /** do something **/
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
                    /** do something **/

                    msgSize = dataMessage.getShort();
                    dataMessage.limit(dataMessage.position() + msgSize);
//                    System.out.println(seqNo + " " + chkSum + " " + msgSize + " data message is received\n" + dataMessage);

                    // write to file
                    fileChannel.write(dataMessage);
                    dataMessage.clear();

                    // send control message
                    seqNo += 1;
                    controlMessage.put(seqNo);
                    controlMessage.putShort(chkSum);
                    controlMessage.flip();
                    dataChannel.write(controlMessage);
                    controlMessage.clear();
//                    System.out.println("control message is sent");
                }

                fileChannel.close();
        }

        dataChannel.close();
        dataSSC.close();

    }

    public static void main(String argv[]) throws Exception {
        if (argv.length == 2) {
            cmdPort = Integer.parseInt(argv[1]);
            dataPort = Integer.parseInt(argv[2]);
        } else if (argv.length > 0) {
            System.out.println("Please enter 'FTPClient or FTPServer [control port number] [data port number]'\n(Default - control : 2020, data : 2121)");
            return;
        }

        String inputString, outputString;
        String[] input;

        ServerSocketChannel serverSC = ServerSocketChannel.open();
        SocketChannel commandChannel;
        serverSC.configureBlocking(true);
        serverSC.bind(new InetSocketAddress(cmdPort));

        Charset charset = Charset.forName("UTF-8");
        /***/ByteBuffer byteBuffer = ByteBuffer.allocate(1000);

        while (true) {
            commandChannel = serverSC.accept();
            curPath = basePath;

            if (commandChannel.isConnected())
                System.out.println("Connected to " + commandChannel.getRemoteAddress() + " " + cmdPort + " " + dataPort);
            else {
                System.out.println("Failed connection");
                commandChannel.close();
                break;
            }

            while (true) {
                // read from Client
                commandChannel.read(byteBuffer);
                byteBuffer.flip();

                inputString = charset.decode(byteBuffer).toString();
                for (String i : inputString.split("\n"))
                    System.out.println("Request: " + i);

                // QUIT
                if (inputString.equals("QUIT")) {
                    System.out.println();
                    break;
                }

                input = inputString.split(" ", 2);
                outputString = processCommand(input[0], input.length > 1 ? input[1] : null);

                // write to Client
                byteBuffer = charset.encode(outputString);
                commandChannel.write(byteBuffer);
                byteBuffer.clear();
                for (String o : outputString.split("\n"))
                    System.out.println("Response: " + o);

                // process data (get, put)
                if ((input[0].equals("GET") || input[0].equals("PUT"))
                        && Integer.parseInt(outputString.split(" ")[0]) < 400) {
                    processData(input[0]);
                }
            }
            byteBuffer.clear();
            commandChannel.close();
        }
    }
}
