package dev.encounter.foxhollow;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
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

    static {
        // Load native libraries in order of dependency
        // Order matters: zlib -> jpeg -> SDL3 -> Dawn/WebGPU -> nod -> Aurora -> Borealis -> Foxhollow
        System.loadLibrary("z");
        System.loadLibrary("jpeg");
        System.loadLibrary("SDL3");
        System.loadLibrary("dawn");
        System.loadLibrary("nod");
        System.loadLibrary("aurora_core");
        System.loadLibrary("aurora_gx");
        System.loadLibrary("aurora_dvd");
        System.loadLibrary("aurora_card");
        System.loadLibrary("borealis");
        System.loadLibrary("foxhollow");
        
        // Initialize JNI after libraries loaded
        nativeInitJNI();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Hide system bars for immersive gameplay
        hideSystemBars();
        
        // Handle intent for opening game files
        handleIntent(getIntent());
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
        // Game will handle pause via native code
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
            if (grantResults.length > 0 && grantResults[0] == Activity.RESULT_GRANTED) {
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
        Activity activity = SDLActivity.getActivity();
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            // Legacy approach for older Android versions
            getWindow().getDecorView().setSystemUiVisibility(
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
        Activity activity = SDLActivity.getActivity();
        if (activity instanceof FoxhollowActivity) {
            FoxhollowActivity self = (FoxhollowActivity) activity;
            self.showFolderDialog(0); // userdata = 0 for initial game selection
        }
    }
}
