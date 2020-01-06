package com.github.stsaz.fmedia;

import android.os.Bundle;
import android.view.View;
import android.widget.Switch;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {
	Core core;
	Switch brandom;
	Switch bshowfilter;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.settings);
		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null)
			actionBar.setDisplayHomeAsUpEnabled(true);

		core = Core.getInstance();

		brandom = findViewById(R.id.brandom);
		brandom.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				core.queue().random(brandom.isChecked());
			}
		});
		if (core.queue().is_random())
			brandom.setChecked(true);

		bshowfilter = findViewById(R.id.bshowfilter);
		bshowfilter.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				core.gui().filter_show = bshowfilter.isChecked();
			}
		});
		if (core.gui().filter_show)
			bshowfilter.setChecked(true);
	}

	@Override
	protected void onDestroy() {
		core.unref();
		super.onDestroy();
	}
}