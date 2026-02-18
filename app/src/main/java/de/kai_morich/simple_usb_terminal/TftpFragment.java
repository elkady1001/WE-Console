package de.kai_morich.simple_usb_terminal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class TftpFragment extends Fragment {

    private TextView statusText, tftpLog, localIpText, progressLabel;
    private SwitchCompat tftpSwitch;
    private ProgressBar tftpProgressBar;

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.hasExtra("message")) {
                String msg = intent.getStringExtra("message");
                if (tftpLog != null) tftpLog.append("\n> " + msg);
            }
            if (intent.hasExtra("progress")) {
                int kb = intent.getIntExtra("progress", 0);
                tftpProgressBar.setIndeterminate(true);
                progressLabel.setText("Transferred: " + kb + " KB");
            }
        }
    };

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_tftp, container, false);
        statusText = view.findViewById(R.id.statusText);
        tftpLog = view.findViewById(R.id.tftpLog);
        localIpText = view.findViewById(R.id.localIpText);
        tftpSwitch = view.findViewById(R.id.tftpSwitch);
        tftpProgressBar = view.findViewById(R.id.tftpProgressBar);
        progressLabel = view.findViewById(R.id.progressLabel);
        Button btnDetectIp = view.findViewById(R.id.btnDetectIp);

        tftpSwitch.setOnCheckedChangeListener((bv, isChecked) -> toggleService(isChecked));
        btnDetectIp.setOnClickListener(v -> localIpText.setText("Ethernet IP: " + getEthernetIp()));
        return view;
    }

    private void toggleService(boolean start) {
        Intent intent = new Intent(getActivity(), TftpServerService.class);
        if (start) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
            String path = prefs.getString("tftp_directory_path", "");
            int port = Integer.parseInt(prefs.getString("tftp_port", "69"));
            intent.putExtra("port", port);
            intent.putExtra("rootPath", path);
            getActivity().startForegroundService(intent);
            statusText.setText("TFTP Server: Running");
        } else {
            getActivity().stopService(intent);
            statusText.setText("TFTP Server: Stopped");
            tftpProgressBar.setIndeterminate(false);
            progressLabel.setText("Status: Idle");
        }
    }

    private String getEthernetIp() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress addr = enumIpAddr.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) return addr.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "Not Connected";
    }

    @Override public void onStart() { super.onStart(); getActivity().registerReceiver(logReceiver, new IntentFilter("TFTP_LOG_UPDATE"), Context.RECEIVER_EXPORTED); }
    @Override public void onStop() { super.onStop(); getActivity().unregisterReceiver(logReceiver); }
}