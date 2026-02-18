package de.kai_morich.simple_usb_terminal;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import org.json.JSONObject;
import java.util.Iterator;

public class CommandManager {
    // تصدير كافة الإعدادات (الأوامر والماكرو) لنص JSON
    public static String exportData(Context context) {
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            JSONObject json = new JSONObject(prefs.getAll());
            return json.toString(4); // تنسيق النص ليكون سهل القراءة
        } catch (Exception e) { return ""; }
    }

    // استيراد البيانات من نص JSON وحفظها في إعدادات التطبيق
    public static void importData(Context context, String jsonData) {
        try {
            JSONObject json = new JSONObject(jsonData);
            SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = json.get(key);
                if (value instanceof String) editor.putString(key, (String) value);
                else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            }
            editor.apply();
        } catch (Exception ignored) {}
    }
}