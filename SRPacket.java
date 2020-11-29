import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Timer;
import java.util.TimerTask;

public class SRPacket {
    private final byte seqNo;
    private short chkSum = 0;
    private short msgSize;
    private ByteBuffer chunk;

    private SocketChannel dataChannel;
    private boolean acked = false;
    private Timer timer;
    private TimerTask timerTask;

    boolean DROP;
    boolean TIMEOUT;
    boolean BITERROR;

    SRPacket(SocketChannel dataChannel,
             byte seqNo, short chkSum, short msgSize, ByteBuffer chunk) {
        this.dataChannel = dataChannel;
        this.seqNo = seqNo;
        this.chkSum = chkSum;
        this.msgSize = msgSize;
        this.chunk = chunk; // chunk should have been flipped
        timer = new Timer();
    }

    void setDROP(boolean flag) {
        DROP = flag;
    }

    void setTIMEOUT(boolean flag) {
        TIMEOUT = flag;
    }

    void setBITERROR(boolean flag) {
        BITERROR = flag;

        if (flag)
            chkSum = (short) 0xFFFF;
        else
            chkSum = (short) 0;
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

    ByteBuffer getChunk(){
        return chunk; // should be flipped;
    }

    ByteBuffer getDataMessage() {
        ByteBuffer dataMessage = ByteBuffer.allocate(1005);
        dataMessage.put(seqNo);
        dataMessage.putShort(chkSum);
        dataMessage.putShort(msgSize);
        dataMessage.put(chunk); // chunk should have been flipped
        return dataMessage.flip();
    }


    boolean isAcked() {
        return acked;
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
            dataChannel.write(getDataMessage());
            if (BITERROR) {
                BITERROR = false;
                chkSum = (short) 0;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        timer.schedule(timerTask, 1000);
    }

}
