package com.github.stsaz.fmedia;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Date;
import java.util.Random;

class Queue {
	private final String TAG = "Queue";
	private Core core;
	private Track track;

	private ArrayList<String> plist;
	private int curpos;
	private boolean modified;
	private boolean random;
	boolean repeat;
	private boolean active;
	private Random rnd;
	private Handler mloop;

	Queue(Core core) {
		repeat = true;
		this.core = core;
		track = core.track();
		track.filter_add(new Filter() {
			@Override
			public int open(String name, int time_total) {
				active = true;
				return 0;
			}

			@Override
			public void close(boolean stopped) {
				active = false;
				if (!stopped) {
					mloop.post(new Runnable() {
						public void run() {
							next();
						}
					});
				}
			}
		});
		plist = new ArrayList<>();

		mloop = new Handler(Looper.getMainLooper());

		load(core.work_dir + "/list1.m3u8");
	}

	void close() {
		if (modified)
			save(core.work_dir + "/list1.m3u8");
	}

	/**
	 * Play track at cursor
	 */
	void playcur() {
		play(curpos);
	}

	/**
	 * Play track at the specified position
	 */
	void play(int index) {
		core.dbglog(TAG, "play: %d", index);
		if (active)
			track.stop();
		if (index < 0 || index >= plist.size())
			return;

		curpos = index;
		track.start(plist.get(index));
	}

	/**
	 * Play next track
	 */
	void next() {
		int i = curpos + 1;
		if (random) {
			i = rnd.nextInt(plist.size());
			if (i < 0)
				i = -i;
			if (i == curpos) {
				i = curpos + 1;
				if (i == plist.size())
					i = 0;
			}
		} else if (repeat) {
			if (i == plist.size())
				i = 0;
		}
		play(i);
	}

	/**
	 * Play previous track
	 */
	void prev() {
		play(curpos - 1);
	}

	/**
	 * Set Random switch
	 */
	void random(boolean val) {
		random = val;
		if (val)
			rnd = new Random(new Date().getTime());
	}

	boolean is_random() {
		return random;
	}

	String[] list() {
		return plist.toArray(new String[0]);
	}

	/**
	 * Clear playlist
	 */
	void clear() {
		core.dbglog(TAG, "clear");
		plist.clear();
		modified = true;
	}

	/**
	 * Add an entry
	 */
	void add(String url) {
		core.dbglog(TAG, "add: %s", url);
		plist.add(url);
		modified = true;
	}

	/**
	 * Save playlist to a file on disk
	 */
	private void save(String fn) {
		StringBuilder sb = new StringBuilder();
		for (String s : plist) {
			sb.append(s);
			sb.append('\n');
		}
		if (core.file_writeall(fn, sb.toString().getBytes(), Core.FILE_WRITE_SAFE))
			core.dbglog(TAG, "saved %d items to %s", plist.size(), fn);
	}

	/**
	 * Load playlist from a file on disk
	 */
	private void load(String fn) {
		byte[] b = core.file_readall(fn);
		if (b == null)
			return;

		String bs = new String(b);
		plist.clear();
		Splitter spl = new Splitter();
		while (true) {
			String s = spl.next(bs, '\n', 0);
			if (s == null)
				break;
			if (s.length() != 0)
				plist.add(s);
		}

		core.dbglog(TAG, "loaded %d items from %s", plist.size(), fn);
	}

	/*
	curpos 0..N
	random 0|1
	*/
	String writeconf() {
		StringBuilder s = new StringBuilder();
		s.append(String.format("curpos %d\n", curpos));

		int val = 0;
		if (random)
			val = 1;
		s.append(String.format("random %d\n", val));

		return s.toString();
	}

	void readconf(String data) {
		Splitter spl = new Splitter();
		while (true) {
			String ln = spl.next(data, '\n', 0);
			if (ln == null)
				break;

			Splitter spl2 = new Splitter();
			String k, v;
			k = spl2.next(ln, ' ', 0);
			v = spl2.next(ln, ' ', 0);
			if (k == null || v == null)
				continue;

			// core.dbglog(TAG, "conf: %s=%s", k, v);
			if (k.equals("curpos")) {
				curpos = core.str_toint(v, 0);

			} else if (k.equals("random")) {
				int val = core.str_toint(v, 0);
				if (val == 1)
					random(true);
			}
		}
	}
}
