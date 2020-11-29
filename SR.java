import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;

public class SR {
    private ArrayList<Integer> DROP;
    private ArrayList<Integer> TIMEOUT;
    private ArrayList<Integer> BITERROR;

    final int windowSize = 5;
    final int seqRange = 15;

    private SRPacket[] packetArr = new SRPacket[seqRange];
    private int sendBase = 0;
    private byte nextSeq = 0;

    SR() {
        DROP = new ArrayList<>();
        TIMEOUT = new ArrayList<>();
        BITERROR = new ArrayList<>();
    }

    public void addToArray(String name, ArrayList<Integer> arr) {
        switch (name) {
            case "SV_DROP":
            case "DROP":
                DROP.addAll(arr);
                break;
            case "SV_TIMEOUT":
            case "TIMEOUT":
                TIMEOUT.addAll(arr);
                break;
            case "SV_BITERROR":
            case "BITERROR":
                BITERROR.addAll(arr);
                break;
        }
    }

    public void clearAllList() {
        DROP.clear();
        TIMEOUT.clear();
        BITERROR.clear();
    }

    public static ArrayList<Integer> availableArg(String arg) {
        ArrayList<Integer> result = new ArrayList<>();
        String[] parsedArg = arg.split(",");

        for (String l : parsedArg) {
            l = l.trim();

            if (l.charAt(0) != 'R') {
                return null;
            }

            try {
                result.add(Integer.parseInt(l.substring(1)));
            } catch (NumberFormatException nfe) {
                result.clear();
                return null;
            }
        }
        return result;
    }

    private boolean isInRange(int sendBase, byte seqNo) {
        if (sendBase + windowSize - 1 >= seqRange
                && (sendBase <= seqNo || seqNo <= (sendBase + windowSize - 1) % seqRange))
            return true;
        else return sendBase + windowSize - 1 < seqRange && sendBase <= seqNo && seqNo <= sendBase + windowSize - 1;
    }

    private void controlPacket(SRPacket packet) {
        byte seqNo = packet.getSeqNo();
        for (int i = 0; i < DROP.size(); i++) {
            if (DROP.get(i) == seqNo + 1) {
                packetArr[seqNo].setDROP(true);
                DROP.remove(i);
            }
        }
        for (int i = 0; i < TIMEOUT.size(); i++) {
            if (TIMEOUT.get(i) == seqNo + 1) {
                packetArr[seqNo].setTIMEOUT(true);
                TIMEOUT.remove(i);
            }
        }
        for (int i = 0; i < BITERROR.size(); i++) {
            if (BITERROR.get(i) == seqNo + 1) {
                packetArr[seqNo].setBITERROR(true);
                BITERROR.remove(i);
            }
        }
    }

    SRPacket sendDataPacket(SocketChannel dataChannel,
                            short chkSum, short msgSize, ByteBuffer chunk) {
        // make a packet
        SRPacket packet = new SRPacket(dataChannel, nextSeq, chkSum, msgSize, chunk);

        // set DROP, TIMEOUT, BITERROR
        controlPacket(packet);

        // buffer a data message
        packetArr[nextSeq] = packet;

        // send a data message and start a timer
        packetArr[nextSeq].sendPacket();

        // increase nextSeqNo
        this.nextSeq = (byte) ((nextSeq + 1) % seqRange);

        return packet;
    }
    void sendControlPacket(SocketChannel dataChannel, byte seqNo, short chkSum) throws IOException {
        ByteBuffer controlMessage = ByteBuffer.allocate(3);
        controlMessage.put(seqNo);
        controlMessage.putShort(chkSum);
        controlMessage.flip();
        System.out.println("Send ACK " + seqNo);
        dataChannel.write(controlMessage);
    }

    void sendData(SocketChannel dataChannel, FileChannel fileChannel) throws IOException {
        SRPacket packet;
        short msgSize;

        // init send N Messages
        while (isInRange(sendBase, nextSeq)) {
            ByteBuffer chunk = ByteBuffer.allocate(1000);

            if ((msgSize = (short) fileChannel.read(chunk)) == -1)  // no data to read
                break;

            sendDataPacket(dataChannel, (short) 0, msgSize, chunk.flip());
        }
        System.out.println();

        // process control messages
        while (true) {
            ByteBuffer controlMessage = ByteBuffer.allocate(3);

            // no packet left to ack
            if (packetArr[sendBase] == null) {
                dataChannel.shutdownOutput();
                break;
            }

            // read a control message
            if (dataChannel.read(controlMessage) == -1)
                break;
            controlMessage.flip();

            // parse a control message
            byte ack = controlMessage.get();
            short ackChkSum = controlMessage.getShort();

            // process ack'ed
            if (ackChkSum == 0 && isInRange(sendBase, ack)) {
                // no bit error -> successfully ack'ed
                packetArr[ack].setAcked();

                // advance window until no acked
                if (packetArr[ack].getSeqNo() == sendBase) {
                    do {
                        packetArr[ack] = null;
                        sendBase = (sendBase + 1) % seqRange;
                    }
                    while (packetArr[sendBase] != null && packetArr[sendBase].isAcked());
                }

                // send remains
                while (isInRange(sendBase, nextSeq)) {

                    ByteBuffer chunk = ByteBuffer.allocate(1000);
                    // read remained file chunks
                    if ((msgSize = (short) fileChannel.read(chunk)) == -1)
                        break;

                    sendDataPacket(dataChannel, (short) 0, msgSize, chunk.flip());
                }
            }
            System.out.println();
        }
    }

    /* PUT */
    void receiveData(SocketChannel dataChannel, FileChannel fileChannel) throws Exception {
        // Data Message
        byte seqNo;
        short chkSum, msgSize;
        SRPacket packet;

        while (true) {
            // read a data message
            ByteBuffer dataMessage = ByteBuffer.allocate(1005);
            if (dataChannel.read(dataMessage) == -1)
                break;
            dataMessage.flip();

            // parse dataMessage
            seqNo = dataMessage.get();
            chkSum = dataMessage.getShort();
            msgSize = dataMessage.getShort();
            dataMessage.limit(dataMessage.position() + msgSize);

            ByteBuffer chunk = ByteBuffer.allocate(1000);
            chunk.put(dataMessage);
            chunk.flip();
            
            // no bitError & in range
            if (chkSum == 0 && isInRange(sendBase, seqNo)) {
                // send a control message
                sendControlPacket(dataChannel, seqNo, (short) 0);

                // in order
                if (sendBase == seqNo) {
                    // deliver a new packet to file
                    fileChannel.write(chunk);
                    sendBase = (sendBase + 1) % seqRange;

                    // deliver buffered packets
                    while (packetArr[sendBase] != null) {
                        fileChannel.write(packetArr[sendBase].getChunk());
                        packetArr[sendBase] = null;
                        sendBase = (sendBase + 1) % seqRange;
                    }
                } else {
                    // out of order -> buffer
                    packetArr[seqNo] = new SRPacket(dataChannel, seqNo, chkSum, msgSize, chunk);
                }
            }
            // no bitError & last range
            else if (chkSum == 0 && isInRange(sendBase - windowSize, seqNo)) {
                // send an ack
                sendControlPacket(dataChannel, seqNo, (short) 0);
            }

        }
    }

}
