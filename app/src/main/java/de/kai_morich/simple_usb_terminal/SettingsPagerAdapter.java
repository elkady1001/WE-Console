package de.kai_morich.simple_usb_terminal;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class SettingsPagerAdapter extends FragmentStateAdapter {

    public SettingsPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        // نستخدم SettingsFragment لكل التبويبات مع تمرير Key مختلف لكل Tab
        SettingsFragment fragment = new SettingsFragment();
        Bundle args = new Bundle();

        switch (position) {
            case 0: args.putString("screen_key", "pref_serial_screen"); break;
            case 1: args.putString("screen_key", "pref_terminal_screen"); break;
            case 2: args.putString("screen_key", "pref_receive_screen"); break;
            case 3: args.putString("screen_key", "pref_send_screen"); break;
            case 4: args.putString("screen_key", "pref_misc_screen"); break;
            case 5: args.putString("screen_key", "pref_commands_screen"); break;
            case 6: args.putString("screen_key", "pref_tftp_screen"); break; // التبويب الجديد لمحمود
            default: args.putString("screen_key", "pref_serial_screen"); break;
        }

        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public int getItemCount() {
        // زيادة العدد لـ 7 ليعمل تبويب الـ TFTP
        return 7;
    }
}