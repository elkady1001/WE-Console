package de.kai_morich.simple_usb_terminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import java.io.IOException;
import java.util.ArrayDeque;

public class SerialService extends Service implements SerialListener {

    class SerialBinder extends Binder {
        SerialService getService() { return SerialService.this; }
    }

    private enum QueueType {Connect, ConnectError, Read, IoError}

    private static class QueueItem {
        QueueType type;
        ArrayDeque<byte[]> datas;
        Exception e;
        QueueItem(QueueType type) { this.type=type; if(type==QueueType.Read) init(); }
        QueueItem(QueueType type, Exception e) { this.type=type; this.e=e; }
        QueueItem(QueueType type, ArrayDeque<byte[]> datas) { this.type=type; this.datas=datas; }
        void init() { datas = new ArrayDeque<>(); }
        void add(byte[] data) { datas.add(data); }
    }

    private final Handler mainLooper = new Handler(Looper.getMainLooper());
    private final IBinder binder = new SerialBinder();
    private final ArrayDeque<QueueItem> queue1 = new ArrayDeque<>(), queue2 = new ArrayDeque<>();
    private final QueueItem lastRead = new QueueItem(QueueType.Read);
    private SerialSocket socket;
    private SerialListener listener;
    private boolean connected;
    private boolean isLogging = false; // متغير حالة التسجيل

    public boolean isLogging() { return isLogging; }
    public void setLogging(boolean logging) { this.isLogging = logging; }

    @Override public void onDestroy() { cancelNotification(); disconnect(); super.onDestroy(); }
    @Nullable @Override public IBinder onBind(Intent intent) { return binder; }

    public void connect(SerialSocket socket) throws IOException {
        socket.connect(this);
        this.socket = socket;
        connected = true;
    }

    public void disconnect() {
        connected = false;
        cancelNotification();
        if(socket != null) { socket.disconnect(); socket = null; }
    }

    public void write(byte[] data) throws IOException {
        if(!connected) throw new IOException("not connected");
        socket.write(data);
    }

    public void attach(SerialListener listener) {
        if(Looper.getMainLooper().getThread() != Thread.currentThread()) throw new IllegalArgumentException("not in main thread");
        initNotification();
        cancelNotification();
        synchronized (this) { this.listener = listener; }
        for(QueueItem item : queue1) {
            switch(item.type) {
                case Connect: listener.onSerialConnect(); break;
                case ConnectError: listener.onSerialConnectError(item.e); break;
                case Read: listener.onSerialRead(item.datas); break;
                case IoError: listener.onSerialIoError(item.e); break;
            }
        }
        queue1.clear(); queue2.clear();
    }

    public void detach() { if(connected) createNotification(); listener = null; }

    private void initNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel nc = new NotificationChannel(Constants.NOTIFICATION_CHANNEL, "Background service", NotificationManager.IMPORTANCE_LOW);
            nc.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(nc);
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    public boolean areNotificationsEnabled() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel nc = nm.getNotificationChannel(Constants.NOTIFICATION_CHANNEL);
        return nm.areNotificationsEnabled() && nc != null && nc.getImportance() > NotificationManager.IMPORTANCE_NONE;
    }

    private void createNotification() {
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent restartPendingIntent = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), flags);
        Notification notification = new NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(getResources().getColor(R.color.purple_we))
                .setContentTitle("WE Console Active")
                .setContentText(socket != null ? "Connected to " + socket.getName() : "Service Running")
                .setContentIntent(restartPendingIntent)
                .setOngoing(true)
                .build();
        startForeground(Constants.NOTIFY_MANAGER_START_FOREGROUND_SERVICE, notification);
    }

    private void cancelNotification() { stopForeground(true); }

    public void onSerialConnect() { synchronized (this) { if (listener != null) mainLooper.post(() -> { if (listener != null) listener.onSerialConnect(); else queue1.add(new QueueItem(QueueType.Connect)); }); else queue2.add(new QueueItem(QueueType.Connect)); } }
    public void onSerialConnectError(Exception e) { synchronized (this) { if (listener != null) mainLooper.post(() -> { if (listener != null) listener.onSerialConnectError(e); else { queue1.add(new QueueItem(QueueType.ConnectError, e)); disconnect(); } }); else { queue2.add(new QueueItem(QueueType.ConnectError, e)); disconnect(); } } }
    public void onSerialRead(ArrayDeque<byte[]> datas) { throw new UnsupportedOperationException(); }
    public void onSerialRead(byte[] data) { synchronized (this) { if (listener != null) { boolean first; synchronized (lastRead) { first = lastRead.datas.isEmpty(); lastRead.add(data); } if(first) mainLooper.post(() -> { ArrayDeque<byte[]> datas; synchronized (lastRead) { datas = lastRead.datas; lastRead.init(); } if (listener != null) listener.onSerialRead(datas); else queue1.add(new QueueItem(QueueType.Read, datas)); }); } else { if(queue2.isEmpty() || queue2.getLast().type != QueueType.Read) queue2.add(new QueueItem(QueueType.Read)); queue2.getLast().add(data); } } }
    public void onSerialIoError(Exception e) { synchronized (this) { if (listener != null) mainLooper.post(() -> { if (listener != null) listener.onSerialIoError(e); else { queue1.add(new QueueItem(QueueType.IoError, e)); disconnect(); } }); else { queue2.add(new QueueItem(QueueType.IoError, e)); disconnect(); } } }
}