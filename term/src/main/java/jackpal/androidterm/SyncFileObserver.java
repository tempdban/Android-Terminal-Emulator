package jackpal.androidterm;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.FileObserver;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

class SyncFileObserver extends RecursiveFileObserver {

    private static Map<String, String> mHashMap = new HashMap<>();
    private File mCacheDir = null;
    private ContentResolver mContentResolver = null;

    SyncFileObserver(String path) {
        this(path, ALL_EVENTS);
    }

    SyncFileObserver(String path, int mask) {
        super(path, mask);
        mCacheDir = new File(path);
    }

    @Override
    public void onEvent(int event, String path) {
        if (!mHashMap.containsKey(path)) return;
        switch (event) {
            case FileObserver.MODIFY:
                super.stopWatching();
                flushCache(Uri.parse(mHashMap.get(path)), new File(path), mContentResolver);
                super.startWatching();
                break;
            default:
                break;
        }
    }

    void setConTentResolver(ContentResolver cr) {
        mContentResolver = cr;
    }

    String getObserverDir() {
        return mCacheDir.toString();
    }

    Map<String, String> getHashMap() {
        return mHashMap;
    }

    boolean putHashMap(String path, String uri) {
        if (mHashMap.containsKey(path)) return false;
        mHashMap.put(path, uri);
        return true;
    }

    boolean clearCache() {
        if (mCacheDir == null) return false;
        if (mCacheDir.isDirectory()) delete(mCacheDir);
        mHashMap.clear();
        mHashMap = new HashMap<>();
        return false;
    }

    private static void delete(File f) {
        if(!f.exists()) {
            return;
        }
        if (f.isFile()) {
            f.delete();
        } else if (f.isDirectory()) {
            File[] files = f.listFiles();
            for (File file : files) {
                delete(file);
            }
            f.delete();
        }
    }

    boolean putUriAndLoad(Uri uri, String path) {
        putHashMap(path, uri.toString());
        super.stopWatching();
        makeCache(uri, new File(path));
        super.startWatching();
        return true;
    }

    void makeCache(Uri uri, File dst) {
        makeCache(uri, dst, mContentResolver);
    }

    static void makeCache(Uri uri, File dst, ContentResolver contentResolver) {
        if (dst == null || uri == null || contentResolver == null) return;

        dst.mkdirs();
        if (dst.isDirectory()) delete(dst);
        try {
            InputStream is = contentResolver.openInputStream(uri);
            BufferedInputStream reader = new BufferedInputStream(is);

            OutputStream os = new FileOutputStream(dst);
            BufferedOutputStream writer = new BufferedOutputStream(os);
            byte buf[] = new byte[1024];
            int len;
            while ((len = reader.read(buf)) != -1) {
                writer.write(buf, 0, len);
            }
            writer.flush();
            writer.close();
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void flushCache(Uri uri, File file, ContentResolver contentResolver) {
        if (contentResolver == null) return;

        try {
            OutputStream os = contentResolver.openOutputStream(uri);
            if (os == null) return;

            BufferedOutputStream writer = new BufferedOutputStream(os);

            InputStream is = new FileInputStream(file);
            BufferedInputStream reader = new BufferedInputStream(is);

            byte buf[] = new byte[256];
            int len;
            while ((len = reader.read(buf)) != -1) {
                writer.write(buf, 0, len);
            }
            writer.flush();
            writer.close();
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}


