package io.github.vladislavekimtcov.broadsheet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * A story's video, full screen. MediaPlayer (HLS or MP4 straight from the publisher's CDN) drawn
 * into a TextureView: on MediaTek TVs a SurfaceView gets a video plane that is never unmuted, so
 * it stays black.
 *
 * <p>Keys: OK or Play/Pause pauses and resumes, Left/Right 10 s, Rewind/Fast-forward 30 s,
 * Up/Down show the controls, Back closes. The controls hide after a few seconds of playing.
 *
 * <p>Touch: full screen without the system bars; a tap pauses or plays and shows the controls,
 * a tap on the line seeks, ← or Back closes. A TextureView here too (safe on phones).
 */
final class Player extends FrameLayout implements TextureView.SurfaceTextureListener {  // feature: video.inline
    interface Listener {
        void onPlayerClosed();
    }

    private static final long CONTROLS_MS = 3500;

    private final Listener listener;
    private final AudioManager audio;
    private final FrameLayout stage;
    private final LinearLayout controls;
    private final TextView title, time, hint, status;
    private final Bar bar;
    private View back;
    private TextureView texture;
    private Surface surface;
    private MediaPlayer player;
    private String url;
    private boolean prepared;
    private int videoW, videoH;
    private final AudioManager.OnAudioFocusChangeListener focus = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS && player != null && prepared && player.isPlaying()) {
            player.pause();
            showControls();
        }
    };
    private final Runnable hide = this::hideControls;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateTime();
            if (controls.getVisibility() == VISIBLE && player != null) postDelayed(this, 500);
        }
    };

    Player(Context c, Listener listener) {
        super(c);
        this.listener = listener;
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        setBackgroundColor(0xFF000000);
        setVisibility(GONE);
        stage = new FrameLayout(c);
        addView(stage, new LayoutParams(-1, -1));

        status = Ui.text(c, 18, 0xFFE4DED3, Ui.sans);
        status.setGravity(Gravity.CENTER);
        status.setLineSpacing(0, 1.3f);
        addView(status, new LayoutParams(-1, -2, Gravity.CENTER));

        controls = new LinearLayout(c);
        controls.setOrientation(LinearLayout.VERTICAL);
        // A deep shade from well above the title down, so the words read over any picture.
        controls.setPadding(Ui.dp(48), Ui.dp(190), Ui.dp(48), Ui.dp(30));
        controls.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xF7000000, 0xF2000000, 0xE6000000, 0xB3000000, 0x00000000}));
        title = Ui.text(c, 20, 0xFFF4EFE6, Ui.head);
        title.setMaxLines(2);
        controls.addView(title);
        bar = new Bar(c);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, Ui.dp(4));
        bl.topMargin = Ui.dp(16);
        controls.addView(bar, bl);
        LinearLayout row = new LinearLayout(c);
        time = Ui.text(c, 14, 0xFFE4DED3, Ui.sansMedium);
        row.addView(time, new LinearLayout.LayoutParams(0, -2, 1));
        hint = Ui.text(c, 14, 0xFFB1AA9E, Ui.sans);
        hint.setText(Ui.touch ? "Tap to pause   ·   Back to close" : "OK pause   ·   ◀ ▶ 10 s   ·   Back close");
        row.addView(hint);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = Ui.dp(10);
        controls.addView(row, rl);
        addView(controls, new LayoutParams(-1, -2, Gravity.BOTTOM));
        if (Ui.touch) {
            controls.setPadding(Ui.dp(24), Ui.dp(140), Ui.dp(24), Ui.dp(28));
            back = new View(c) {
                private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

                @Override
                protected void onDraw(Canvas canvas) {
                    p.setColor(0x99000000);
                    canvas.drawRect(0, 0, getWidth(), getHeight(), p);
                    p.setColor(0xFFF4EFE6);
                    Icons.draw(canvas, Icons.BACK, (getWidth() - Ui.px(24)) / 2, (getHeight() - Ui.px(24)) / 2, Ui.px(24), p);
                }
            };
            back.setOnClickListener(v -> close());
            LayoutParams bl2 = new LayoutParams(Ui.dp(52), Ui.dp(52), Gravity.TOP | Gravity.START);
            bl2.leftMargin = bl2.topMargin = Ui.dp(16);
            addView(back, bl2);
        }
    }

    /** Touch: a tap pauses or plays (and shows the controls); on the line, it seeks there. */
    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        if (!Ui.touch) return super.onTouchEvent(e);
        if (e.getActionMasked() != android.view.MotionEvent.ACTION_UP) return true;
        if (player == null || !prepared) {
            if (player == null) close();  // it failed: a tap goes back to the story
            return true;
        }
        int[] at = new int[2];
        bar.getLocationOnScreen(at);
        int[] me = new int[2];
        getLocationOnScreen(me);
        float y = e.getY() + me[1];
        if (controls.getVisibility() == VISIBLE && Math.abs(y - (at[1] + bar.getHeight() / 2f)) < Ui.dp(22)
                && player.getDuration() > 0) {
            float f = Math.max(0, Math.min(1, (e.getX() + me[0] - at[0]) / Math.max(1f, bar.getWidth())));
            player.seekTo(Math.round(player.getDuration() * f));
        } else if (player.isPlaying()) {
            player.pause();
        } else {
            player.start();
        }
        showControls();
        updateTime();
        return true;
    }

    /** Closes it as Back does. */
    void closeNow() {
        close();
    }

    boolean playing() {
        return getVisibility() == VISIBLE;
    }

    void play(String streamUrl, String storyTitle) {
        stop();
        url = streamUrl;
        setVisibility(VISIBLE);
        bringToFront();
        setKeepScreenOn(true);
        if (Ui.touch) {
            setSystemUiVisibility(SYSTEM_UI_FLAG_FULLSCREEN | SYSTEM_UI_FLAG_HIDE_NAVIGATION | SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | SYSTEM_UI_FLAG_LAYOUT_STABLE | SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        title.setText(storyTitle != null ? storyTitle : "");
        status.setText("Loading video…");
        status.setVisibility(VISIBLE);
        showControls();
        texture = new TextureView(getContext());
        texture.setSurfaceTextureListener(this);
        stage.addView(texture, new LayoutParams(-1, -1, Gravity.CENTER));
    }

    /** Stops and lets go of the player and its surface; hides. */
    void stop() {
        removeCallbacks(hide);
        removeCallbacks(tick);
        if (player != null) {
            player.release();
            player = null;
            audio.abandonAudioFocus(focus);
        }
        prepared = false;
        videoW = videoH = 0;
        if (surface != null) {
            surface.release();
            surface = null;
        }
        stage.removeAllViews();
        texture = null;
        setKeepScreenOn(false);
        if (Ui.touch) setSystemUiVisibility(0);
        setVisibility(GONE);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = new Surface(st);
        final MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(url);
            mp.setSurface(surface);
        } catch (java.io.IOException | RuntimeException e) {
            fail("This video's address doesn't work on the " + Ui.device + ".");
            return;
        }
        mp.setOnPreparedListener(m -> {
            if (m != player) return;
            prepared = true;
            audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            status.setVisibility(GONE);
            m.start();
            showControls();
        });
        mp.setOnVideoSizeChangedListener((m, vw, vh) -> {
            videoW = vw;
            videoH = vh;
            fit();
        });
        mp.setOnInfoListener((m, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                status.setText("Loading…");
                status.setVisibility(VISIBLE);
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                status.setVisibility(GONE);
            }
            return false;
        });
        mp.setOnErrorListener((m, what, extra) -> {
            fail("This video won't play on the " + Ui.device + " (" + what + ", " + extra + ").");
            return true;
        });
        mp.setOnCompletionListener(m -> close());
        mp.prepareAsync();
    }

    private void fail(String text) {
        if (player != null) {
            player.release();
            player = null;
        }
        prepared = false;
        status.setText(text + (Ui.touch ? "\n\nTap to go back to the story" : "\n\nBack: return to the story"));
        status.setVisibility(VISIBLE);
        controls.setVisibility(GONE);
    }

    private void close() {
        stop();
        listener.onPlayerClosed();
    }

    /** The picture as large as fits, in its own shape. */
    private void fit() {
        if (texture == null || videoW <= 0 || videoH <= 0 || getWidth() <= 0) return;
        float scale = Math.min(getWidth() / (float) videoW, getHeight() / (float) videoH);
        LayoutParams lp = new LayoutParams(Math.round(videoW * scale), Math.round(videoH * scale), Gravity.CENTER);
        texture.setLayoutParams(lp);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fit();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    private void hideControls() {
        if (player != null && prepared && player.isPlaying()) {
            controls.setVisibility(GONE);
            if (back != null) back.setVisibility(GONE);
        }
    }

    private void showControls() {
        controls.setVisibility(VISIBLE);
        if (back != null) back.setVisibility(VISIBLE);
        removeCallbacks(tick);
        post(tick);
        removeCallbacks(hide);
        postDelayed(hide, CONTROLS_MS);
    }

    private void updateTime() {
        if (player == null || !prepared) {
            time.setText("");
            bar.set(0);
            return;
        }
        int pos = player.getCurrentPosition(), dur = player.getDuration();
        time.setText(dur > 0 ? clock(pos) + " / " + clock(dur) : clock(pos));
        bar.set(dur > 0 ? pos / (float) dur : 0);
        if (Ui.touch) hint.setText(player.isPlaying() ? "Tap to pause   ·   Back to close" : "Paused   ·   Tap to play");
        else hint.setText(player.isPlaying() ? "OK pause   ·   ◀ ▶ 10 s   ·   Back close" : "Paused   ·   OK play   ·   Back close");
    }

    private static String clock(int ms) {
        int s = ms / 1000;
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void seek(int deltaMs) {
        if (player == null || !prepared) return;
        int dur = player.getDuration();
        int to = Math.max(0, player.getCurrentPosition() + deltaMs);
        if (dur > 0) to = Math.min(dur - 500, to);
        player.seekTo(to);
    }

    boolean onKey(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return false;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (k) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                close();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                if (player != null && prepared) {
                    if (player.isPlaying()) player.pause();
                    else player.start();
                }
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (player != null && prepared) player.start();
                break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (player != null && prepared) player.pause();
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                seek(-10000);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seek(10000);
                break;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seek(-30000);
                break;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seek(30000);
                break;
            default:
                break;
        }
        if (player != null && prepared) showControls();
        return true;
    }

    /** The thin progress line. */
    private static final class Bar extends View {
        private final Paint track = new Paint(), done = new Paint();
        private float value;

        Bar(Context c) {
            super(c);
            track.setColor(0x40FFFFFF);
            done.setColor(0xFFE5484D);
        }

        void set(float v) {
            if (Math.abs(v - value) < 0.0005f) return;
            value = v;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawRect(0, 0, getWidth(), getHeight(), track);
            c.drawRect(0, 0, getWidth() * value, getHeight(), done);
        }
    }
}
