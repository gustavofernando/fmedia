package com.github.stsaz.fmedia;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

public class MainActivity extends AppCompatActivity {
	private final String TAG = "UI";
	Core core;
	TrackCtl track;
	int time_total;

	// Explorer:
	String root_path; // upmost filesystem path
	String cur_path; // current path
	String[] fns; // file names
	boolean updir; // "UP" directory link is shown
	int ndirs; // number of directories shown
	int cur_view; // Explorer/Playlist view switch (0:Playlist)
	ArrayList<String> pl; // temporary array for recursive directory contents

	TextView lbl_name;
	ImageButton bplay;
	TextView lbl_pos;
	ListView list;
	SeekBar progs;
	ToggleButton bexplorer;
	ToggleButton bplist;
	Switch brandom;

	enum Cmd {
		PlayPause,
		Next,
		Prev,
		Explorer,
		Playlist,
		Random,
	}

	final int PERMREQ_READ_EXT_STORAGE = 1;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		if (0 != init_mods())
			return;
		init_system();
		init_ui();
		core.dbglog(TAG, "init");

		/* Prevent from going upper than sdcard because
		 it may be impossible to come back (due to file permissions) */
		root_path = Environment.getExternalStorageDirectory().getPath();
		cur_path = root_path;
		bplist.setChecked(true);
	}

	@Override
	public void onStart() {
		if (core != null)
			core.dbglog(TAG, "onStart()");
		super.onStart();
	}

	@Override
	public void onStop() {
		if (core != null) {
			core.dbglog(TAG, "onStop()");
			core.close();
		}
		track.close();
		super.onStop();
	}

	/**
	 * Request system permissions
	 */
	void init_system() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
			String perm = Manifest.permission.READ_EXTERNAL_STORAGE;
			if (ActivityCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
				core.dbglog(TAG, "ActivityCompat.requestPermissions");
				ActivityCompat.requestPermissions(this, new String[]{perm}, PERMREQ_READ_EXT_STORAGE);
			}
		}
	}

	/**
	 * Called by OS with the result of requestPermissions().
	 */
	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		if (grantResults.length != 0)
			core.dbglog(TAG, "onRequestPermissionsResult: %d: %d", requestCode, grantResults[0]);
		/*switch (requestCode) {
			case PERMREQ_READ_EXT_STORAGE:
				if (grantResults.length != 0
						&& grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				}
		}*/
	}

	/**
	 * Initialize core and modules
	 */
	int init_mods() {
		core = new Core();
		if (0 != core.init(this))
			return -1;
		track = new TrackCtl(core, this);
		track.notifier(new Filter() {
			@Override
			public void init() {
				if (track.is_random())
					brandom.setChecked(true);
				show_plist();
			}

			@Override
			public int open(String name, int time_total) {
				return new_track(name, time_total);
			}

			@Override
			public void close(boolean stopped) {
				close_track(stopped);
			}

			@Override
			public int process(int playtime) {
				return update_track(playtime);
			}
		});
		return 0;
	}

	/**
	 * Set UI objects and register event handlers
	 */
	void init_ui() {
		bplay = findViewById(R.id.bplay);
		bplay.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				cmd(Cmd.PlayPause);
			}
		});
		findViewById(R.id.bnext).setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				cmd(Cmd.Next);
			}
		});
		findViewById(R.id.bprev).setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				cmd(Cmd.Prev);
			}
		});
		bexplorer = findViewById(R.id.bexplorer);
		bexplorer.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				cmd(Cmd.Explorer);
			}
		});
		bplist = findViewById(R.id.bplaylist);
		bplist.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				cmd(Cmd.Playlist);
			}
		});

		brandom = findViewById(R.id.brandom);
		brandom.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				cmd(Cmd.Random);
			}
		});

		lbl_name = findViewById(R.id.lname);
		lbl_pos = findViewById(R.id.lpos);

		progs = findViewById(R.id.seekbar);
		progs.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			int val; // last value

			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				if (fromUser)
					val = progress;
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
				val = -1;
			}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
				if (val != -1)
					seek(val);
			}
		});

		list = findViewById(R.id.list);
		list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				list_click(position);
			}
		});
		list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
			@Override
			public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
				return list_longclick(position);
			}
		});
	}

	/**
	 * Show status message to the user.
	 */
	void msg_show(String fmt, Object... args) {
		Toast.makeText(this, String.format(fmt, args), Toast.LENGTH_SHORT).show();
	}

	/**
	 * UI event on listview click
	 * If the view is Playlist: start playing the track at this position.
	 * If the view is Explorer:
	 * if the entry is a directory: show its contents;
	 * or create a playlist with all the files in the current directory
	 * and start playing the selected track
	 */
	void list_click(int pos) {
		if (cur_view == 0) {
			play(pos);
			return;
		}

		if (pos < ndirs) {
			list_show(fns[pos]);
			return;
		}

		String[] pl = new String[fns.length - ndirs];
		int n = 0;
		for (int i = ndirs; i != fns.length; i++) {
			pl[n++] = fns[i];
		}
		track.setqueue(pl, TrackCtl.SETQUEUE_SET);
		core.dbglog(TAG, "added %d items", n);
		msg_show("Set %d playlist items", n);
		play(pos - ndirs);
	}

	/**
	 * Recurively add directory contents to this.pl
	 */
	void add_files_recursive(String dir) {
		File fdir = new File(dir);
		if (!fdir.isDirectory()) {
			if (core.supported(dir))
				pl.add(dir);
			return;
		}
		File[] files = fdir.listFiles();
		if (files == null)
			return;

		// sort file names (directories first)
		class FileCmp implements Comparator<File> {
			@Override
			public int compare(File f1, File f2) {
				if (f1.isDirectory() == f2.isDirectory())
					return f1.getName().compareToIgnoreCase(f2.getName());
				if (f1.isDirectory())
					return -1;
				return 1;
			}
		}
		Arrays.sort(files, new FileCmp());

		for (File f : files) {
			if (!f.isDirectory())
				if (core.supported(f.getName()))
					pl.add(f.getPath());
		}

		for (File f : files) {
			if (f.isDirectory())
				add_files_recursive(f.getPath());
		}
	}

	/**
	 * UI event on listview long click.
	 * Add files to the playlist.  Recursively add directory contents.
	 */
	boolean list_longclick(int pos) {
		if (cur_view == 0)
			return false; // no action for a playlist

		if (pos == 0 && updir)
			return false; // no action for a long click on "<UP>"

		pl = new ArrayList<>();
		add_files_recursive(fns[pos]);
		track.setqueue(pl.toArray(new String[0]), TrackCtl.SETQUEUE_ADD);
		core.dbglog(TAG, "added %d items", pl.size());
		msg_show("Added %d items to playlist", pl.size());
		return true;
	}

	/**
	 * UI event on button click
	 */
	void cmd(Cmd c) {
		core.dbglog(TAG, "cmd: %s", c.name());
		switch (c) {
			case PlayPause:
				if (track.state() == Track.State.PLAYING) {
					track.pause();
					bplay.setImageResource(R.drawable.ic_play);
				} else {
					track.unpause();
					bplay.setImageResource(R.drawable.ic_pause);
				}
				break;

			case Next:
				track.next();
				break;

			case Prev:
				track.prev();
				break;

			case Explorer:
				cur_view = -1;
				list_show(this.cur_path);
				bexplorer.setChecked(true);
				bplist.setChecked(false);
				break;

			case Playlist:
				cur_view = 0;
				bexplorer.setChecked(false);
				bplist.setChecked(true);
				show_plist();
				break;

			case Random:
				track.random(brandom.isChecked());
				break;
		}
	}

	/**
	 * Show the playlist items
	 */
	void show_plist() {
		String[] l = track.queue();
		ArrayList<String> names = new ArrayList<>();
		int i = 1;
		try {
			for (String s : l) {
				names.add(String.format("%d. %s", i++, new File(s).getName()));
			}
		} catch (Exception e) {
			core.errlog(TAG, "%s", e);
			return;
		}
		ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.list_row, names.toArray(new String[0]));
		list.setAdapter(adapter);
	}

	/**
	 * Read directory contents and update listview
	 */
	void list_show(String path) {
		core.dbglog(TAG, "list_show: %s", path);
		ArrayList<String> fnames = new ArrayList<>();
		ArrayList<String> names = new ArrayList<>();
		boolean updir = false;
		int ndirs = 0;
		try {
			File fdir = new File(path);
			File[] files = fdir.listFiles();

			if (!path.equalsIgnoreCase(root_path)) {
				String parent = fdir.getParent();
				if (parent != null) {
					fnames.add(parent);
					names.add("<UP>");
					updir = true;
					ndirs++;
				}
			}

			if (files != null) {
				// sort file names (directories first)
				class FileCmp implements Comparator<File> {
					@Override
					public int compare(File f1, File f2) {
						if (f1.isDirectory() == f2.isDirectory())
							return f1.getName().compareToIgnoreCase(f2.getName());
						if (f1.isDirectory())
							return -1;
						return 1;
					}
				}
				Arrays.sort(files, new FileCmp());

				for (File f : files) {
					String s;
					if (f.isDirectory()) {
						s = "<DIR> ";
						s += f.getName();
						names.add(s);
						fnames.add(f.getPath());
						ndirs++;
						continue;
					}

					if (!core.supported(f.getName()))
						continue;
					s = f.getName();
					names.add(s);
					fnames.add(f.getPath());
				}
			}
		} catch (Exception e) {
			core.errlog(TAG, "list_show: %s", e);
			return;
		}

		fns = fnames.toArray(new String[0]);
		cur_path = path;
		ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.list_row, names.toArray(new String[0]));
		list.setAdapter(adapter);
		this.updir = updir;
		this.ndirs = ndirs;
		core.dbglog(TAG, "added %d files", fns.length - 1);
	}

	/**
	 * Start playing a new track
	 */
	void play(int pos) {
		track.play(pos);
	}

	/**
	 * UI event from seek bar
	 */
	void seek(int pos) {
		track.seek(pos);
	}

	/**
	 * Called by Track when a new track is initialized
	 */
	int new_track(String name, int time_total) {
		lbl_name.setText(name);
		progs.setProgress(0);
		if (time_total < 0) {
			time_total = -time_total;
			this.time_total = time_total;
			bplay.setImageResource(R.drawable.ic_play);
		} else {
			this.time_total = time_total;
			bplay.setImageResource(R.drawable.ic_pause);
		}
		return 0;
	}

	/**
	 * Called by Track after a track is finished
	 */
	void close_track(boolean stopped) {
		lbl_name.setText("");
		lbl_pos.setText("");
		if (stopped) {
			progs.setProgress(0);
			bplay.setImageResource(R.drawable.ic_play);
		}
	}

	/**
	 * Called by Track during playback
	 */
	int update_track(int playtime) {
		if (playtime < 0) {
			playtime = -playtime;
			bplay.setImageResource(R.drawable.ic_play);
		}
		int pos = playtime / 1000;
		int dur = time_total / 1000;
		int progress = 0;
		if (dur != 0)
			progress = pos * 100 / dur;
		progs.setProgress(progress);
		String s = String.format("%d:%02d / %d:%02d"
				, pos / 60, pos % 60, dur / 60, dur % 60);
		lbl_pos.setText(s);
		return 0;
	}
}
