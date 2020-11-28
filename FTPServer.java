import java.io.*;
import java.lang.reflect.Array;
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
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

public class FTPServer {
    final static String basePath = Paths.get("").toAbsolutePath().toString();
    static String curPath;
    static int cmdPort = 2020, dataPort = 2121;
    static File file;
    static long fileSize;
    static ArrayList<Integer> DROP = new ArrayList<>();
    static ArrayList<Integer> TIMEOUT = new ArrayList<>();
    static ArrayList<Integer> BITERROR = new ArrayList<>();

    static class Packet {
        private final byte seqNo;
        private boolean acked = false;
        private SocketChannel dataChannel;
        private ByteBuffer dataMessage;
        private Timer timer;
        private TimerTask timerTask;
        boolean DROP;
        boolean TIMEOUT;
        boolean BITERROR;

        Packet(SocketChannel dataChannel, byte seqNo, ByteBuffer dataMessage) {
            this.seqNo = seqNo;
            this.dataChannel = dataChannel;
            this.dataMessage = dataMessage;
            this.timer = new Timer();
        }

        void sendPacket() {
            if (!DROP) {
                if (!TIMEOUT) {
                    System.out.print(seqNo + " sent, ");
                    sendPacketAndStartTimer();
                } else {
                    TIMEOUT = false;
                    Timer delayTimer = new Timer();
                    TimerTask delayTrans = new TimerTask() {
                        @Override
                        public void run() {
                            System.out.println("\n" + seqNo + " time out & retransmitted");
                            sendPacketAndStartTimer();
                        }
                    };
                    System.out.print(seqNo + " sent, ");
                    delayTimer.schedule(delayTrans, 2000);
                }
            } else { // just start a timer
                DROP = false;
                timerTask = new TimerTask() { // retransmit
                    @Override
                    public void run() {
                        System.out.println("\n" + seqNo + " time out & retransmitted");
                        sendPacketAndStartTimer();
                    }
                };
                System.out.print(seqNo + " sent, ");
                timer.schedule(timerTask, 1000);
            }
        }

        void sendPacketAndStartTimer() {
            timerTask = new TimerTask() { // retransmit
                @Override
                public void run() {
                    System.out.println("\n" + seqNo + " time out & retransmitted");
                    sendPacketAndStartTimer();
                }
            };

            try {
                dataMessage.flip();
//                System.out.println(dataMessage);
                dataChannel.write(dataMessage);
                if (BITERROR) {
                    BITERROR = false;
                    dataMessage.position(1);
                    dataMessage.putShort((short) 0);
                    dataMessage.position(dataMessage.limit());
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            timer.schedule(timerTask, 1000);
        }

        void setDROP() {
            DROP = true;
        }

        void setTIMEOUT() {
            TIMEOUT = true;
        }

        void setBITERROR() {
            BITERROR = true;
            dataMessage.position(1);
            dataMessage.putShort((short) 0xFFFF);
            dataMessage.position(dataMessage.limit());
        }

        void setAcked() {
            acked = true;
            timerTask.cancel();
            // print
            System.out.print(seqNo + " acked, ");
        }

        byte getSeqNo() {
            return seqNo;
        }

        boolean isAcked() {
            return acked;
        }

        ByteBuffer getDataMessage() {
            return dataMessage;
        }
    }

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
            case 204: // SERVER DROP
                phrase = "Drop server packets";
                break;
            case 205: // SERVER TIMEOUT
                phrase = "Drop server packets";
                break;
            case 206: // SERVER BITERROR
                phrase = "Drop server packets";
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
                phrase = "Wrong argument (CD <dir>, LIST [dir], GET [file], PUT [file], SERVER [DROP/TIMEOUT/BITERROR] [R#,R#...], QUIT)";
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

    static String processCommand(String cmd, String[] arg) throws IOException {
        Path argPath;
        // wrong command
        if (!cmd.equals("CD") && !cmd.equals("LIST")
                && !cmd.equals("PUT") && !cmd.equals("GET") && !cmd.equals("SERVER"))
            return statusCode(501);

        // arg is always needed, except "CD"
        if (arg == null && cmd.equals("CD"))
            return statusCode(200);
        else if (arg == null)
            return statusCode(500);
        else
            argPath = Paths.get(arg[0]);

        if (!cmd.equals("SERVER")) {
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
        }

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
            case "SERVER":
                String[] packetControl = arg[0].split(" ");
                ArrayList<Integer> arr;
                if (packetControl[0].equals("DROP"))
                    arr = DROP;
                else if (packetControl[0].equals("TIMEOUT"))
                    arr = TIMEOUT;
                else if (packetControl[0].equals("BITERROR"))
                    arr = BITERROR;
                else
                    return statusCode(501);

                for (int i = 1; i < packetControl.length; i++) {
                    arr.add(Integer.parseInt(packetControl[i].trim().substring(1)));
                }

                if (packetControl[0].equals("DROP"))
                    return statusCode(204);
                else if (packetControl[0].equals("TIMEOUT"))
                    return statusCode(205);
                else
                    return statusCode(206);

            default:
                return statusCode(501);
        }
    }

