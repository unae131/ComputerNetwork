import java.io.*;
import java.net.*;

public class Client {
    static InetAddress serverIP;

    static BufferedReader inFromUser;
    static DataOutputStream outToServer;
    static BufferedReader inFromServer;

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
                String[] list = inFromServer.readLine().split(",");

                for (int i = 0; i < list.length; i += 2) {
                    System.out.println(list[i] + "," + list[i + 1]);
                }
                return;
            case "GET":
                String[] tmp = phrase.split(" ",5);
                String fileName = tmp[4];
                long fileSize = Long.parseLong(tmp[1]);

                System.out.println("Received " + fileName + " / " + fileSize + " bytes");

                Socket dataSocket = new Socket(serverIP, 2121);

                System.out.println("Test: dataSocket is connected");

                BufferedReader inData = new BufferedReader(new InputStreamReader(dataSocket.getInputStream()));
                // Msg format : {SeqNo(1byte), CHKsum(2bytes)}

                dataSocket.close();
                return;
            case "PUT":
                tmp = phrase.split(" ",7);
                System.out.println(tmp[3] + " transferred / " + tmp[6] + " bytes");

                dataSocket = new Socket(serverIP, 2121);
                System.out.println("Test: dataSocket is connected");
                /***/
                dataSocket.close();
                return;
        }
    }

    public static void main(String argv[]) throws Exception {
        serverIP = InetAddress.getLocalHost();
        String request;
        String response;
        String[] parsedResponse;

        inFromUser = new BufferedReader(new InputStreamReader(System.in));

        Socket commandSocket = new Socket(serverIP, 2020);

        outToServer = new DataOutputStream(commandSocket.getOutputStream());
        inFromServer = new BufferedReader(new InputStreamReader(commandSocket.getInputStream()));

        while (true) {
            File file;
            request = inFromUser.readLine();
            String[] cmds = request.split(" ", 2);

            // request
            // quit
            if (cmds[0].equals("QUIT")) {
                commandSocket.close();
                return;
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

                outToServer.writeBytes(request + '\n' + file.length() + '\n');
            }
            // 그 외 명령어
            else {
                outToServer.writeBytes(request + '\n');
            }

            // read response
            response = inFromServer.readLine();
            parsedResponse = response.split(" ", 2);

            processResponse(cmds[0], Integer.parseInt(parsedResponse[0]), parsedResponse[1]);

        }

    }
}
