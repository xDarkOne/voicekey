package dev.xdark.voicekey;

import android.content.Context;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * A minimal adb client that talks to the device's own adbd over the loopback, so the app can give
 * itself the permissions that otherwise take a computer. "USB debugging" and "Wi-Fi debugging"
 * must be on in the developer settings: only then the system asks "Allow USB debugging?" for a
 * new key (with "always allow" ticked it never asks again). The key is made on first use and
 * stays in the app's private storage.
 */
final class SelfAdb {
    private static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f,
            A_OKAY = 0x59414b4f, A_CLSE = 0x45534c43, A_WRTE = 0x45545257;
    private static final int PORT = 5555;
    /** DER prefix of a SHA-1 DigestInfo: adb signs the 20-byte token as if it were a SHA-1 hash. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02,
            0x1a, 0x05, 0x00, 0x04, 0x14};

    private SelfAdb() {}

    /** A failure the user can act on; the message says how. */
    static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }
    }

    private static final class Message {
        int command, arg0, arg1;
        byte[] data;
    }

    /** Runs a shell command as the adb shell user and returns what it printed. */
    static String shell(Context context, String command) throws Failure {
        try (Socket socket = new Socket()) {
            try {
                socket.connect(new InetSocketAddress("127.0.0.1", PORT), 3_000);
            } catch (IOException e) {
                throw new Failure("отладка по сети выключена");
            }
            // The user may take a while with the system's "Allow USB debugging?" question.
            socket.setSoTimeout(90_000);
            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            KeyPair key = key(context);

            send(out, A_CNXN, 0x01000000, 256 * 1024, "host::voicekey\0".getBytes(StandardCharsets.US_ASCII));
            boolean signed = false;
            while (true) {
                Message m = read(in);
                if (m.command == A_CNXN) break;
                if (m.command != A_AUTH || m.arg0 != 1) throw new Failure("неожиданный ответ adbd");
                if (!signed) {
                    signed = true;
                    send(out, A_AUTH, 2, 0, sign(key.getPrivate(), m.data));
                } else {
                    send(out, A_AUTH, 3, 0, publicKey(key.getPublic()));
                }
            }

            send(out, A_OPEN, 1, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            while (true) {
                Message m = read(in);
                if (m.command == A_WRTE) {
                    result.write(m.data);
                    send(out, A_OKAY, 1, m.arg0, new byte[0]);
                } else if (m.command == A_CLSE) {
                    break;
                }
            }
            return result.toString("UTF-8").trim();
        } catch (Failure e) {
            throw e;
        } catch (java.net.SocketTimeoutException e) {
            throw new Failure("не дождались подтверждения на экране");
        } catch (IOException e) {
            throw new Failure("запрос отладки отклонён");
        } catch (java.security.GeneralSecurityException e) {
            throw new Failure("ключ: " + e);
        }
    }

    private static KeyPair key(Context context) throws java.security.GeneralSecurityException, IOException {
        File priv = new File(context.getFilesDir(), "adbkey.pk8");
        File pub = new File(context.getFilesDir(), "adbkey.pub");
        KeyFactory factory = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            return new KeyPair(
                    factory.generatePublic(new java.security.spec.X509EncodedKeySpec(Files.readAllBytes(pub.toPath()))),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv.toPath()))));
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Files.write(priv.toPath(), pair.getPrivate().getEncoded());
        Files.write(pub.toPath(), pair.getPublic().getEncoded());
        return pair;
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws java.security.GeneralSecurityException {
        Signature signature = Signature.getInstance("NONEwithRSA");
        signature.initSign(key);
        signature.update(SHA1_PREFIX);
        signature.update(token);
        return signature.sign();
    }

    /** The key in adb's own layout (length, n0inv, modulus, R^2 mod n, exponent), base64, with a name. */
    private static byte[] publicKey(PublicKey key) {
        java.security.interfaces.RSAPublicKey rsa = (java.security.interfaces.RSAPublicKey) key;
        BigInteger n = rsa.getModulus();
        int words = 64;
        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = two32.subtract(n.mod(two32).modInverse(two32));
        BigInteger rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 4 * words * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        for (int i = 0; i < words; i++) b.putInt(n.shiftRight(32 * i).and(two32.subtract(BigInteger.ONE)).intValue());
        for (int i = 0; i < words; i++) b.putInt(rr.shiftRight(32 * i).and(two32.subtract(BigInteger.ONE)).intValue());
        b.putInt(rsa.getPublicExponent().intValue());
        String text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " voicekey@projector\0";
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static void send(OutputStream out, int command, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte x : data) sum += x & 0xff;
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(command ^ 0xffffffff);
        b.put(data);
        out.write(b.array());
        out.flush();
    }

    private static Message read(DataInputStream in) throws IOException {
        byte[] header = new byte[24];
        in.readFully(header);
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int length = b.getInt();
        if (length < 0 || length > 1 << 20) throw new IOException("bad length " + length);
        m.data = new byte[length];
        in.readFully(m.data);
        return m;
    }
}
