import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Server {
    final static String basePath = Paths.get("").toAbsolutePath().toString();
    static String curPath = basePath;
    static BufferedReader inFromClient;
    static DataOutputStream outToClient;

    static long fileSize;

    static String statusCode(int code) {
        String phrase;
        switch (code) {
            case 200: // 성공적인 cd
                phrase = "Moved to " + curPath;
                break;
            case 201: // 성공적인 list
                phrase = "Comprising " + fileSize + " entries";
                break;
            case 202: // 성공적인 get. 보낼 준비 되었다.
                phrase = "Containing " + fileSize + " bytes in ";
                break;
            case 203: // put할 준비 되었다.
                phrase = "Ready to receive " + fileSize + " bytes in ";
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
        File file;

        // "CD"를 제외하곤, arg가 없는 것을 허용하지 않는다.
        if (arg == null && cmd.equals("CD")) {
            return statusCode(200);
        } else if (arg == null) {
            return statusCode(500);
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
                fileSize = files.length; // number of files in the dir

                String response = "\n";
                for (File f : files) {
                    response += f.getName() + "," + (f.isDirectory() ? "-" : f.length()) + ",";
                }

                return statusCode(201) + response;

            case "GET":
                if (file.isDirectory()) {
                    return statusCode(402);
                }
                fileSize = file.length();
                return statusCode(202) + file.getName();

            case "PUT":
                if (file.isDirectory()) {
                    return statusCode(402);
                }
                // /dir/file 이런식으로 주어졌을 경우
                if (!file.getParentFile().exists()){
                    return statusCode(403);
                }
                fileSize = Long.parseLong(inFromClient.readLine());
                System.out.println("Request: " + fileSize);
                return statusCode(203) + file.getName();

            default:
                return statusCode(501);
        }
    }

    static void processData(String cmd){

    }

    public static void main(String argv[]) throws Exception {
        String inputString, outputString;
        String[] input;

        ServerSocket welcomeSocket = new ServerSocket(2020);
        ServerSocket welcomeDataSocket = new ServerSocket(2121);
        Socket commandSocket;

        while (true) {
            commandSocket = welcomeSocket.accept();
            curPath = basePath;

            inFromClient = new BufferedReader(new InputStreamReader(commandSocket.getInputStream()));
            outToClient = new DataOutputStream(commandSocket.getOutputStream());

            while (true) {
                inputString = inFromClient.readLine();
                System.out.println("Request: " + inputString);

                input = inputString.split(" ", 2);
                outputString = processCommand(input[0], input.length > 1 ? input[1] : null);

                outToClient.writeBytes(outputString + "\n");
                System.out.println("Response: " + outputString);

                if((input[0].equals("GET"))
                        && Integer.parseInt(outputString.split(" ")[0]) < 400){
                    Socket dataSocket = welcomeDataSocket.accept();

                }
            }
        }
    }
}
