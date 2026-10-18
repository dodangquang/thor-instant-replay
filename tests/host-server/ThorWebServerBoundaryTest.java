package com.thor.instantreplay.share;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Sends HTTP requests to the actual Gradle-compiled server, against an owned temporary directory. */
public final class ThorWebServerBoundaryTest {
    private static int checks;
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    private static byte[] request(int port,String path) throws Exception {
        try(Socket socket=new Socket(InetAddress.getLoopbackAddress(),port)){
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(("GET "+path+" HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            return socket.getInputStream().readAllBytes();
        }
    }
    public static void main(String[] args) throws Exception {
        Path parent=new File(args[0]).getCanonicalFile().toPath();Files.createDirectories(parent);
        Path scratch=Files.createTempDirectory(parent,"clip-http-").toRealPath();
        List<Path> owned=new ArrayList<>();owned.add(scratch);
        ThorWebServer server=null;
        try {
            Path movies=Files.createDirectory(scratch.resolve("Movies"));owned.add(movies);
            Path clips=Files.createDirectory(movies.resolve("ThorClips"));owned.add(clips);
            String name="Pha đẹp + 100% clip & phần 2.MP4";
            byte[] video={0,1,2,3,4,5,6,7};
            Path clip=Files.write(clips.resolve(name),video);owned.add(clip);
            Path outside=Files.write(movies.resolve("outside.mp4"),new byte[]{99,98,97});owned.add(outside);
            System.setProperty("clip.test.movies",movies.toString());
            int port;try(ServerSocket available=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=available.getLocalPort();}
            server=new ThorWebServer(port,true);check(server.start(),"actual server started");
            byte[] dashboard=request(port,"/");String html=new String(dashboard,StandardCharsets.UTF_8);
            check(html.startsWith("HTTP/1.1 200"),"dashboard status");
            check(html.contains(ClipFilePolicy.queryName(name)),"dashboard link encodes complete filename");
            check(html.contains("clip &amp; phần"),"dashboard escapes filename display");
            for(String route:new String[]{"/video?file=","/download?file="}){
                byte[] response=request(port,route+ClipFilePolicy.queryName(name));
                String header=new String(response,StandardCharsets.ISO_8859_1);
                check(header.startsWith("HTTP/1.1 200"),"valid clip route "+route);
                int body=header.indexOf("\r\n\r\n")+4;
                check(Arrays.equals(video,Arrays.copyOfRange(response,body,response.length)),"clip bytes preserved "+route);
                for(String attack:new String[]{"../outside.mp4","%2e%2e%2foutside.mp4","%2e%2e%5coutside.mp4","%252e%252e%252foutside.mp4","%25252e%25252e%25252foutside.mp4","%2foutside.mp4","clip%00.mp4","clip%0d%0aheader.mp4"}){
                    String denied=new String(request(port,route+attack),StandardCharsets.ISO_8859_1);
                    check(denied.startsWith("HTTP/1.1 404"),"boundary denied "+route+attack);
                }
            }
            check(Arrays.equals(Files.readAllBytes(clip),video),"original clip not modified");
            check(Files.readAllBytes(outside)[0]==99,"outside file not modified");
            System.out.println("PASS "+checks+" actual-server HTTP boundary checks");
        } finally {
            if(server!=null)server.stop();
            for(int i=owned.size()-1;i>=0;i--){Path path=owned.get(i).toAbsolutePath().normalize();if(!path.startsWith(scratch))throw new SecurityException("Cleanup escaped scratch");Files.deleteIfExists(path);}
        }
    }
}
