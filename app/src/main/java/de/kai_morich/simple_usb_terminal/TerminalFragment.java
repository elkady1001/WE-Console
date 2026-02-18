package de.kai_morich.simple_usb_terminal;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected { False, Pending, True }

    private final Handler mainLooper = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver broadcastReceiver;
    private int deviceId, portNum, baudRate;
    private UsbSerialPort usbSerialPort;
    private SerialService service;

    private TextView receiveText;
    private TextView sendText;
    private ImageButton sendBtn;
    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private ControlLines controlLines = new ControlLines();
    private String newline = TextUtil.newline_crlf;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (prefs, key) -> {
        if ((key.equals("baud_rate") || key.equals("baud_rate_custom") || key.equals("flow_control"))
                && usbSerialPort != null && connected == Connected.True) {
            applyCurrentSettings();
        }
    };

    public TerminalFragment() {
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(Constants.INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                    connect(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false));
                }
            }
        };
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        if (getArguments() != null) {
            deviceId = getArguments().getInt("device");
            portNum = getArguments().getInt("port");
            baudRate = getArguments().getInt("baud");
        }
        PreferenceManager.getDefaultSharedPreferences(requireContext()).registerOnSharedPreferenceChangeListener(prefsListener);
    }

    @Override
    public void onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(requireContext()).unregisterOnSharedPreferenceChangeListener(prefsListener);
        if (connected != Connected.False) disconnect();
        if (getActivity() != null) getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if(service != null) service.attach(this);
        else if (getActivity() != null) getActivity().startService(new Intent(getActivity(), SerialService.class));
        if (getActivity() != null) ContextCompat.registerReceiver(getActivity(), broadcastReceiver, new IntentFilter(Constants.INTENT_ACTION_GRANT_USB), ContextCompat.RECEIVER_EXPORTED);
    }

    @Override
    public void onStop() {
        if (getActivity() != null) getActivity().unregisterReceiver(broadcastReceiver);
        if(service != null && getActivity() != null && !getActivity().isChangingConfigurations()) service.detach();
        super.onStop();
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (getActivity() != null) getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { if (getActivity() != null) getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateMacroButtonsUI();
        if(initialStart && service != null) {
            initialStart = false;
            if (getActivity() != null) getActivity().runOnUiThread(() -> connect());
        }
        if(connected == Connected.True) controlLines.start();
    }

    @Override
    public void onPause() { controlLines.stop(); super.onPause(); }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            if (getActivity() != null) getActivity().runOnUiThread(() -> connect());
        }
    }

    @Override public void onServiceDisconnected(ComponentName name) { service = null; }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);
        receiveText.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorRecieveText));
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());
        sendText = view.findViewById(R.id.send_text);
        sendBtn = view.findViewById(R.id.send_btn);
        hexWatcher = new TextUtil.HexWatcher(sendText);
        hexWatcher.enable(hexEnabled);
        sendText.addTextChangedListener(hexWatcher);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));
        setupCommandsButton(view);
        setupMacroButtons(view);
        controlLines.onCreateView(view);
        return view;
    }

    private void applyCurrentSettings() {
        if (usbSerialPort == null) return;
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
            int baud = Integer.parseInt(prefs.getString("baud_rate", "9600"));
            usbSerialPort.setParameters(baud, 8, 1, UsbSerialPort.PARITY_NONE);
            String flow = prefs.getString("flow_control", "none");
            if (flow.equals("rts_cts")) usbSerialPort.setFlowControl(UsbSerialPort.FlowControl.RTS_CTS);
            else if (flow.equals("xon_xoff")) usbSerialPort.setFlowControl(UsbSerialPort.FlowControl.XON_XOFF);
            else usbSerialPort.setFlowControl(UsbSerialPort.FlowControl.NONE);
            status("Applied: " + baud + " bps");
        } catch (Exception e) { status("Update failed"); }
    }

    private void setupCommandsButton(View view) {
        Button btnCommands = view.findViewById(R.id.btnCommands);
        if (btnCommands != null) {
            btnCommands.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(getContext(), v);
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
                String list = prefs.getString("custom_vendors_list", "cisco,juniper,huawei,aruba,hp,aethra");
                for (String vendor : list.split(",")) if(!vendor.isEmpty()) popup.getMenu().add(vendor.toUpperCase());
                popup.setOnMenuItemClickListener(item -> { showVendorCommands(item.getTitle().toString()); return true; });
                popup.show();
            });
        }
    }

    private void showVendorCommands(String vendor) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String savedCommands = prefs.getString("vendor_" + vendor.toLowerCase() + "_list", "");
        if (savedCommands.isEmpty()) { status("Add commands in Settings."); return; }
        final String[] commandsArray = savedCommands.split("\n");
        new AlertDialog.Builder(getActivity()).setTitle(vendor).setItems(commandsArray, (dialog, i) -> send(commandsArray[i])).show();
    }

    private void setupMacroButtons(View view) {
        int[] ids = {R.id.macro1, R.id.macro2, R.id.macro3, R.id.macro4, R.id.macro5, R.id.macro6, R.id.macro7};
        for(int id : ids) {
            Button btn = view.findViewById(id);
            if(btn != null) {
                btn.setOnClickListener(v -> send(btn.getText().toString()));
                btn.setOnLongClickListener(v -> { startActivity(new Intent(getActivity(), CustomSettingsActivity.class)); return true; });
            }
        }
    }

    private void updateMacroButtonsUI() {
        if (getView() == null) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        int[] ids = {R.id.macro1, R.id.macro2, R.id.macro3, R.id.macro4, R.id.macro5, R.id.macro6, R.id.macro7};
        for (int i = 0; i < ids.length; i++) {
            Button btn = getView().findViewById(ids[i]);
            if (btn != null) {
                String type = prefs.getString("macro_m" + (i + 1) + "_config", "custom");
                if ("custom".equals(type)) btn.setText(prefs.getString("macro_m" + (i + 1) + "_custom", "M" + (i + 1)));
                else btn.setText(type.toUpperCase());
            }
        }
    }

    @Override public void onSerialConnect() { status("connected"); connected = Connected.True; controlLines.start(); }
    @Override public void onSerialConnectError(Exception e) { status("connection failed"); disconnect(); }
    @Override public void onSerialIoError(Exception e) { status("connection lost"); disconnect(); }
    @Override public void onSerialRead(byte[] data) { receive(new ArrayDeque<>(Arrays.asList(data))); }
    @Override public void onSerialRead(ArrayDeque<byte[]> datas) { receive(datas); }

    private void receive(ArrayDeque<byte[]> datas) {
        SpannableStringBuilder spn = new SpannableStringBuilder();
        for (byte[] data : datas) {
            String msg = new String(data);
            if (hexEnabled) spn.append(TextUtil.toHexString(data)).append('\n');
            else spn.append(msg.replace("\r", ""));
        }
        receiveText.append(spn);
    }

    private void showFlashBrowser(String output) {
        java.util.List<String> files = new java.util.ArrayList<>();
        Pattern p = Pattern.compile("\\b[\\w-]+\\.(?:text|bin|cfg|dat|log|tar)\\b");
        Matcher m = p.matcher(output);
        while (m.find()) if (!files.contains(m.group())) files.add(m.group());
        if (files.isEmpty()) { status("No files found in output."); return; }
        String[] arr = files.toArray(new String[0]);
        new AlertDialog.Builder(getActivity()).setTitle("Flash Files (WE Console)")
                .setItems(arr, (dialog, which) -> generateTftpCommand(arr[which])).show();
    }

    private void generateTftpCommand(String file) {
        String ip = getEthernetIp();
        if (ip.equals("Not Connected")) { status("Ethernet IP not detected!"); return; }
        send("copy flash:" + file + " tftp://" + ip + "/" + file);
        status("TFTP command sent for: " + file);
    }

    private String getEthernetIp() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress addr = enumIpAddr.nextElement(); // تصحيح nextElement
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) return addr.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "Not Connected";
    }

    @Override public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) { inflater.inflate(R.menu.menu_terminal, menu); }
    @Override public void onPrepareOptionsMenu(@NonNull Menu menu) {
        menu.findItem(R.id.hex).setChecked(hexEnabled);
        controlLines.onPrepareOptionsMenu(menu);
        if (menu.findItem(R.id.menu_log) != null && service != null) menu.findItem(R.id.menu_log).setChecked(service.isLogging());
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) { receiveText.setText(""); return true; }
        if (id == R.id.hex) { hexEnabled = !hexEnabled; item.setChecked(hexEnabled); return true; }
        if (id == R.id.menu_flash_browser) { send("dir flash:"); new Handler(Looper.getMainLooper()).postDelayed(() -> showFlashBrowser(receiveText.getText().toString()), 1500); return true; }
        if (id == R.id.menu_log) { if (service != null) { service.setLogging(!service.isLogging()); status(service.isLogging() ? "Logging started" : "Logging stopped"); } return true; }
        return super.onOptionsItemSelected(item);
    }

    private void connect() { connect(null); }
    private void connect(Boolean permissionGranted) {
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        for(UsbDevice v : usbManager.getDeviceList().values()) if(v.getDeviceId() == deviceId) device = v;
        if(device == null) return;
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if(driver == null) driver = CustomProber.getCustomProber().probeDevice(device);
        if(driver == null) return;
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if(usbConnection == null && permissionGranted == null && !usbManager.hasPermission(driver.getDevice())) {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_MUTABLE : 0;
            usbManager.requestPermission(driver.getDevice(), PendingIntent.getBroadcast(getActivity(), 0, new Intent(Constants.INTENT_ACTION_GRANT_USB).setPackage(getActivity().getPackageName()), flags));
            return;
        }
        if(usbConnection == null) return;
        connected = Connected.Pending;
        try {
            usbSerialPort.open(usbConnection);
            applyCurrentSettings();
            service.connect(new SerialSocket(getActivity().getApplicationContext(), usbConnection, usbSerialPort));
            onSerialConnect();
        } catch (Exception e) { onSerialConnectError(e); }
    }

    private void disconnect() { connected = Connected.False; controlLines.stop(); if(service != null) service.disconnect(); usbSerialPort = null; }
    private void send(String str) {
        if(connected != Connected.True) return;
        try {
            byte[] data = (str + newline).getBytes();
            SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
            spn.setSpan(new ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            service.write(data);
        } catch (Exception e) { status("Send failed"); }
    }

    void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    class ControlLines {
        private final Runnable runnable = this::run;
        private ToggleButton rtsBtn, dtrBtn;
        void onCreateView(View view) {
            rtsBtn = view.findViewById(R.id.controlRts); dtrBtn = view.findViewById(R.id.controlDtr);
            if(rtsBtn != null) rtsBtn.setOnClickListener(v -> toggle("RTS"));
            if(dtrBtn != null) dtrBtn.setOnClickListener(v -> toggle("DTR"));
        }
        void onPrepareOptionsMenu(Menu menu) {}
        void start() { mainLooper.post(runnable); }
        void stop() { mainLooper.removeCallbacks(runnable); }
        private void run() {
            if (connected != Connected.True || usbSerialPort == null) return;
            try {
                EnumSet<UsbSerialPort.ControlLine> lines = usbSerialPort.getControlLines();
                if(rtsBtn != null) rtsBtn.setChecked(lines.contains(UsbSerialPort.ControlLine.RTS));
                mainLooper.postDelayed(runnable, 200);
            } catch (IOException ignored) {}
        }
        private void toggle(String ctrl) { try { if (ctrl.equals("RTS")) usbSerialPort.setRTS(rtsBtn.isChecked()); } catch (Exception ignored) {} }
    }
}