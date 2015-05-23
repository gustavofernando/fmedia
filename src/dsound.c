/** Direct Sound input/output.
Copyright (c) 2015 Simon Zolin */

#include <fmedia.h>

#include <FF/audio/dsound.h>
#include <FF/array.h>
#include <FFOS/mem.h>


static const fmed_core *core;
static byte stopping;
static byte refcount;

typedef struct dsnd_out {
	ffdsnd_buf snd;
	fmed_handler handler;
	void *trk;
	unsigned async :1
		, silence :1;
} dsnd_out;

typedef struct dsnd_in {
	ffdsnd_capt snd;
} dsnd_in;

enum {
	conf_buflen = 1000
	, conf_buflen_capt = 1000
};


//FMEDIA MODULE
static const fmed_filter* dsnd_iface(const char *name);
static int dsnd_sig(uint signo);
static void dsnd_destroy(void);
static const fmed_mod fmed_dsnd_mod = {
	&dsnd_iface, &dsnd_sig, &dsnd_destroy
};

static int dsnd_listdev(void);

//OUTPUT
static void* dsnd_open(fmed_filt *d);
static int dsnd_write(void *ctx, fmed_filt *d);
static void dsnd_close(void *ctx);
static const fmed_filter fmed_dsnd_out = {
	&dsnd_open, &dsnd_write, &dsnd_close
};

static void dsnd_onplay(void *udata);

//INPUT
static void* dsnd_in_open(fmed_filt *d);
static int dsnd_in_read(void *ctx, fmed_filt *d);
static void dsnd_in_close(void *ctx);
static const fmed_filter fmed_dsnd_in = {
	&dsnd_in_open, &dsnd_in_read, &dsnd_in_close
};


FF_EXP const fmed_mod* fmed_getmod(const fmed_core *_core)
{
	ffmem_init();
	core = _core;
	if (refcount++ == 0 && 0 != ffdsnd_init())
		return NULL;
	return &fmed_dsnd_mod;
}


static const fmed_filter* dsnd_iface(const char *name)
{
	if (!ffsz_cmp(name, "out"))
		return &fmed_dsnd_out;
	else if (!ffsz_cmp(name, "in"))
		return &fmed_dsnd_in;
	return NULL;
}

static int dsnd_sig(uint signo)
{
	switch (signo) {
	case FMED_STOP:
		stopping = 1;
		break;

	case FMED_LISTDEV:
		return dsnd_listdev();
	}
	return 0;
}

static void dsnd_destroy(void)
{
	if (--refcount == 0)
		ffdsnd_uninit();
}

static int dsnd_listdev(void)
{
	struct ffdsnd_devenum *d, *dhead;
	int i, r;
	ffstr3 buf = {0};

	if (0 != (r = ffdsnd_devenum(&dhead, FFDSND_DEV_RENDER)))
		goto fail;
	ffstr_catfmt(&buf, "Playback:\n");
	for (d = dhead, i = 0;  d != NULL; d = d->next, i++) {
		ffstr_catfmt(&buf, "device #%u: %s\n", i, d->name);
	}
	ffdsnd_devenumfree(dhead);

	if (0 != (r = ffdsnd_devenum(&dhead, FFDSND_DEV_CAPTURE)))
		goto fail;
	ffstr_catfmt(&buf, "\nCapture:\n");
	for (d = dhead, i = 0;  d != NULL; d = d->next, i++) {
		ffstr_catfmt(&buf, "device #%u: %s\n", i, d->name);
	}
	ffdsnd_devenumfree(dhead);

	fffile_write(ffstdout, buf.ptr, buf.len);
	ffarr_free(&buf);
	return 1;

fail:
	errlog(core, NULL, "dsound", "ffdsnd_devenum(): (%xu) %s", r, ffdsnd_errstr(r));
	ffarr_free(&buf);
	return -1;
}

/** Get device by index. */
static int dsnd_devbyidx(struct ffdsnd_devenum **dhead, struct ffdsnd_devenum **dev, uint idev, uint flags)
{
	struct ffdsnd_devenum *devs, *d;

	if (0 != ffdsnd_devenum(&devs, flags))
		return 1;

	for (d = devs;  d != NULL;  d = d->next, idev--) {
		if (idev == 0) {
			*dev = d;
			*dhead = devs;
			return 0;
		}
	}

	ffdsnd_devenumfree(devs);
	return 1;
}

static void dsnd_onplay(void *udata)
{
	dsnd_out *ds = udata;
	if (!ds->async)
		return; //the function may be called when we aren't expecting it
	ds->async = 0;
	ds->handler(ds->trk);
}

