package com.github.stsaz.fmedia;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.collection.SimpleArrayMap;

import java.io.File;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

abstract class Filter {
	/**
	 * Initialize filter.  Called once.
	 */
	public void init() {
	}

	/**
	 * Open filter track context.  Called for each new track.
	 * name: file name
	 * time_total: track duration (in msec)
	 * Return -1: close the track.
	 */
	public int open(String name, int time_total) {
		return 0;
	}

	/**
	 * Close filter track context.
	 * stopped: FALSE: the track completed
	 */
	public void close(boolean stopped) {
	}

	/**
	 * Update track progress.  Called periodically by timer.
	 * playtime: current progress (in msec)
	 */
	public int process(int playtime) {
		return 0;
	}
}

class TrackHandle {
	MediaPlayer mp;
	Track.State state;
	String curname;
}

/**
 * Chain: SysJobs -> Queue -> Svc
 */
class Track {
	private final String TAG = "Track";
	private Core core;
	private ArrayList<Filter> filters;
	private SimpleArrayMap<String, Boolean> supp_exts;

	private TrackHandle t;
	private Timer tmr;
	private Handler mloop;

	enum State {
		NONE,
		PLAYING,
		PAUSED,
	}

	Track(Core core) {
		this.core = core;
		t = new TrackHandle();
		filters = new ArrayList<>();
		t.state = State.NONE;

		t.mp = new MediaPlayer();
		t.mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
			public void onPrepared(MediaPlayer mp) {
				on_start(t);
			}
		});
		t.mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
			public void onCompletion(MediaPlayer mp) {
				on_complete(t);
			}
		});

		mloop = new Handler(Looper.getMainLooper());

		String[] exts = {"mp3", "ogg", "m4a", "wav", "flac", "mp4", "mkv", "avi"};
		supp_exts = new SimpleArrayMap<>(exts.length);
		for (String e : exts) {
			supp_exts.put(e, true);
		}

		core.dbglog(TAG, "init");
	}

	boolean supported_url(@NonNull String name) {
		if (name.startsWith("http://") || name.startsWith("https://"))
			return true;
		return false;
	}

	/**
	 * Return TRUE if file name's extension is supported
	 */
	boolean supported(@NonNull String name) {
		if (supported_url(name))
			return true;

		int dot = name.lastIndexOf('.');
		if (dot <= 0)
			return false;
		dot++;
		String ext = name.substring(dot);
		return supp_exts.containsKey(ext);
	}

	void filter_add(Filter f) {
		filters.add(f);
	}

	State state() {
		return t.state;
	}

	/**
	 * Start playing
	 */
	void start(String url) {
		core.dbglog(TAG, "play: %s", url);
		if (t.state != State.NONE)
			return;

		try {
			t.mp.setDataSource(url);
		} catch (Exception e) {
			core.errlog(TAG, "mp.setDataSource: %s", e);
			return;
		}

		t.curname = new File(url).getName();
		t.mp.prepareAsync();
	}

	/**
	 * Stop playing, reset
	 */
	private void reset() {
		if (tmr != null) {
			tmr.cancel();
			tmr = null;
		}
		try {
			t.mp.stop();
		} catch (Exception ignored) {
		}
		t.mp.reset();
		t.state = State.NONE;
	}

	/**
	 * Stop playing and notifiy filters
	 */
	void stop() {
		core.dbglog(TAG, "stop");
		reset();
		for (Filter f : filters) {
			f.close(true);
		}
	}

	void pause() {
		core.dbglog(TAG, "pause");
		if (t.state != State.PLAYING)
			return;
		t.mp.pause();
		t.state = State.PAUSED;
	}

	void unpause() {
		core.dbglog(TAG, "unpause");
		if (t.state != State.PAUSED)
			return;
		t.mp.start();
		t.state = State.PLAYING;
	}

	void seek(int percent) {
		core.dbglog(TAG, "seek: %d", percent);
		if (t.state != State.PLAYING && t.state != State.PAUSED)
			return;
		int ms = percent * t.mp.getDuration() / 100;
		t.mp.seekTo(ms);
	}

	/**
	 * Called by MediaPlayer when it's ready to start
	 */
	private void on_start(TrackHandle t) {
		core.dbglog(TAG, "prepared");

		int dur = t.mp.getDuration();
		for (Filter f : filters) {
			int r = f.open(t.curname, dur);
			if (r != 0) {
				core.errlog(TAG, "f.open(): %d", r);
				stop();
				return;
			}
		}

		tmr = new Timer();
		tmr.schedule(new TimerTask() {
			@Override
			public void run() {
				onTimer();
			}
		}, 0, 500);

		t.mp.start();
		t.state = State.PLAYING;
	}

	/**
	 * Called by MediaPlayer when it's finished playing
	 */
	private void on_complete(TrackHandle t) {
		core.dbglog(TAG, "completed");
		if (!(t.state == State.PLAYING || t.state == State.PAUSED))
			return;
		reset();
		for (Filter f : filters) {
			f.close(false);
		}
	}

	private void onTimer() {
		mloop.post(new Runnable() {
			public void run() {
				update(t);
			}
		});
	}

	/**
	 * Notify filters on the track's progress
	 */
	private void update(TrackHandle t) {
		if (t.state != State.PLAYING)
			return;
		int pos = t.mp.getCurrentPosition();
		for (Filter f : filters) {
			f.process(pos);
		}
	}
}
