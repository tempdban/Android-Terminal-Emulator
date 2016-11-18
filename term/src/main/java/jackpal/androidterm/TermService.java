/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jackpal.androidterm;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.os.Binder;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.text.TextUtils;
import android.util.Log;
import android.app.Notification;
import android.app.PendingIntent;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.Process;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import jackpal.androidterm.emulatorview.TermSession;

import jackpal.androidterm.compat.AndroidCompat;
import jackpal.androidterm.compat.ServiceForegroundCompat;
import jackpal.androidterm.libtermexec.v1.*;
import jackpal.androidterm.util.SessionList;
import jackpal.androidterm.util.TermSettings;

import java.io.File;
import java.util.UUID;

public class TermService extends Service implements TermSession.FinishCallback
{
    /* Parallels the value of START_STICKY on API Level >= 5 */
    private static final int COMPAT_START_STICKY = 1;

    private static final int RUNNING_NOTIFICATION = 1;
    private ServiceForegroundCompat compat;

    private SessionList mTermSessions;

    public class TSBinder extends Binder {
        TermService getService() {
            Log.i("TermService", "Activity binding to service");
            return TermService.this;
        }
    }
    private final IBinder mTSBinder = new TSBinder();

    @Override
    public void onStart(Intent intent, int flags) {
    }

    /* This should be @Override if building with API Level >=5 */
    public int onStartCommand(Intent intent, int flags, int startId) {
        return COMPAT_START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (TermExec.SERVICE_ACTION_V1.equals(intent.getAction())) {
            Log.i("TermService", "Outside process called onBind()");

            return new RBinder();
        } else {
            Log.i("TermService", "Activity called onBind()");

            return mTSBinder;
        }
    }

    @Override
    @SuppressLint("NewApi")
    public void onCreate() {
        // should really belong to the Application class, but we don't use one...
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        SharedPreferences.Editor editor = prefs.edit();
        String defValue = getDir("HOME", MODE_PRIVATE).getAbsolutePath();
        String homePath = prefs.getString("home_path", defValue);
        editor.putString("home_path", homePath);
        if (BuildConfig.FLAVOR.equals("vim")) {
            editor.putBoolean("functionbar_vim_paste", true);
            editor.putBoolean("functionbar_colon", true);
        }
        editor.commit();

        compat = new ServiceForegroundCompat(this);
        mTermSessions = new SessionList();

        int priority = Notification.PRIORITY_DEFAULT;
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
        if (pref.getBoolean("statusbar_icon", true) == false) priority = Notification.PRIORITY_MIN;
        CharSequence contentText = getText(R.string.application_terminal);
        if (BuildConfig.FLAVOR.equals("vim")) {
            contentText = getText(R.string.application_termvim);
        }
        Notification notification = new NotificationCompat.Builder(getApplicationContext())
            .setContentTitle(contentText)
            .setContentText(getText(R.string.service_notify_text))
            .setSmallIcon(R.drawable.ic_stat_service_notification_icon)
            .setPriority(priority)
            .build();
        notification.flags |= Notification.FLAG_ONGOING_EVENT;
        Intent notifyIntent = new Intent(this, Term.class);
        notifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notifyIntent, 0);
        notification.contentIntent = pendingIntent;
        compat.startForeground(RUNNING_NOTIFICATION, notification);

        install();

