package de.kai_morich.simple_usb_terminal;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.navigation.NavigationView;

public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener, NavigationView.OnNavigationItemSelectedListener {

    private DrawerLayout drawer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. إعداد التولبار البنفسجي
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // 2. إعداد القائمة الجانبية (Drawer)
        drawer = findViewById(R.id.drawer_layout);
        NavigationView navigationView = findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);

        // 3. تجميل القائمة (أيقونات بيضاء وخط Bold)
        navigationView.setItemIconTintList(ColorStateList.valueOf(Color.WHITE));
        navigationView.setItemTextColor(ColorStateList.valueOf(Color.WHITE));

        // 4. إعداد زر المنيو (الـ 3 شرط) وربطه بالتولبار
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.menu_drawer_open, R.string.menu_drawer_close);
        drawer.addDrawerListener(toggle);
        toggle.syncState();

        getSupportFragmentManager().addOnBackStackChangedListener(this);

        // فتح شاشة الأجهزة (Devices) تلقائياً عند تشغيل التطبيق أول مرة
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction().add(R.id.fragment, new DevicesFragment(), "devices").commit();
        } else {
            onBackStackChanged();
        }
    }

    @Override
    public void onBackStackChanged() {
        // لتغيير شكل الزرار من منيو لسهم رجوع لو دخلنا في شاشات فرعية
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(getSupportFragmentManager().getBackStackEntryCount() > 0);
        }
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        // تنقلات القائمة الجانبية
        if (id == R.id.nav_terminal) {
            getSupportFragmentManager().beginTransaction().replace(R.id.fragment, new TerminalFragment(), "terminal").addToBackStack(null).commit();
        } else if (id == R.id.nav_devices) {
            getSupportFragmentManager().beginTransaction().replace(R.id.fragment, new DevicesFragment(), "devices").addToBackStack(null).commit();
        } else if (id == R.id.nav_settings) {
            // كود فتح الإعدادات الأصلي
            startActivity(new Intent(this, CustomSettingsActivity.class));
        } else if (id == R.id.nav_about) {
            getSupportFragmentManager().beginTransaction().replace(R.id.fragment, new AboutFragment(), "about").addToBackStack(null).commit();
        }

        drawer.closeDrawer(GravityCompat.START);
        return true;
    }

    @Override
    public void onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        // كود المخ التقني الأصلي لاكتشاف الـ USB
        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction())) {
            TerminalFragment terminal = (TerminalFragment) getSupportFragmentManager().findFragmentByTag("terminal");
            if (terminal != null)
                terminal.status("USB device detected");
        }
        super.onNewIntent(intent);
    }
}