package dev.xdark.voicekey;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The remote's microphone, from Hisense's HsPcmShare service: Android's own microphone input is
 * silent on this projector, the remote's audio is served decoded on the abstract socket
 * GETSYSTEMPCMDATALOCAL as 16 kHz mono 16-bit PCM. One recording per connection:
 *
 * <pre>
 * start_pcmrecorder_v_0#&lt;who&gt;#0#4        -> "OK#&lt;client&gt;#"   (4: the remote)
 * get_pcmdata_v_0#&lt;max bytes&gt;#&lt;client&gt;  -> PCM, or a single 0 byte while none is buffered
 * stop_pcmrecorder_v_0#&lt;client&gt;#4        -> "OK"
 * </pre>
 *
 * The service keeps a few 40 ms frames per client and drops the rest, so it is read a frame at a
 * time, without pause.
 */
final class RemoteMic implements AutoCloseable {
    static final int SAMPLE_RATE = 16_000;
    /** One frame of the service: 40 ms. */
    static final int FRAME_BYTES = 1_280;

    private static final String SOCKET = "GETSYSTEMPCMDATALOCAL";
    private static final String WHO = "voicekey";
    private static final int SOURCE_REMOTE = 4;

    private final LocalSocket socket;
    private final InputStream in;
    private final OutputStream out;
    private final String client;

    RemoteMic() throws IOException {
        socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(10_000);
            in = socket.getInputStream();
            out = socket.getOutputStream();
            send("start_pcmrecorder_v_0#" + WHO + "#0#" + SOURCE_REMOTE);
            byte[] reply = new byte[256];
            int n = in.read(reply);
            String text = n > 0 ? new String(reply, 0, n, StandardCharsets.US_ASCII) : "";
            String[] parts = text.split("#");
            if (parts.length < 2 || !"OK".equals(parts[0]) || parts[1].isEmpty()) {
                throw new IOException("remote mic refused: \"" + text + "\"");
            }
            client = parts[1];
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    /** Up to one frame into [dst]; 0 while the service has none yet, -1 once it hung up. */
    int read(byte[] dst) throws IOException {
        send("get_pcmdata_v_0#" + Math.min(dst.length, FRAME_BYTES) + "#" + client);
        int n = in.read(dst);
        if (n == 1 && dst[0] == 0) return 0;
        return n;
    }

    private void send(String command) throws IOException {
        out.write(command.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    @Override
    public void close() {
        try {
            send("stop_pcmrecorder_v_0#" + client + "#" + SOURCE_REMOTE);
        } catch (IOException ignored) {
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