    static void processData(String cmd) throws Exception {
        ServerSocketChannel dataSSC = ServerSocketChannel.open();
        dataSSC.configureBlocking(true);
        dataSSC.bind(new InetSocketAddress(dataPort));

        SocketChannel dataChannel = dataSSC.accept();
        FileChannel fileChannel;

        switch (cmd) {
            case "GET":
                fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
                sendData(dataChannel, fileChannel, DROP, TIMEOUT, BITERROR);
                System.out.println("\nCompleted...");

                DROP.clear();
                TIMEOUT.clear();
                BITERROR.clear();

                fileChannel.close();
                break;

            case "PUT":
                fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                receiveData(dataChannel, fileChannel);
                fileChannel.close();
        }

        dataChannel.close();
        dataSSC.close();

    }

    static boolean isInRange(int sendBase, int windowSize, byte seqNo) {
        if (sendBase + windowSize - 1 >= 15
                && (sendBase <= seqNo || seqNo <= (sendBase + windowSize - 1) % 15))
            return true;
        else if (sendBase + windowSize - 1 < 15 && sendBase <= seqNo && seqNo <= sendBase + windowSize - 1)
            return true;
        return false;
    }

    /* GET
     * seqNo 0..15
     * window size 5
     * sender timout 1
     */
    /* Message formats:
     * {SeqNo(1byte), CHKsum(2bytes), Size(2byte), data chunk(1000bytes)}
     * {SeqNo(1byte), CHKsum(2bytes)}
     */
    static void sendData(SocketChannel dataChannel, FileChannel fileChannel,
                         ArrayList<Integer> DROP, ArrayList<Integer> TIMEOUT,
                         ArrayList<Integer> BITERROR) throws IOException {
        // For SR
        Packet[] packetArr = new Packet[15];
        final int windowSize = 5;
        int sendBase = 0;

        // Data Message
        byte seqNo = 0; // next Sequence number
        short chkSum = 0, msgSize;
        ByteBuffer chunk = ByteBuffer.allocate(1000);

        // init send N Messages
        while (isInRange(sendBase, windowSize, seqNo)) {
//            System.out.println("Let's Read a chunk from a file");
            chunk.clear();
            if ((msgSize = (short) fileChannel.read(chunk)) == -1)  // no data to read
                break;
            chunk.flip();

            // make a data message
//            System.out.println("Let's make a data message");
            ByteBuffer dataMessage = ByteBuffer.allocate(1005);
            dataMessage.put(seqNo);
            dataMessage.putShort(chkSum);
            dataMessage.putShort(msgSize);
            dataMessage.put(chunk);
//            System.out.println(dataMessage);

            // buffer a data message
//            System.out.println("Buffer a data message");
            packetArr[seqNo] = new Packet(dataChannel, seqNo, dataMessage);

            // set DROP, TIMEOUT, BITERROR
//            System.out.println("set DROP, TIMEOUT, BITERROR");
            for(int i = 0; i < DROP.size(); i++){
                if(DROP.get(i) == seqNo + 1) {
                    packetArr[seqNo].setDROP();
                    DROP.remove(i);
                }
            }
            for(int i = 0; i < TIMEOUT.size(); i++){
                if(TIMEOUT.get(i) == seqNo + 1) {
                    packetArr[seqNo].setTIMEOUT();
                    TIMEOUT.remove(i);
                }
            }
            for(int i = 0; i < BITERROR.size(); i++){
                if(BITERROR.get(i) == seqNo + 1) {
                    packetArr[seqNo].setBITERROR();
                    BITERROR.remove(i);
                }
            }

            // send a data message and start a timer
//            System.out.println("send a data message");
            packetArr[seqNo].sendPacket();

            seqNo = (byte) ((seqNo + 1) % 15);
        }
        System.out.println();

        // read Control Messages
        while (true) {
            ByteBuffer controlMessage = ByteBuffer.allocate(3);
            if (packetArr[sendBase] == null) {// all packets are ack'ed
                dataChannel.shutdownOutput();
                break;
            }

//            System.out.println("read a control message");
            if (dataChannel.read(controlMessage) == -1)
                break;
//            System.out.println(controlMessage);
            controlMessage.flip();

            byte ack = controlMessage.get();
            short ackChkSum = controlMessage.getShort();
//            System.out.println(ack + " " + ackChkSum);

            // ack'ed
            if (ackChkSum == 0 && isInRange(sendBase, windowSize, ack)) {
//                System.out.println("no bit error -> successfully ack'ed");
//                System.out.println();
                // no bit error -> successfully ack'ed
                packetArr[ack].setAcked();

                // advance window
                if (packetArr[ack].getSeqNo() == sendBase) {
                    do {
                        packetArr[ack] = null;
                        sendBase = (sendBase + 1) % 15;
                    }
                    while (packetArr[sendBase] != null && packetArr[sendBase].isAcked());

                }
                // send remains
                while (isInRange(sendBase, windowSize, seqNo)) {
                    chunk.clear();
                    if ((msgSize = (short) fileChannel.read(chunk)) == -1)
                        break;
                    chunk.flip();

                    // make a data message
                    ByteBuffer dataMessage = ByteBuffer.allocate(1005);
                    dataMessage.put(seqNo);
                    dataMessage.putShort(chkSum);
                    dataMessage.putShort(msgSize);
                    dataMessage.put(chunk);

                    // buffer a data message
                    packetArr[seqNo] = new Packet(dataChannel, seqNo, dataMessage);

                    // set DROP, TIMEOUT, BITERROR
//            System.out.println("set DROP, TIMEOUT, BITERROR");
                    for(int i = 0; i < DROP.size(); i++){
                        if(DROP.get(i)-1 == seqNo) {
                            packetArr[seqNo].setDROP();
                            DROP.remove(i);
                        }
                    }
                    for(int i = 0; i < TIMEOUT.size(); i++){
                        if(TIMEOUT.get(i)-1 == seqNo) {
                            packetArr[seqNo].setTIMEOUT();
                            TIMEOUT.remove(i);
                        }
                    }
                    for(int i = 0; i < BITERROR.size(); i++){
                        if(BITERROR.get(i)-1 == seqNo) {
                            packetArr[seqNo].setBITERROR();
                            BITERROR.remove(i);
                        }
                    }

                    // send a data message and start a timer
//                    System.out.println("send a data message " + seqNo);
                    System.out.println();
                    packetArr[seqNo].sendPacket();

                    seqNo = (byte) ((seqNo + 1) % 15);
                }
            }
        }
    }

