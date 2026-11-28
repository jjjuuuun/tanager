package tanager.archfixture.ui;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.channels.SocketChannel;
import javax.net.ssl.SSLSocketFactory;

/** 위반 픽스처: 소켓 · URL 연결 · TLS · 소켓 채널로 밖에 나간다(NFR-06). */
public final class OpensSockets {
    interface Opener {
        InputStream open(URL url) throws IOException;
    }

    Object socket() {
        return new Socket();
    }

    Object server() throws IOException {
        return new ServerSocket(0);
    }

    Object datagram() throws IOException {
        return new DatagramSocket();
    }

    Object connection(URL url) throws IOException {
        return url.openConnection();
    }

    Object stream(URL url) throws IOException {
        return url.openStream();
    }

    Opener streamRef() {
        return URL::openStream;
    }

    Object tls() {
        return SSLSocketFactory.getDefault();
    }

    Object channel() throws IOException {
        return SocketChannel.open();
    }
}
