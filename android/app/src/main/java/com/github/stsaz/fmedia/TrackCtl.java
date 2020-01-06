package com.github.stsaz.fmedia;

import android.app.Activity;
import android.content.ComponentName;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import java.util.List;

/**
 * Bridge between UI and audio service.
 */
class TrackCtl {
	private final String TAG = "TrackCtl";
	private Core core;
	private Activity activity;
	private Filter notifier;

	private MediaBrowserCompat browser;
	private MediaControllerCompat ctl;

	TrackCtl(Core cor, Activity activity) {
		core = cor;
		this.activity = activity;
		browser = new MediaBrowserCompat(core.context, new ComponentName(core.context, Svc.class),
				new MediaBrowserCompat.ConnectionCallback() {
					@Override
					public void onConnected() {
						core.dbglog(TAG, "MediaBrowserCompat.onConnected");
						on_connected();
					}
				},
				null
		);
	}

	void close() {
		core.dbglog(TAG, "browser.disconnect");
		browser.disconnect();
	}

	void notifier(Filter f) {
		notifier = f;
		browser.connect();
	}

	/**
	 * Called by MediaBrowser when it's connected to the audio service
	 */
	private void on_connected() {
		try {
			ctl = new MediaControllerCompat(activity, browser.getSessionToken());
		} catch (Exception e) {
			core.errlog(TAG, "%s", e);
			return;
		}
		ctl.registerCallback(new MediaControllerCompat.Callback() {
			@Override
			public void onSessionReady() {
				core.dbglog(TAG, "MediaControllerCompat.onSessionReady");
				open();
			}

			@Override
			public void onSessionDestroyed() {
				core.dbglog(TAG, "MediaControllerCompat.onSessionDestroyed");
				notifier.close(true);
			}

			@Override
			public void onPlaybackStateChanged(PlaybackStateCompat state) {
				// core.dbglog(TAG, "MediaControllerCompat.onPlaybackStateChanged: %d", state.getState());
				on_update(state);
			}

			@Override
			public void onMetadataChanged(MediaMetadataCompat metadata) {
				core.dbglog(TAG, "MediaControllerCompat.onMetadataChanged");
			}
		});
		MediaControllerCompat.setMediaController(activity, ctl);

		notifier.init();
	}

	private void on_update(PlaybackStateCompat state) {
		switch (state.getState()) {
			case PlaybackStateCompat.STATE_BUFFERING:
				open();
				break;

			case PlaybackStateCompat.STATE_PLAYING:
				notifier.process((int) state.getPosition());
				break;

			case PlaybackStateCompat.STATE_PAUSED:
				notifier.process(-(int) state.getPosition());
				break;

			case PlaybackStateCompat.STATE_STOPPED:
			case PlaybackStateCompat.STATE_SKIPPING_TO_NEXT:
				notifier.close(true);
				break;

			default:
				break;
		}
	}

	private void open() {
		switch (ctl.getPlaybackState().getState()) {
			case PlaybackStateCompat.STATE_BUFFERING:
			case PlaybackStateCompat.STATE_PLAYING:
			case PlaybackStateCompat.STATE_PAUSED:
				break;
			default:
				return;
		}
		String name = ctl.getMetadata().getString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI);
		int dur = (int) ctl.getMetadata().getLong(MediaMetadataCompat.METADATA_KEY_DURATION);
		notifier.open(name, dur);
		int pos = (int) ctl.getPlaybackState().getPosition();
		if (ctl.getPlaybackState().getState() == PlaybackStateCompat.STATE_PAUSED)
			pos = -pos;
		notifier.process(pos);
	}

	Track.State state() {
		int st = ctl.getPlaybackState().getState();
		switch (st) {
			case PlaybackStateCompat.STATE_PLAYING:
				return Track.State.PLAYING;
			case PlaybackStateCompat.STATE_PAUSED:
				return Track.State.PAUSED;
			default:
				return Track.State.NONE;
		}
	}

	void play(int pos) {
		ctl.getTransportControls().skipToQueueItem(pos);
	}

	void pause() {
		ctl.getTransportControls().pause();
	}

	void unpause() {
		ctl.getTransportControls().play();
	}

	/*void stop() {
		core.dbglog(TAG, "stop");
		ctl.getTransportControls().stop();
	}*/

	void next() {
		ctl.getTransportControls().skipToNext();
	}

	void prev() {
		ctl.getTransportControls().skipToPrevious();
	}

	void seek(int pos) {
		ctl.getTransportControls().seekTo(pos);
	}
}
