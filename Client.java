import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class Client {
    static InetAddress serverIP;
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
                System.out.println(phrase.split(" ",3)[2]);
                return;
            case "LIST":
                String[] list = phrase.split("\n")[1].split(",");

                for (int i = 0; i < list.length ; i += 2) {
                    System.out.println(list[i] + "," + list[i + 1]);
                }

                return;
            case "GET":
                String[] tmp = phrase.split(" ",5);
                String fileName = tmp[4];
                long fileSize = Long.parseLong(tmp[1]);

                System.out.println("Received " + fileName + " / " + fileSize + " bytes");

                SocketChannel dataChannel = SocketChannel.open();
                dataChannel.configureBlocking(true);
                dataChannel.connect(new InetSocketAddress(serverIP, 2121));

                FileChannel fileChannel = FileChannel.open(Paths.get(fileName), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                ByteBuffer dataMessage, controlMessage, chunk;
                byte seqNo;
                short chkSum, msgSize;

                while(dataChannel.read(dataMessage = ByteBuffer.allocate(1005)) > 0){
                    // read message
                    dataMessage.flip();
                    seqNo = dataMessage.get();
                    chkSum = dataMessage.getShort();
                    /** do something **/
                    msgSize = dataMessage.getShort();
                    chunk = dataMessage.slice(5, msgSize);
                    System.out.println(seqNo + " " + chkSum + " " + msgSize + " data message is received");

                    // write to file
                    fileChannel.write(chunk);
                    System.out.print("#");

                    // send control message
                    seqNo += 1;
                    controlMessage = ByteBuffer.allocate(3);
                    controlMessage.put(seqNo);
                    controlMessage.putShort(chkSum);
                    fileChannel.write(controlMessage);
                    System.out.println("control message is sent");
                }

                System.out.println(" Completed...");
                fileChannel.close();
                dataChannel.close();
                return;

            case "PUT":
                tmp = phrase.split(" ",7);
                fileName = tmp[3];
                fileSize = Long.parseLong(tmp[6]);
                System.out.println(fileName + " transferred / " + tmp[6] + " bytes");

                dataChannel = SocketChannel.open();
                dataChannel.configureBlocking(true);
                dataChannel.connect(new InetSocketAddress(serverIP, 2121));

                System.out.println("Test: dataSocket is connected");

                fileChannel = FileChannel.open(Paths.get(fileName), StandardOpenOption.READ);
                seqNo = 1;
                chkSum = 0;

                do {
                    dataMessage = ByteBuffer.allocate(1005);
                    chunk = ByteBuffer.allocate(1000);
                    controlMessage = ByteBuffer.allocate(3);

                    if(fileSize != 0 && fileSize % 1000 == 0)
                        msgSize = 1000;
                    else
                        msgSize = (short) (fileSize % 1000);

                    // data message
                    dataMessage.put(seqNo);
                    dataMessage.putShort(chkSum);
                    dataMessage.putShort(msgSize);
                    fileChannel.read(chunk);
                    dataMessage.put(chunk);

                    // send message
                    dataChannel.write(dataMessage);

                    // read control msg
                    dataChannel.read(controlMessage);
                    controlMessage.flip();
                    /** do something **/

                    System.out.print("#");

                    fileSize -= 1000;

                } while (fileSize > 0);

                System.out.println(" Completed...");
                fileChannel.close();
                dataChannel.close();
                return;
        }
    }

    public static void main(String argv[]) throws Exception {
        serverIP = InetAddress.getLocalHost();
        String request;
        String response;
        String[] parsedResponse;

        inFromUser = new BufferedReader(new InputStreamReader(System.in));

        commandChannel = SocketChannel.open();
        commandChannel.configureBlocking(true);
        commandChannel.connect(new InetSocketAddress(serverIP, 2020));

        while (true) {
            File file;
            request = inFromUser.readLine();
            String[] cmds = request.split(" ", 2);

            // request
            // quit
            if (cmds[0].equals("QUIT")) {
                break;
            }
            // put 관련
            else if (cmds[0].equals("PUT")) {
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
            }

            // read response
            /***/byteBuffer = ByteBuffer.allocate(10000);
            commandChannel.read(byteBuffer);
            byteBuffer.flip();

            response = charset.decode((byteBuffer)).toString();
            parsedResponse = response.split(" ", 2);

            processResponse(cmds[0], Integer.parseInt(parsedResponse[0]), parsedResponse[1]);

        }

        inFromUser.close();
        commandChannel.close();
    }
}
