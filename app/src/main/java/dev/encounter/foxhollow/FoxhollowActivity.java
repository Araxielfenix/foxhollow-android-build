package dev.encounter.foxhollow;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.window.OnBackInvokedDispatcher;

import dev.encounter.aurora.AuroraSurface;
import dev.encounter.borealis.BorealisSurface;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * FoxhollowActivity - Main Android Activity for Foxhollow
 * Extends SDLActivity through BorealisActivity for proper GameCube/Wii emulation support
 */
public class FoxhollowActivity extends SDLActivity {
    private static final String TAG = "FoxhollowActivity";
    private static final float DEFAULT_SURFACE_FRAME_RATE = 60.0f;
    private static final int FOLDER_DIALOG_REQUEST_CODE = 0x4253;
    private static final int MANAGE_STORAGE_REQUEST_CODE = 0x4254;
    private static final String EXTERNAL_STORAGE_AUTHORITY =
            "com.android.externalstorage.documents";

    private long folderDialogUserdata = 0;
    private boolean awaitingManageStoragePermission = false;
    private String pendingGamePath = null;

    // Native methods (implemented in C++ via CMake)
    private static native void nativeFolderDialogResult(long userdata, String path, String error);
    private static native void nativeSetGamePath(String path);
    private static native void nativeInitJNI();

    /**
     * Feeds the on-screen controls into controller slot 0.
     *
     * Called from the UI thread on every touch change; Aurora merges this on top of any real
     * controller, so it does not need to go through SDL's controller layer.
     */
    private static native void nativeSetVirtualPad(int buttonMask, int stickX, int stickY);
    private static native void nativeClearVirtualPad();

    /** Presented frames per second, straight from Aurora's present timestamps. */
    private static native float nativeGetFps();

    /** Live render scale; applies on the next frame without a restart. */
    private static native void nativeSetRenderScale(float scale);

    /** 0 = 4:3, 1 = 16:9, 2 = stretched to the display. */
    private static native void nativeSetDisplayMode(int mode);

    private TouchControlsView touchControls;
    private SettingsMenuView settingsMenu;
    private FpsOverlayView fpsOverlay;
    private TouchControlSettings touchSettings;

    static {
        // Aurora, Borealis, nod, SDL3, libjpeg and zlib are linked statically into
        // libfoxhollow.so, so this is the only native library to load.
        System.loadLibrary("foxhollow");

        nativeInitJNI();
    }

    /**
     * SDL3 usa este metodo para saber que biblioteca nativa principal cargar.
     * La clase base espera "main", pero el port construye "foxhollow".
     */
    @Override
    protected String[] getLibraries() {
        return new String[] { "foxhollow" };
    }
    /**
     * Devuelve la instancia activa de la actividad.
     *
     * SDL3 expone la instancia actual como el campo protegido {@code mSingleton},
     * al que esta clase accede por herencia. Se envuelve en un metodo estatico
     * propio porque el codigo nativo necesita resolver la actividad en tiempo
     * de ejecucion, fuera de cualquier instancia.
     */
    private static Activity currentActivity() {
        return mSingleton;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Hide system bars for immersive gameplay
        hideSystemBars();

        installTouchControls();

        registerBackHandler();

        // Handle intent for opening game files
        handleIntent(getIntent());
    }