        Log.d(TermDebug.LOG_TAG, "TermService started");
        return;
    }

    @SuppressLint("NewApi")
    private boolean install() {
        String path = this.getFilesDir().toString();
        long time = getInstallStatus(path+"/install", "res/raw/install");
        if (time > 0) {
            int id = getResources().getIdentifier("bin", "raw", getPackageName());
            installZip(path, getInputStream(id));
            if (AndroidCompat.SDK < 16) {
                File file = new File(String.format("%s/lib/libvim_no_pie.so", this.getApplicationInfo().dataDir.toString()));
                id = getResources().getIdentifier("bin_no_pie", "raw", getPackageName());
                if (file.exists()) installZip(path, getInputStream(id));
            }
            File extfilesdir = (AndroidCompat.SDK >= 8) ? this.getExternalFilesDir(null) : null;
            String sdcard = extfilesdir != null ? extfilesdir.toString() : path;
            id = getResources().getIdentifier("terminfo", "raw", getPackageName());
            installZip(sdcard, getInputStream(id));
            id = getResources().getIdentifier("runtime", "raw", getPackageName());
            installZip(sdcard, getInputStream(id));
            id = getResources().getIdentifier("extra", "raw", getPackageName());
            installZip(sdcard, getInputStream(id));
            id = getResources().getIdentifier("install", "raw", getPackageName());
            copyScript(id, "install", time);
            exeCmd(String.format("sh %s/install", path));
            return true;
        }
        if (time == 0) return true;
        return false;
    }

    @SuppressLint("NewApi")
    public String getInitialCommand(String cmd, boolean bFirst) {
        String replace = bFirst ? "" : "#";
        cmd = cmd.replaceAll("(^|\n)-+", "$1"+ replace);
        String appbase = this.getApplicationInfo().dataDir;
        String appfiles = this.getFilesDir().toString();
        File extfilesdir = (AndroidCompat.SDK >= 8) ? this.getExternalFilesDir(null) : null;
        String appextfiles = extfilesdir != null ? extfilesdir.toString() : appfiles;
        if (new File(appextfiles+"/vim").exists()) {
            cmd = "cat %APPEXTFILES%/vim > %APPFILES%/bin/vim\ncat %APPEXTFILES%/xxd > %APPFILES%/bin/xxd\nchmod 755 %APPFILES%/bin/*\nrm %APPEXTFILES%/vim\nrm %APPEXTFILES%/xxd\n" + cmd;
        }
        cmd = cmd.replaceAll("%APPBASE%", appbase);
        cmd = cmd.replaceAll("%APPFILES%", appfiles);
        cmd = cmd.replaceAll("%APPEXTFILES%", appextfiles);
        return cmd;
    }

    private static String fileToString(File file) throws IOException {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = br.read()) != -1) {
              sb.append((char) c);
            }
            return sb.toString();
        } finally {
            br.close();
        }
    }

    private int copyScript(int id, String fname) {
        return copyScript(id, fname, 0);
    }

    @SuppressLint("NewApi")
    private int copyScript(int id, String fname, long time) {
        if (id == 0) return -1;
        BufferedReader br = null;
        String appfiles = "";
        String appextfiles = "";
        try {
            try {
                InputStream is = getResources().openRawResource(id);
                br = new BufferedReader(new InputStreamReader(is));
                PrintWriter writer = new PrintWriter(this.openFileOutput(fname, MODE_PRIVATE));
                String str;
                String appbase = this.getApplicationInfo().dataDir;
                appfiles = this.getFilesDir().toString();
                File extfilesdir = (AndroidCompat.SDK >= 8) ? this.getExternalFilesDir(null) : null;
                appextfiles = extfilesdir != null ? extfilesdir.toString() : appfiles;
                while ((str = br.readLine()) != null) {
                    str = str.replace("%APPBASE%", appbase);
                    str = str.replace("%APPFILES%", appfiles);
                    str = str.replace("%APPEXTFILES%", appextfiles);
                    writer.print(str+"\n");
                }
                writer.close();
            } catch (IOException e) {
                return 1;
            } finally {
                if (br != null) br.close();
            }
        } catch (IOException e) {
            return 1;
        }
        if (time > 0) {
            File file = new File(appfiles+"/"+fname);
            file.setLastModified(time);
        }
        return 0;
    }

    private void exeCmd(String cmd) {
        try {
            _exeCmd(cmd);
        } catch (IOException e) {
        } catch (InterruptedException e) {
        }
    }

    private int _exeCmd(String cmd) throws IOException, InterruptedException {
        Process p = Runtime.getRuntime().exec(cmd);
        int ret = p.waitFor();
        return ret;
    }

    private long getInstallStatus(String scriptFile, String zipFile) {
        if (!BuildConfig.VERSION_NAME.equals(getDevString(this, "versionName", ""))) {
            setDevString(this, "versionName", BuildConfig.VERSION_NAME);
            return 1;
        }
        return 0;
    }

    public String setDevString(Context context, String key, String value) {
        SharedPreferences pref = context.getSharedPreferences("dev", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = pref.edit();
        editor.putString(key, value);
        editor.apply();
        return value;
    }

    public String getDevString(Context context, String key, String defValue) {
        SharedPreferences pref = context.getSharedPreferences("dev", Context.MODE_PRIVATE);
        return pref.getString(key, defValue);
     }

    private InputStream getInputStream(int id) {
        InputStream is = null;
        try {
            is = getResources().openRawResource(id);
        } catch(Exception e) {
        }
        return is;
    }

    @SuppressLint("NewApi")
    public void installZip(String path, InputStream is) {
        if (is == null) return;
        File outDir = new File(path);
        outDir.mkdirs();
        ZipInputStream zin = new ZipInputStream(new BufferedInputStream(is));
        ZipEntry ze = null;
        int size;
        byte[] buffer = new byte[8192];

        try {
            while ((ze = zin.getNextEntry()) != null) {
                if (ze.isDirectory()) {
                    File file = new File(path+"/"+ze.getName());
                    if (!file.isDirectory()) file.mkdirs();
                } else {
                    File file = new File(path+"/"+ze.getName());
                    File parentFile = file.getParentFile();
                    parentFile.mkdirs();

                    FileOutputStream fout = new FileOutputStream(file);
                    BufferedOutputStream bufferOut = new BufferedOutputStream(fout, buffer.length);
                    while ((size = zin.read(buffer, 0, buffer.length)) != -1) {
                        bufferOut.write(buffer, 0, size);
                    }
                    bufferOut.flush();
                    bufferOut.close();
                    if (ze.getName().startsWith("bin/")) {
                        if (AndroidCompat.SDK >= 9) file.setExecutable(true, false);
                    }
                }
            }

            byte[] buf = new byte[2048];
            while (is.available() > 0) {
                is.read(buf);
            }
            buf = null;
            zin.close();
        } catch (Exception e) {
        }
    }

    @Override
    public void onDestroy() {
        compat.stopForeground(true);
        for (TermSession session : mTermSessions) {
            /* Don't automatically remove from list of sessions -- we clear the
             * list below anyway and we could trigger
             * ConcurrentModificationException if we do */
            session.setFinishCallback(null);
            session.finish();
        }
        mTermSessions.clear();
        return;
    }

    public SessionList getSessions() {
        return mTermSessions;
    }

    public void onSessionFinish(TermSession session) {
        mTermSessions.remove(session);
    }

    private final class RBinder extends ITerminal.Stub {
        @Override
        public IntentSender startSession(final ParcelFileDescriptor pseudoTerminalMultiplexerFd,
                                         final ResultReceiver callback) {
            final String sessionHandle = UUID.randomUUID().toString();

            // distinct Intent Uri and PendingIntent requestCode must be sufficient to avoid collisions
            final Intent switchIntent = new Intent(RemoteInterface.PRIVACT_OPEN_NEW_WINDOW)
                    .setData(Uri.parse(sessionHandle))
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(RemoteInterface.PRIVEXTRA_TARGET_WINDOW, sessionHandle);

            final PendingIntent result = PendingIntent.getActivity(getApplicationContext(), sessionHandle.hashCode(),
                    switchIntent, 0);

            final PackageManager pm = getPackageManager();
            final String[] pkgs = pm.getPackagesForUid(getCallingUid());
            if (pkgs == null || pkgs.length == 0)
                return null;

            for (String packageName:pkgs) {
                try {
                    final PackageInfo pkgInfo = pm.getPackageInfo(packageName, 0);

                    final ApplicationInfo appInfo = pkgInfo.applicationInfo;
                    if (appInfo == null)
                        continue;

                    final CharSequence label = pm.getApplicationLabel(appInfo);

                    if (!TextUtils.isEmpty(label)) {
                        final String niceName = label.toString();

                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                GenericTermSession session = null;
                                try {
                                    final TermSettings settings = new TermSettings(getResources(),
                                            PreferenceManager.getDefaultSharedPreferences(getApplicationContext()));

                                    session = new BoundSession(pseudoTerminalMultiplexerFd, settings, niceName);

                                    mTermSessions.add(session);

                                    session.setHandle(sessionHandle);
                                    session.setFinishCallback(new RBinderCleanupCallback(result, callback));
                                    session.setTitle("");

                                    session.initializeEmulator(80, 24);
                                } catch (Exception whatWentWrong) {
                                    Log.e("TermService", "Failed to bootstrap AIDL session: "
                                            + whatWentWrong.getMessage());

                                    if (session != null)
                                        session.finish();
                                }
                            }
                        });

                        return result.getIntentSender();
                    }
                } catch (PackageManager.NameNotFoundException ignore) {}
            }

            return null;
        }
    }

    private final class RBinderCleanupCallback implements TermSession.FinishCallback {
        private final PendingIntent result;
        private final ResultReceiver callback;

        public RBinderCleanupCallback(PendingIntent result, ResultReceiver callback) {
            this.result = result;
            this.callback = callback;
        }

        @Override
        public void onSessionFinish(TermSession session) {
            result.cancel();

            callback.send(0, new Bundle());

            mTermSessions.remove(session);
        }
    }
}
