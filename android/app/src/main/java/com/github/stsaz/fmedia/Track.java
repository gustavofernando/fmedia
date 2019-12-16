package com.github.stsaz.fmedia;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

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

/**
 * Chain: SysJobs -> Queue -> Svc
 */
class Track {
	private final String TAG = "Track";
	private Core core;
	private ArrayList<Filter> filters;

	private MediaPlayer mp;
	private Timer tmr;
	private String curname;
	private State state;
	private Handler mloop;

	enum State {
		NONE,
		PLAYING,
		PAUSED,
	}

	Track(Core core) {
		this.core = core;
		filters = new ArrayList<>();
		state = State.NONE;

		mp = new MediaPlayer();
		mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
			public void onPrepared(MediaPlayer mp) {
				on_start();
			}
		});
		mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
			public void onCompletion(MediaPlayer mp) {
				on_complete();
			}
		});

		mloop = new Handler(Looper.getMainLooper());

		core.dbglog(TAG, "init");
	}

	void filter_add(Filter f) {
		filters.add(f);
	}

	State state() {
		return state;
	}

	/**
	 * Start playing
	 */
	void start(String url) {
		core.dbglog(TAG, "play: %s", url);
		if (state != State.NONE)
			return;

		try {
			mp.setDataSource(url);
		} catch (Exception e) {
			core.errlog(TAG, "mp.setDataSource: %s", e);
			return;
		}

		curname = new File(url).getName();
		mp.prepareAsync();
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
			mp.stop();
		} catch (Exception ignored) {
		}
		mp.reset();
		state = State.NONE;
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
		if (state != State.PLAYING)
			return;
		mp.pause();
		state = State.PAUSED;
	}

	void unpause() {
		core.dbglog(TAG, "unpause");
		if (state != State.PAUSED)
			return;
		mp.start();
		state = State.PLAYING;
	}

	void seek(int percent) {
		core.dbglog(TAG, "seek: %d", percent);
		if (state != State.PLAYING && state != State.PAUSED)
			return;
		int ms = percent * mp.getDuration() / 100;
		mp.seekTo(ms);
	}

	/**
	 * Called by MediaPlayer when it's ready to start
	 */
	private void on_start() {
		core.dbglog(TAG, "prepared");

		int dur = mp.getDuration();
		for (Filter f : filters) {
			int r = f.open(curname, dur);
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

		mp.start();
		state = State.PLAYING;
	}

	/**
	 * Called by MediaPlayer when it's finished playing
	 */
	private void on_complete() {
		core.dbglog(TAG, "completed");
		if (!(state == State.PLAYING || state == State.PAUSED))
			return;
		reset();
		for (Filter f : filters) {
			f.close(false);
		}
	}

	private void onTimer() {
		mloop.post(new Runnable() {
			public void run() {
				update();
			}
		});
	}

	/**
	 * Notify filters on the track's progress
	 */
	private void update() {
		if (state != State.PLAYING)
			return;
		int pos = mp.getCurrentPosition();
		for (Filter f : filters) {
			f.process(pos);
		}
	}
}
