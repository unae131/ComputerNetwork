import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class FTPClient {
    static InetAddress serverIP;
    static int cmdPort = 2020, dataPort = 2121;
    static String putFilePath;

    static void processResponse(String cmd, int statusCode, String phrase) throws IOException {
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
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                System.out.println("Received " + fileName + " / " + fileSize + " bytes");

                ByteBuffer dataMessage = ByteBuffer.allocate(1005);
                ByteBuffer controlMessage = ByteBuffer.allocate(3);
                byte seqNo;
                short chkSum, msgSize;

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

                // open data channel
                dataChannel = SocketChannel.open();
                dataChannel.configureBlocking(true);
                dataChannel.connect(new InetSocketAddress(serverIP, dataPort));

                // open file channel
                fileChannel = FileChannel.open(Paths.get(putFilePath), StandardOpenOption.READ);
                System.out.println(fileName + " transferred / " + fileSize + " bytes");

                dataMessage = ByteBuffer.allocate(1005);
                controlMessage = ByteBuffer.allocate(3);
                ByteBuffer chunk = ByteBuffer.allocate(1000);
                seqNo = 1;
                chkSum = 0;

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
                    System.out.print("#");

                    // read control msg
                    dataChannel.read(controlMessage);
                    controlMessage.flip();

                    /* do something */
//                    seqNo = controlMessage.get();
//                    chkSum = controlMessage.getShort();
                    controlMessage.clear();

                }

                System.out.println(" Completed...");
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
                    + "\nEnter commands (CD <dir>, LIST [dir], GET [file], PUT [file], QUIT)");
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
            request = inFromUser.readLine();
            String[] cmds = request.split(" ", 2);

            // request
            if (cmds[0].equals("PUT")) { // PUT
                if (cmds.length < 2) {
                    System.out.println("Please enter 'PUT [fileName]'");
                    continue;
                }

                file = new File(cmds[1]);
                if (!file.exists()) {
                    System.out.println("Such file does not exist!");
                    continue;
                }
                putFilePath = file.getCanonicalPath();
                requestBuffer = charset.encode(request + "\n" + file.length() + "\n");
                commandChannel.write(requestBuffer);
            } else { // other commands
                requestBuffer = charset.encode(request);
                commandChannel.write(requestBuffer);
                // quit
                if (request.equals("QUIT")) {
                    break;
                }
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

        inFromUser.close();
        commandChannel.close();
    }
}