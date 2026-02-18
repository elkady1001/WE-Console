package de.kai_morich.simple_usb_terminal;

import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.net.*;

public class TftpServer {
    private DatagramSocket socket;
    private boolean running;
    private File rootDir;
    private int port;
    private LogListener listener;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    public interface LogListener {
        void onLog(String message);
        void onProgress(int kbTransferred);
    }

    public TftpServer(File directory, int port, LogListener listener) {
        this.rootDir = directory;
        this.port = port;
        this.listener = listener;
    }

    public void start() {
        running = true;
        new Thread(() -> {
            try {
                // استخدام DatagramSocket ثابت للسيرفر
                socket = new DatagramSocket(port);
                log("WE TFTP Server Started on Port " + port);

                while (running) {
                    byte[] buf = new byte[516];
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet); // انتظار طلب من الكمبيوتر

                    int opcode = ((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF);
                    if (opcode == 2) { // WRQ (Write Request)
                        handleWrite(packet);
                    }
                }
            } catch (Exception e) { log("Server Error: " + e.getMessage()); shutdown(); }
        }).start();
    }

    private void handleWrite(DatagramPacket initPacket) {
        new Thread(() -> {
            try {
                String fileName = extractFileName(initPacket.getData());
                log("Receiving File: " + fileName);

                File file = new File(rootDir, fileName);
                FileOutputStream fos = new FileOutputStream(file);

                // الرد فوراً بـ ACK لـ Block 0 عشان الكمبيوتر يبدأ يبعت
                sendAck(initPacket.getAddress(), initPacket.getPort(), 0);

                int expectedBlock = 1;
                socket.setSoTimeout(5000); // مهلة 5 ثواني لكل قطعة بيانات

                while (true) {
                    byte[] dataBuf = new byte[516];
                    DatagramPacket dataPacket = new DatagramPacket(dataBuf, dataBuf.length);
                    socket.receive(dataPacket);

                    int blockNum = ((dataBuf[2] & 0xFF) << 8) | (dataBuf[3] & 0xFF);

                    if (blockNum == expectedBlock) {
                        fos.write(dataBuf, 4, dataPacket.getLength() - 4);
                        sendAck(dataPacket.getAddress(), dataPacket.getPort(), blockNum);

                        // تحديث شريط التقدم كل 20 بلوك (10 كيلو بايت)
                        if (blockNum % 20 == 0) {
                            int totalKb = (blockNum * 512) / 1024;
                            uiHandler.post(() -> listener.onProgress(totalKb));
                        }

                        if (dataPacket.getLength() < 516) { // آخر قطعة في الملف
                            fos.close();
                            log("Transfer Finished: " + fileName + " ✅");
                            uiHandler.post(() -> listener.onProgress(0)); // تصفير الشريط
                            break;
                        }
                        expectedBlock++;
                    }
                }
            } catch (Exception e) { log("Error during transfer: " + e.getMessage()); }
        }).start();
    }

    private void sendAck(InetAddress addr, int port, int block) throws IOException {
        byte[] ack = {0, 4, (byte)((block >> 8) & 0xFF), (byte)(block & 0xFF)};
        socket.send(new DatagramPacket(ack, ack.length, addr, port));
    }

    private String extractFileName(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i < data.length && data[i] != 0; i++) sb.append((char) data[i]);
        return sb.toString();
    }

    private void log(String msg) { if (listener != null) uiHandler.post(() -> listener.onLog(msg)); }
    public void shutdown() { running = false; if (socket != null) socket.close(); }
}