import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class FTPClient {
    static InetAddress serverIP;
    static int cmdPort = 2020, dataPort = 2121;
    static SocketChannel commandChannel;
    static BufferedReader inFromUser;
    static ByteBuffer byteBuffer;
    final static Charset charset = Charset.forName("UTF-8");

    static void processResponse(String cmd, int statusCode, String phrase) throws IOException {
        // 에러 발생시
        if (statusCode >= 400) {
            System.out.println(phrase);
            return;
        }

        // 성공적으로 수행시
        switch (cmd) {
            case "CD":
                System.out.println(phrase.split(" ", 3)[2]);
                return;
            case "LIST":
                String[] list = phrase.split("\n")[1].split(",");

                for (int i = 0; i < list.length; i += 2) {
                    System.out.println(list[i] + "," + list[i + 1]);
                }

                return;
            case "GET":
                String[] tmp = phrase.split(" ", 5);
                String fileName = tmp[4];
                long fileSize = Long.parseLong(tmp[1]);

                System.out.println("Received " + fileName + " / " + fileSize + " bytes");

                SocketChannel dataChannel = SocketChannel.open();
                dataChannel.configureBlocking(true);
                dataChannel.connect(new InetSocketAddress(serverIP, dataPort));

                FileChannel fileChannel = FileChannel.open(Paths.get(fileName), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                ByteBuffer dataMessage, controlMessage, chunk;
                byte seqNo;
                short chkSum, msgSize;

                dataMessage = ByteBuffer.allocate(1005);
                controlMessage = ByteBuffer.allocate(3);
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
                    System.out.print("#");
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

                System.out.println(" Completed...");
                fileChannel.close();
                dataChannel.close();
                return;

            case "PUT":
                tmp = phrase.split(" ", 7);
                fileName = tmp[6];
                fileSize = Long.parseLong(tmp[3]);
                System.out.println(fileName + " transferred / " + fileSize + " bytes");

                dataChannel = SocketChannel.open();
                dataChannel.configureBlocking(true);
                dataChannel.connect(new InetSocketAddress(serverIP, dataPort));

//                System.out.println("Test: dataSocket is connected");

                fileChannel = FileChannel.open(Paths.get(fileName), StandardOpenOption.READ);
                seqNo = 1;
                chkSum = 0;
                dataMessage = ByteBuffer.allocate(1005);
                chunk = ByteBuffer.allocate(1000);
                controlMessage = ByteBuffer.allocate(3);

                while ((msgSize = (short) fileChannel.read(chunk)) != -1) {
//                    System.out.println("chunk: " +chunk);
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
                    System.out.print("#");

                    // read control msg
                    dataChannel.read(controlMessage);
                    controlMessage.flip();
//                    System.out.println("control message is received");

                    /** do something **/
//                    seqNo = controlMessage.get();
//                    chkSum = controlMessage.getShort();
                    controlMessage.clear();

                }
                System.out.println(" Completed...");
                fileChannel.close();
                dataChannel.close();
        }
    }

    public static void main(String argv[]) throws Exception {
        if (argv.length == 0)
            serverIP = InetAddress.getByName("127.0.0.1");
        else if (argv.length >= 1) {
            serverIP = InetAddress.getByName(argv[1]);

            if (argv.length == 3) {
                cmdPort = Integer.parseInt(argv[1]);
                dataPort = Integer.parseInt(argv[2]);
            } else {
                System.out.println("Please enter 'FTPClient or FTPClient [ftp server host] [control port number] [data port number]'\n(Default - host : 127.0.0.1, control : 2020, data : 2121)");
                return;
            }
        }

        String request;
        String response;
        String[] parsedResponse;

        inFromUser = new BufferedReader(new InputStreamReader(System.in));

        commandChannel = SocketChannel.open();
        commandChannel.configureBlocking(true);
        commandChannel.connect(new InetSocketAddress(serverIP, cmdPort));
        if (commandChannel.isConnected())
            System.out.println("Connected to " + serverIP.getHostAddress() + " " + cmdPort + " " + dataPort);
        else {
            System.out.println("Failed connection");
            commandChannel.close();
            return;
        }

        System.out.println("Enter commands... (CD {[dir]}, LIST [dir], GET [file], PUT [file], QUIT)");
        while (true) {
            File file;
            request = inFromUser.readLine();
            String[] cmds = request.split(" ", 2);

            // request
            // put 관련
            if (cmds[0].equals("PUT")) {
                if (cmds.length < 2) {
                    System.out.println("Please enter \"PUT [fileName]\"");
                    continue;
                }

                file = new File(cmds[1]);
                if (!file.exists()) {
                    System.out.println("Such file does not exist!");
                    continue;
                }

                byteBuffer = charset.encode(request + '\n' + file.length());
                commandChannel.write(byteBuffer);
            }
            // 그 외 명령어
            else {
                byteBuffer = charset.encode(request);
                commandChannel.write(byteBuffer);
                // quit
                if (cmds[0].equals("QUIT")) {
                    break;
                }
            }

            // read response
            response = "";
            byteBuffer = ByteBuffer.allocate(10000);
            commandChannel.read(byteBuffer);
//            while(commandChannel.read(byteBuffer) != -1) {
                byteBuffer.flip();
                response += charset.decode((byteBuffer)).toString();
//                byteBuffer.clear();
//            }

            parsedResponse = response.split(" ", 2);

            processResponse(cmds[0], Integer.parseInt(parsedResponse[0]), parsedResponse[1]);

        }

        inFromUser.close();
        commandChannel.close();
    }
}
