package dev.encounter.aurora;

import android.content.Context;
import android.view.SurfaceHolder;

import org.libsdl.app.SDLSurface;

/**
 * Bridges Android's Surface lifecycle to Aurora's frame pump.
 *
 * Aurora renders on a dedicated thread and waits for the surface to be usable before it may
 * touch it, so the native side is told when the surface is (not) valid. Without this the first
 * frames race against SurfaceView creation.
 */
public class AuroraSurface extends SDLSurface {
    private static native void nativeSetSurfaceReady(boolean ready);

    public AuroraSurface(Context context) {
        super(context);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        nativeSetSurfaceReady(false);
        super.surfaceCreated(holder);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        nativeSetSurfaceReady(false);
        super.surfaceDestroyed(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        nativeSetSurfaceReady(false);
        super.surfaceChanged(holder, format, width, height);
        nativeSetSurfaceReady(mIsSurfaceReady);
    }
}
