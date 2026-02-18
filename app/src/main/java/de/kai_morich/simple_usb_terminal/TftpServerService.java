package de.kai_morich.simple_usb_terminal;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import java.io.File;
import java.io.IOException;
import org.apache.commons.net.tftp.TFTPServer;
public class TftpServerService extends Service {
    private TFTPServer tftpServer;
    private boolean isRunning = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isRunning && intent != null) {
            String path = intent.getStringExtra("root_path");
            int port = intent.getIntExtra("port", 69);
            if (path != null) {
                try {
                    File root = new File(path);
                    if (!root.exists()) root.mkdirs();

                    // تشغيل السيرفر للنسخة 3.12.0
                    tftpServer = new TFTPServer(root, root, port,
                            TFTPServer.ServerMode.GET_AND_PUT, null, null);
                    isRunning = true;
                } catch (IOException e) { e.printStackTrace(); }
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (tftpServer != null) tftpServer.shutdown();
        isRunning = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}