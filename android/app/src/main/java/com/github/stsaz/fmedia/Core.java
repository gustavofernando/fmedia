package com.github.stsaz.fmedia;

import androidx.annotation.NonNull;
import androidx.collection.SimpleArrayMap;

import android.content.ContextWrapper;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

class Core {
	private final String TAG = "Core";
	private SimpleArrayMap<String, Boolean> supp_exts;
	private Queue qu;
	private Track track;
	private SysJobs sysjobs;

	String work_dir;
	ContextWrapper context;

	int init(@NonNull ContextWrapper ctx) {
		context = ctx;
		work_dir = ctx.getCacheDir().getPath();

		String[] exts = {"mp3", "ogg", "m4a", "wav", "flac", "mp4", "mkv", "avi"};
		supp_exts = new SimpleArrayMap<>(exts.length);
		for (String e : exts) {
			supp_exts.put(e, true);
		}
		return 0;
	}

	int init2() {
		track = new Track(this);
		sysjobs = new SysJobs();
		sysjobs.init(this);
		qu = new Queue(this);

		loadconf();

		dbglog(TAG, "init");
		return 0;
	}

	void close() {
		dbglog(TAG, "close()");
		if (qu == null)
			return;
		saveconf();
		qu.close();
		sysjobs.uninit();
	}

	Queue queue() {
		return qu;
	}

	Track track() {
		return track;
	}

	/**
	 * Save configuration
	 */
	private void saveconf() {
		String fn = work_dir + "/fmedia-user.conf";
		if (file_writeall(fn, qu.writeconf().getBytes(), 0))
			dbglog(TAG, "saveconf ok");
	}

	/**
	 * Load configuration
	 */
	private void loadconf() {
		byte[] b = file_readall(work_dir + "/fmedia-user.conf");
		if (b == null)
			return;
		String bs = new String(b);
		qu.readconf(bs);
		dbglog(TAG, "loadconf: %s", bs);
	}

	/**
	 * Return TRUE if file name's extension is supported
	 */
	boolean supported(@NonNull String name) {
		int dot = name.lastIndexOf('.');
		if (dot <= 0)
			return false;
		dot++;
		String ext = name.substring(dot);
		return supp_exts.containsKey(ext);
	}

	void errlog(String mod, String fmt, Object... args) {
		if (BuildConfig.DEBUG)
			Log.e(mod, String.format(fmt, args));
	}

	void dbglog(String mod, String fmt, Object... args) {
		if (BuildConfig.DEBUG)
			Log.d(mod, String.format(fmt, args));
	}

	int str_toint(String s, int def) {
		try {
			return Integer.decode(s);
		} catch (Exception e) {
			return def;
		}
	}

	static final int FILE_WRITE_SAFE = 1;

	boolean file_writeall(String fn, byte[] data, int flags) {
		String name = fn;
		if (flags == FILE_WRITE_SAFE)
			name = fn + ".tmp";
		try {
			File f = new File(name);
			FileOutputStream os = new FileOutputStream(f);
			BufferedOutputStream bo = new BufferedOutputStream(os);
			bo.write(data);
			bo.close();
			os.close();
			if (flags == FILE_WRITE_SAFE) {
				if (!f.renameTo(new File(fn))) {
					errlog(TAG, "renameTo() failed");
					return false;
				}
			}
		} catch (Exception e) {
			errlog(TAG, "file_writeall: %s", e);
			return false;
		}

		return true;
	}

	byte[] file_readall(String fn) {
		byte[] b;
		try {
			File f = new File(fn);
			FileInputStream is = new FileInputStream(f);
			int n = (int) f.length();
			b = new byte[n];
			is.read(b, 0, n);
		} catch (Exception e) {
			errlog(TAG, "file_readall: %s", e);
			return null;
		}
		return b;
	}
}

class Splitter {
	private int off;

	String next(String s, char by, int flags) {
		if (off == s.length())
			return null;
		int pos = s.indexOf(by, off);
		String r;
		if (pos == -1) {
			r = s.substring(off);
			off = s.length();
		} else {
			r = s.substring(off, pos);
			off = pos + 1;
		}
		return r;
	}
}