static void* dsnd_open(fmed_filt *d)
{
	dsnd_out *ds;
	ffpcm fmt;
	int e, idx;
	struct ffdsnd_devenum *dhead, *dev;

	ds = ffmem_tcalloc1(dsnd_out);
	if (ds == NULL)
		return NULL;
	ds->handler = d->handler;
	ds->trk = d->trk;

	idx = (int)d->track->getval(d->trk, "playdev_name");
	if (0 != dsnd_devbyidx(&dhead, &dev, idx, FFDSND_DEV_RENDER)) {
		errlog(core, d->trk, "dsound", "no audio device by index #%u", idx);
		goto done;
	}

	ds->snd.handler = &dsnd_onplay;
	ds->snd.udata = ds;
	fmt.format = (int)d->track->getval(d->trk, "pcm_format");
	fmt.channels = (int)d->track->getval(d->trk, "pcm_channels");
	fmt.sample_rate = (int)d->track->getval(d->trk, "pcm_sample_rate");
	e = ffdsnd_open(&ds->snd, dev->id, &fmt, conf_buflen);

	ffdsnd_devenumfree(dhead);

	if (e != 0) {
		errlog(core, d->trk, "dsound", "ffdsnd_open(): (%xu) %s", e, ffdsnd_errstr(e));
		goto done;
	}

	dbglog(core, d->trk, "dsound", "opened buffer %u bytes", ds->snd.bufsize);
	return ds;

done:
	dsnd_close(ds);
	return NULL;
}

static void dsnd_close(void *ctx)
{
	dsnd_out *ds = ctx;

	ffdsnd_close(&ds->snd);
	ffmem_free(ds);
}

static int dsnd_write(void *ctx, fmed_filt *d)
{
	dsnd_out *ds = ctx;

	if (stopping) {
		ffdsnd_pause(&ds->snd);
		return FMED_RDONE;
	}

	if (d->datalen != 0) {
		int r = ffdsnd_write(&ds->snd, d->data, d->datalen);
		if (r < 0) {
			errlog(core, d->trk, "dsound", "ffdsnd_write(): (%xu) %s", r, ffdsnd_errstr(r));
			return r;
		}

		dbglog(core, d->trk, "dsound", "written %u bytes (%u%% filled)"
			, r, ffdsnd_filled(&ds->snd) * 100 / ds->snd.bufsize);

		d->data += r;
		d->datalen -= r;
	}

	if ((d->flags & FMED_FLAST) && d->datalen == 0) {

		if (!ds->silence) {
			ds->silence = 1;
			if (0 > ffdsnd_silence(&ds->snd))
				return FMED_RERR;
		}

		if (0 == ffdsnd_filled(&ds->snd))
			return FMED_RDONE;

		ffdsnd_start(&ds->snd);
		ds->async = 1;
		return FMED_RASYNC; //wait until all filled bytes are played
	}

	if (d->datalen != 0) {
		ffdsnd_start(&ds->snd);
		ds->async = 1;
		return FMED_RASYNC; //sound buffer is full
	}

	return FMED_ROK; //more data may be written into the sound buffer
}


static void* dsnd_in_open(fmed_filt *d)
{
	dsnd_in *ds;
	ffpcm fmt;
	int r, idx;
	struct ffdsnd_devenum *dhead, *dev;

	ds = ffmem_tcalloc1(dsnd_in);
	if (ds == NULL)
		return NULL;

	idx = (int)d->track->getval(d->trk, "capture_device");
	if (0 != dsnd_devbyidx(&dhead, &dev, idx, FFDSND_DEV_CAPTURE)) {
		errlog(core, d->trk, "dsound", "no audio device by index #%u", idx);
		goto fail;
	}

	ds->snd.handler = d->handler;
	ds->snd.udata = d->trk;
	fmt.format = (int)d->track->getval(d->trk, "pcm_format");
	fmt.channels = (int)d->track->getval(d->trk, "pcm_channels");
	fmt.sample_rate = (int)d->track->getval(d->trk, "pcm_sample_rate");
	r = ffdsnd_capt_open(&ds->snd, dev->id, &fmt, conf_buflen_capt);

	ffdsnd_devenumfree(dhead);

	if (r != 0) {
		errlog(core, d->trk, "dsound", "ffdsnd_capt_open(): %d", r);
		goto fail;
	}

	r = ffdsnd_capt_start(&ds->snd);
	if (r != 0) {
		errlog(core, d->trk, "dsound", "ffdsnd_capt_start(): %d", r);
		goto fail;
	}

	dbglog(core, d->trk, "dsound", "opened capture buffer %u bytes", ds->snd.bufsize);
	return ds;

fail:
	dsnd_in_close(ds);
	return NULL;
}

static void dsnd_in_close(void *ctx)
{
	dsnd_in *ds = ctx;

	ffdsnd_capt_close(&ds->snd);
	ffmem_free(ds);
}

static int dsnd_in_read(void *ctx, fmed_filt *d)
{
	dsnd_in *ds = ctx;
	int r = ffdsnd_capt_read(&ds->snd, (void**)&d->out, &d->outlen);
	if (r < 0) {
		errlog(core, d->trk, "dsound", "ffdsnd_capt_read(): (%xu) %s", r, ffdsnd_errstr(r));
		return FMED_RERR;
	}
	if (r == 0)
		return FMED_RASYNC;

	dbglog(core, d->trk, "dsound", "read %L bytes", d->outlen);
	if (stopping) {
		ffdsnd_capt_stop(&ds->snd);
		return FMED_RDONE;
	}
	return FMED_ROK;
}