    /* PUT */
    static void receiveData(SocketChannel dataChannel, FileChannel fileChannel) throws Exception {
        // For SR
        Packet[] packetArr = new Packet[15];
        final int windowSize = 5;
        int sendBase = 0;

        // Data Message
        byte seqNo;
        short chkSum, msgSize;
//        ByteBuffer chunk;

        while (true) {
//            System.out.println("Read a data message");
            ByteBuffer dataMessage = ByteBuffer.allocate(1005);
            if (dataChannel.read(dataMessage) == -1)
                break;

            dataMessage.flip();

            // parse dataMessage
//            System.out.println("Parse a data message");
            seqNo = dataMessage.get();
            chkSum = dataMessage.getShort();
            msgSize = dataMessage.getShort();
            dataMessage.limit(dataMessage.position() + msgSize);
//            byte[] chunkArr = new byte[msgSize];
//            dataMessage.get(new byte[msgSize]);
//            chunk = ByteBuffer.wrap(chunkArr);
//            System.out.println(chunk);

            // no bitError & in range
            if (chkSum == 0 && isInRange(sendBase, windowSize, seqNo)) {
                // send a control message
                ByteBuffer controlMessage = ByteBuffer.allocate(3);
                controlMessage.put(seqNo);
                controlMessage.putShort(chkSum);
                controlMessage.flip();
                System.out.println("Send ACK " + seqNo);
//                System.out.println(controlMessage);
                dataChannel.write(controlMessage);
                controlMessage.clear();

                // in order
                if (sendBase == seqNo) {
                    // deliver a new packet to file
//                    System.out.println("Deliver a new packet");
//                    chunk.flip();
                    fileChannel.write(dataMessage);
                    sendBase = (sendBase + 1) % 15;

                    // deliver buffered
//                    System.out.println("Deliver buffered packets");
                    while (packetArr[sendBase] != null) {
//                        chunk = packetArr[sendBase].getDataMessage();
                        fileChannel.write(packetArr[sendBase].getDataMessage());
//                        packetArr[sendBase].getDataMessage().flip();
                        packetArr[sendBase] = null;
                        sendBase = (sendBase + 1) % 15;
                    }
                } else {
                    // out of order -> buffer
                    packetArr[seqNo] = new Packet(dataChannel, seqNo, dataMessage);
                }
            }
            // no bitError & last range
            else if (chkSum == 0 && isInRange(sendBase - windowSize, windowSize, seqNo)) {
                // send an ack
                ByteBuffer controlMessage = ByteBuffer.allocate(3);
                controlMessage.put(seqNo);
                controlMessage.putShort(chkSum);
                controlMessage.flip();
                System.out.println("Send ACK " + seqNo);
                dataChannel.write(controlMessage);
                controlMessage.clear();
            }

        }
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
                            System.out.println(dataPort + " port is being occupied.");
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