    /**
 * Puts the on-screen controls on top of SDL's surface.
 *
 * mLayout is the RelativeLayout SDLActivity builds for its SurfaceView, so adding siblings there
 * keeps the game's own view untouched while still drawing above it. The menu and the FPS counter
 * go on last so they sit above the controls and win the touch, which is what stops a slider drag
 * from also steering the game.
 */
    private void installTouchControls() {
        if (mLayout == null || touchControls != null) {
            return;
        }

        touchSettings = new TouchControlSettings(this);

        touchControls = new TouchControlsView(this, touchSettings);
        touchControls.setOnMenuGestureListener(new TouchControlsView.OnMenuGestureListener() {
            @Override
            public void onMenuGesture() {
                showSettingsMenu();
            }
        });
        mLayout.addView(touchControls, matchParent());

        fpsOverlay = new FpsOverlayView(this);
        fpsOverlay.setVisibility(touchSettings.showFps ? View.VISIBLE : View.GONE);
        mLayout.addView(fpsOverlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        settingsMenu = new SettingsMenuView(this, touchSettings, touchControls,
                new SettingsMenuView.SettingsHost() {
                    @Override
                    public void onSettingsChanged(TouchControlSettings settings) {
                        applySettings(settings);
                    }

                    @Override
                    public void onExitRequested() {
                        hideSettingsMenu();
                        finish();
                    }
                });
        settingsMenu.setVisibility(View.GONE);
        mLayout.addView(settingsMenu, matchParent());

        // The game reads its own scale from the environment at startup, so the saved value has to
        // be pushed in afterwards for it to take effect on this launch.
        nativeSetRenderScale(touchSettings.renderScale);
        nativeSetDisplayMode(touchSettings.displayMode);
    }

    private static ViewGroup.LayoutParams matchParent() {
        return new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void applySettings(TouchControlSettings settings) {
        nativeSetRenderScale(settings.renderScale);
        nativeSetDisplayMode(settings.displayMode);
        if (fpsOverlay != null) {
            fpsOverlay.setVisibility(settings.showFps ? View.VISIBLE : View.GONE);
        }
    }

    private void showSettingsMenu() {
        if (settingsMenu == null) {
            return;
        }
        releaseControls();
        settingsMenu.refresh();
        settingsMenu.setVisibility(View.VISIBLE);
    }

    private void hideSettingsMenu() {
        if (settingsMenu != null) {
            settingsMenu.setVisibility(View.GONE);
        }
    }

    private void releaseControls() {
        if (touchControls != null) {
            touchControls.releaseAll();
        }
        nativeClearVirtualPad();
    }

    /** Used by the FPS overlay; kept static so the view does not hold the activity. */
    static float getFps() {
        return nativeGetFps();
    }

    /**
     * Back opens the settings menu instead of leaving the game.
     *
     * Leaving is only possible from the menu, so a stray press cannot drop the player out of a
     * run; back again closes the menu.
     */
    @Override
    public void onBackPressed() {
        handleBack();
    }

    /**
     * Takes the back key before SDL sees it.
     *
     * SDLActivity forwards KEYCODE_BACK into the game's event loop and swallows the system
     * behaviour, so onBackPressed() never runs on this path. Intercepting it here is what makes
     * the key reach the settings menu; every other key still goes to SDL.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                handleBack();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void handleBack() {
        if (settingsMenu != null && settingsMenu.getVisibility() == View.VISIBLE) {
            hideSettingsMenu();
            return;
        }
        showSettingsMenu();
    }

    /**
     * Registers the same handler on the modern back dispatcher.
     *
     * From targetSdk 33 the system only skips onBackPressed() when the app opts into predictive
     * back through the manifest, so both entry points are wired to one handler to stay correct
     * either way.
     */
    private void registerBackHandler() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }

        OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
        if (dispatcher != null) {
            dispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_OVERLAY, this::handleBack);
        }
    }

    /** Bridges the overlay to native; kept static to avoid leaking the activity. */
    static void setVirtualPad(int buttonMask, int stickX, int stickY) {
        nativeSetVirtualPad(buttonMask, stickX, stickY);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        
        String action = intent.getAction();
        Uri data = intent.getData();
        
        if (Intent.ACTION_VIEW.equals(action) && data != null) {
            // Handle game file/folder opening
            String path = getPathFromUri(data);
            if (path != null) {
                pendingGamePath = path;
                // If native is ready, set immediately
                if (isNativeReady()) {
                    nativeSetGamePath(path);
                }
            }
        } else if (Intent.ACTION_OPEN_DOCUMENT_TREE.equals(action)) {
            // Handle folder selection result
            if (data != null) {
                String path = getPathFromUri(data);
                if (path != null) {
                    nativeFolderDialogResult(folderDialogUserdata, path, null);
                } else {
                    nativeFolderDialogResult(folderDialogUserdata, null, "Invalid path");
                }
            }
        }
    }

    private String getPathFromUri(Uri uri) {
        if (uri == null) return null;
        
        // Handle file:// URIs
        if ("file".equals(uri.getScheme())) {
            return uri.getPath();
        }
        
        // Handle content:// URIs (SAF)
        if ("content".equals(uri.getScheme())) {
            // For documents, we need to use DocumentsContract
            try {
                // Try to get the actual file path
                String[] projection = { android.provider.MediaStore.MediaColumns.DATA };
                Cursor cursor = getContentResolver().query(uri, projection, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int columnIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DATA);
                    String path = cursor.getString(columnIndex);
                    cursor.close();
                    return path;
                }
                if (cursor != null) cursor.close();
            } catch (Exception e) {
                Log.w(TAG, "Could not resolve content URI: " + uri, e);
            }
            
            // Return the URI as string - native code can handle it via AAssetManager
            return uri.toString();
        }
        
        return null;
    }

    private boolean isNativeReady() {
        // Check if native libraries are loaded and initialized
        // This is a simple check; in practice you might want a more robust mechanism
        return true; // Native init happens in static block
    }

    @Override
    protected SDLSurface createSDLSurface(Context context) {
        // Use BorealisSurface which extends AuroraSurface for proper rendering
        return new BorealisSurface(context);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();

        // SDLActivity hands the view back without clearing touches, so anything held when the
        // app went away would still be down on return.
        releaseControls();

        if (awaitingManageStoragePermission) {
            requestManageStoragePermission();
        }

        // Set pending game path if available
        if (pendingGamePath != null) {
            nativeSetGamePath(pendingGamePath);
            pendingGamePath = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // A finger lifted while the activity is not in front is never delivered, which would
        // leave a direction or trigger stuck down.
        releaseControls();
        hideSettingsMenu();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) {
            releaseControls();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Cleanup native resources if needed
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == MANAGE_STORAGE_REQUEST_CODE) {
            awaitingManageStoragePermission = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Manage storage permission granted");
            } else {
                Log.w(TAG, "Manage storage permission denied");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == FOLDER_DIALOG_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri treeUri = data.getData();
                if (treeUri != null) {
                    // Persist URI permission
                    getContentResolver().takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );
                    String path = getPathFromUri(treeUri);
                    if (path != null) {
                        nativeFolderDialogResult(folderDialogUserdata, path, null);
                    } else {
                        nativeFolderDialogResult(folderDialogUserdata, null, "Invalid folder path");
                    }
                }
            } else {
                nativeFolderDialogResult(folderDialogUserdata, null, "User cancelled or error");
            }
        }
    }

    /**
     * Called from native code to show folder picker dialog
     * @param userdata User data to pass back to native callback
     */
    public static void showFolderDialog(long userdata) {
        Activity activity = currentActivity();
        if (activity instanceof FoxhollowActivity) {
            FoxhollowActivity self = (FoxhollowActivity) activity;
            self.folderDialogUserdata = userdata;
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+: Use MANAGE_EXTERNAL_STORAGE
                if (!Environment.isExternalStorageManager()) {
                    self.awaitingManageStoragePermission = true;
                    self.requestManageStoragePermission();
                    return;
                }
            }
            
            // Use Storage Access Framework for folder selection
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try {
                activity.startActivityForResult(intent, FOLDER_DIALOG_REQUEST_CODE);
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "No activity to handle folder picker", e);
                self.nativeFolderDialogResult(userdata, null, "No file picker available");
            }
        }
    }

    private void requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, MANAGE_STORAGE_REQUEST_CODE);
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "Cannot request manage storage permission", e);
                awaitingManageStoragePermission = false;
            }
        }
    }

    private void hideSystemBars() {
        Window window = getWindow();
        if (window == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // getWindow().getInsetsController() lanza NullPointerException si el
            // DecorView todavia no esta adjunto, algo que ocurre al llamar desde
            // onCreate(). Hay que pasar por getDecorView() y tolerar un controlador nulo.
            View decorView = window.getDecorView();
            WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            // Legacy approach for older Android versions
            window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
            );
        }
    }

    /**
     * Called from native code to request game path selection
     */
    public static void requestGamePath() {
        Activity activity = currentActivity();
        if (activity instanceof FoxhollowActivity) {
            FoxhollowActivity self = (FoxhollowActivity) activity;
            self.showFolderDialog(0); // userdata = 0 for initial game selection
        }
    }
}
