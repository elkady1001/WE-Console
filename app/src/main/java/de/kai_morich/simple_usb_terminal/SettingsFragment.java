package de.kai_morich.simple_usb_terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build; // إضافة هذا الـ Import ضروري لحل مشكلة السطر 116
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public class SettingsFragment extends PreferenceFragmentCompat {

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<Intent> dirLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
                    prefs.edit().putString("tftp_directory_path", uri.toString()).apply();
                    Preference dirPref = findPreference("tftp_directory");
                    if(dirPref != null) dirPref.setSummary("Selected: " + uri.getPath());
                }
            });

    private final ActivityResultLauncher<Intent> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> { if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) saveToFile(result.getData().getData()); });

    private final ActivityResultLauncher<Intent> importLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> { if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) readFromFile(result.getData().getData()); });

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        String screenKey = getArguments() != null ? getArguments().getString("screen_key") : "";
        switch (screenKey) {
            case "pref_serial_screen": setPreferencesFromResource(R.xml.pref_serial, null); break;
            case "pref_terminal_screen": setPreferencesFromResource(R.xml.pref_terminal, null); break;
            case "pref_receive_screen": setPreferencesFromResource(R.xml.pref_receive, null); break;
            case "pref_send_screen": setPreferencesFromResource(R.xml.pref_send, null); break;
            case "pref_misc_screen": setPreferencesFromResource(R.xml.pref_misc, null); break;
            case "pref_commands_screen": setPreferencesFromResource(R.xml.pref_commands, null); loadDynamicVendors(); break;
            case "pref_tftp_screen": setPreferencesFromResource(R.xml.pref_tftp, null); break;
            default: setPreferencesFromResource(R.xml.pref_serial, null);
        }
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        String key = preference.getKey();
        if ("tftp_server_enabled".equals(key)) { toggleTftpService(((SwitchPreferenceCompat) preference).isChecked()); return true; }
        if ("tftp_directory".equals(key)) { Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE); dirLauncher.launch(intent); return true; }
        if ("tftp_detect_ip".equals(key)) { String ip = getEthernetIp(); preference.setSummary("Detected IP: " + ip); return true; }
        if ("tftp_ping_test".equals(key)) { showPingDialog(); return true; }
        if ("export_commands".equals(key)) { triggerExport(); return true; }
        if ("import_commands".equals(key)) { triggerImport(); return true; }
        if ("add_new_vendor".equals(key)) { showAddVendorDialog(); return true; }
        if (key != null && key.startsWith("vendor_")) { showVendorEditorDialog(key, preference.getTitle().toString()); return true; }
        return super.onPreferenceTreeClick(preference);
    }

    private void showPingDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Ping Test (WE Tools)");
        final EditText input = new EditText(requireContext());
        input.setHint("e.g. 192.168.1.1");
        builder.setView(input);
        builder.setPositiveButton("Ping", (dialog, which) -> {
            String ip = input.getText().toString().trim();
            if (!ip.isEmpty()) runPingTask(ip);
        });
        builder.show();
    }

    private void runPingTask(String ip) {
        Toast.makeText(getContext(), "Pinging " + ip + "...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec("ping -c 3 " + ip);
                int status = p.waitFor();
                uiHandler.post(() -> Toast.makeText(getContext(), (status == 0 ? "Ping Success! ✅" : "Ping Failed! ❌"), Toast.LENGTH_LONG).show());
            } catch (Exception e) { uiHandler.post(() -> Toast.makeText(getContext(), "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show()); }
        }).start();
    }

    private void toggleTftpService(boolean start) {
        Intent intent = new Intent(requireActivity(), TftpServerService.class);
        if (start) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
            int port = Integer.parseInt(prefs.getString("tftp_port", "69"));
            String savedPath = prefs.getString("tftp_directory_path", 
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath());

            intent.putExtra("port", port);
            intent.putExtra("root_path", savedPath); // توحيد الاسم مع الـ Service

            // حل مشكلة السطر 116 (API 26+) لضمان عمل الـ Build
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireActivity().startForegroundService(intent);
            } else {
                requireActivity().startService(intent);
            }
        } else {
            requireActivity().stopService(intent);
        }
    }

    private String getEthernetIp() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                if (intf.getName().contains("eth") || intf.getName().contains("usb") || intf.getName().contains("wlan")) {
                    for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                        InetAddress inetAddress = enumIpAddr.nextElement(); // تم تصحيح الخطأ هنا
                        if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                            return inetAddress.getHostAddress();
                        }
                    }
                }
            }
        } catch (SocketException ex) { ex.printStackTrace(); }
        return "Not Connected";
    }

    private void loadDynamicVendors() {
        PreferenceCategory category = findPreference("vendors_category");
        if (category == null) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String vendors = prefs.getString("custom_vendors_list", "cisco,juniper,huawei,aruba,hp,aethra");
        for (String v : vendors.split(",")) {
            if (findPreference("vendor_" + v) == null) {
                Preference p = new Preference(requireContext());
                p.setKey("vendor_" + v);
                p.setTitle(v.toUpperCase());
                p.setSummary("Manage commands for " + v);
                category.addPreference(p);
            }
        }
    }

    private void showAddVendorDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(requireContext()); b.setTitle("Add Vendor"); final EditText input = new EditText(requireContext()); b.setView(input);
        b.setPositiveButton("Add", (dialog, which) -> {
            String name = input.getText().toString().trim().toLowerCase();
            if (!name.isEmpty()) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
                String list = prefs.getString("custom_vendors_list", "cisco,juniper,huawei,aruba,hp,aethra");
                if (!list.contains(name)) { prefs.edit().putString("custom_vendors_list", list + "," + name).apply(); loadDynamicVendors(); }
            }
        }); b.show();
    }

    private void showVendorEditorDialog(String key, String name) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        AlertDialog.Builder b = new AlertDialog.Builder(requireContext()); b.setTitle("Edit " + name); final EditText input = new EditText(requireContext());
        input.setText(prefs.getString(key + "_list", "")); b.setView(input);
        b.setPositiveButton("Save", (dialog, which) -> prefs.edit().putString(key + "_list", input.getText().toString()).apply()); b.show();
    }

    private void triggerExport() { Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/json"); intent.putExtra(Intent.EXTRA_TITLE, "WE_Console_Config.json"); exportLauncher.launch(intent); }
    private void triggerImport() { Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*"); importLauncher.launch(intent); }
    private void saveToFile(Uri uri) { try (OutputStream os = requireContext().getContentResolver().openOutputStream(uri)) { if (os != null) os.write(CommandManager.exportData(requireContext()).getBytes()); Toast.makeText(getContext(), "Exported!", Toast.LENGTH_SHORT).show(); } catch (Exception e) { Toast.makeText(getContext(), "Failed", Toast.LENGTH_SHORT).show(); } }
    private void readFromFile(Uri uri) { try (BufferedReader reader = new BufferedReader(new InputStreamReader(requireContext().getContentResolver().openInputStream(uri)))) { StringBuilder sb = new StringBuilder(); String line; while ((line = reader.readLine()) != null) sb.append(line); CommandManager.importData(requireContext(), sb.toString()); Toast.makeText(getContext(), "Imported!", Toast.LENGTH_SHORT).show(); } catch (Exception e) { Toast.makeText(getContext(), "Failed", Toast.LENGTH_SHORT).show(); } }
}
