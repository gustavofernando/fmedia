package com.github.stsaz.fmedia;

import android.content.Context;
import android.widget.Toast;

class GUI {
	private Core core;
	Context cur_activity;
	boolean filter_show = true;
	String cur_path = ""; // current path

	GUI(Core core) {
		this.core = core;
	}

	/*
	curpath /path
	filter_show true|false
	*/
	String writeconf() {
		StringBuilder s = new StringBuilder();
		s.append(String.format("curpath %s\n", cur_path));
		s.append(String.format("filter_show %s\n", core.str_frombool(filter_show)));
		return s.toString();
	}

	int readconf(String k, String v) {
		if (k.equals("curpath")) {
			cur_path = v;
			return 0;
		} else if (k.equals("filter_show")) {
			filter_show = core.str_tobool(v);
			return 0;
		}
		return 1;
	}

	void on_error(String fmt, Object... args) {
		if (cur_activity == null)
			return;
		msg_show(cur_activity, fmt, args);
	}

	/**
	 * Show status message to the user.
	 */
	void msg_show(Context ctx, String fmt, Object... args) {
		Toast.makeText(ctx, String.format(fmt, args), Toast.LENGTH_SHORT).show();
	}
}
