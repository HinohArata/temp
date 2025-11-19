/*
 * Copyright (C) 2017-2023 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package id.afterlife.updater;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ProgressDialog;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import androidx.core.content.ContextCompat;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.icu.text.DateFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemProperties;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import id.afterlife.updater.controller.UpdaterController;
import id.afterlife.updater.controller.UpdaterService;
import id.afterlife.updater.download.DownloadClient;
import id.afterlife.updater.misc.BuildInfoUtils;
import id.afterlife.updater.misc.Constants;
import id.afterlife.updater.misc.StringGenerator;
import id.afterlife.updater.misc.Utils;
import id.afterlife.updater.model.Update;
import id.afterlife.updater.model.UpdateInfo;

public class UpdatesListFragment extends Fragment implements UpdateImporter.Callbacks, UpdatesListCallback {

    private static final String TAG = "UpdatesListFragment";
    private UpdaterService mUpdaterService;
    private BroadcastReceiver mBroadcastReceiver;

    private UpdatesListAdapter mAdapter;

    private View mRefreshIconView;
    private RotateAnimation mRefreshAnimation;

    private boolean mIsTV;

    private UpdateInfo mToBeExported = null;
    
    private final ActivityResultLauncher<Intent> mExportUpdate = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    Intent intent = result.getData();
                    if (intent != null) {
                        Uri uri = intent.getData();
                        exportUpdate(uri);
                    }
                }
            });

    private UpdateImporter mUpdateImporter;
    @SuppressWarnings("deprecation")
    private ProgressDialog importDialog;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            UpdaterService.LocalBinder binder = (UpdaterService.LocalBinder) service;
            mUpdaterService = binder.getService();
            mAdapter.setUpdaterController(mUpdaterService.getUpdaterController());
            getUpdatesList();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mAdapter.setUpdaterController(null);
            mUpdaterService = null;
            mAdapter.notifyDataSetChanged();
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        mUpdateImporter = new UpdateImporter(requireActivity(), this);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_updates, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        UiModeManager uiModeManager = requireContext().getSystemService(UiModeManager.class);
        mIsTV = uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;

        RecyclerView recyclerView = view.findViewById(R.id.recycler_view);
        mAdapter = new UpdatesListAdapter(requireContext(), this);
        recyclerView.setAdapter(mAdapter);
        RecyclerView.LayoutManager layoutManager = new LinearLayoutManager(requireContext());
        recyclerView.setLayoutManager(layoutManager);
        RecyclerView.ItemAnimator animator = recyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }

        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (UpdaterController.ACTION_UPDATE_STATUS.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    handleDownloadStatusChange(downloadId);
                    mAdapter.notifyItemChanged(downloadId);
                } else if (UpdaterController.ACTION_DOWNLOAD_PROGRESS.equals(intent.getAction()) ||
                        UpdaterController.ACTION_INSTALL_PROGRESS.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    mAdapter.notifyItemChanged(downloadId);
                } else if (UpdaterController.ACTION_UPDATE_REMOVED.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    mAdapter.removeItem(downloadId);
                }
            }
        };

        if (!mIsTV) {
            Toolbar toolbar = view.findViewById(R.id.toolbar);
            
            AppCompatActivity activity = (AppCompatActivity) requireActivity();
            activity.setSupportActionBar(toolbar);
            
            ActionBar actionBar = activity.getSupportActionBar();
            if (actionBar != null) {
                actionBar.setDisplayShowTitleEnabled(false);
                
                final int statusBarHeight;
                TypedValue tv = new TypedValue();
                if (requireActivity().getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
                    statusBarHeight = TypedValue.complexToDimensionPixelSize(
                            tv.data, getResources().getDisplayMetrics());
                } else {
                    statusBarHeight = 0;
                }
                RelativeLayout headerContainer = view.findViewById(R.id.header_container);
                recyclerView.setOnApplyWindowInsetsListener((v, insets) -> {
                    int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
                    CollapsingToolbarLayout.LayoutParams lp =
                            (CollapsingToolbarLayout.LayoutParams)
                                    headerContainer.getLayoutParams();
                    lp.topMargin = top + statusBarHeight;
                    headerContainer.setLayoutParams(lp);
                    return insets;
                });
            }
        }

        TextView headerTitle = view.findViewById(R.id.header_title);
        headerTitle.setText(getString(R.string.header_title_text,
                Utils.getDisplayVersion(BuildInfoUtils.getBuildVersion())));

        updateLastCheckedString(view);

        TextView headerBuildVersion = view.findViewById(R.id.header_build_version);
        headerBuildVersion.setText(
                getString(R.string.header_android_version, Build.VERSION.RELEASE));

        TextView headerBuildDate = view.findViewById(R.id.header_build_date);
        headerBuildDate.setText(StringGenerator.getDateLocalizedUTC(requireContext(),
                DateFormat.LONG, BuildInfoUtils.getBuildDateTimestamp()));

        if (!mIsTV) {
            final CollapsingToolbarLayout collapsingToolbar = view.findViewById(R.id.collapsing_toolbar);
            final AppBarLayout appBar = view.findViewById(R.id.app_bar);
            appBar.addOnOffsetChangedListener(new AppBarLayout.OnOffsetChangedListener() {
                boolean mIsShown = false;

                @Override
                public void onOffsetChanged(AppBarLayout appBarLayout, int verticalOffset) {
                    int scrollRange = appBarLayout.getTotalScrollRange();
                    if (!mIsShown && scrollRange + verticalOffset < 10) {
                        collapsingToolbar.setTitle(getString(R.string.display_name));
                        mIsShown = true;
                    } else if (mIsShown && scrollRange + verticalOffset > 100) {
                        collapsingToolbar.setTitle(null);
                        mIsShown = false;
                    }
                }
            });

            mRefreshAnimation = new RotateAnimation(0, 360, Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f);
            mRefreshAnimation.setInterpolator(new LinearInterpolator());
            mRefreshAnimation.setDuration(1000);

            if (!Utils.hasTouchscreen(requireContext())) {
                appBar.setExpanded(false);
            }
        } else {
            view.findViewById(R.id.refresh).setOnClickListener(v -> downloadUpdatesList(true));
            view.findViewById(R.id.preferences).setOnClickListener(v -> showPreferencesDialog());
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        Intent intent = new Intent(requireContext(), UpdaterService.class);
        requireContext().startService(intent);
        requireContext().bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_STATUS);
        intentFilter.addAction(UpdaterController.ACTION_DOWNLOAD_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_INSTALL_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_REMOVED);
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(mBroadcastReceiver, intentFilter);
    }

    @Override
    public void onPause() {
        if (importDialog != null) {
            importDialog.dismiss();
            importDialog = null;
            mUpdateImporter.stopImport();
        }
        super.onPause();
    }

    @Override
    public void onStop() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(mBroadcastReceiver);
        if (mUpdaterService != null) {
            requireContext().unbindService(mConnection);
        }
        super.onStop();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.menu_toolbar, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_refresh) {
            downloadUpdatesList(true);
            return true;
        } else if (itemId == R.id.menu_preferences) {
            showPreferencesDialog();
            return true;
        } else if (itemId == R.id.menu_show_changelog) {
            Intent openUrl = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(Utils.getChangelogURL(requireContext())));
            startActivity(openUrl);
            return true;
        } else if (itemId == R.id.menu_local_update) {
            mUpdateImporter.openImportPicker();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (!mUpdateImporter.onResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onImportStarted() {
        if (importDialog != null && importDialog.isShowing()) {
            importDialog.dismiss();
        }

        importDialog = ProgressDialog.show(requireContext(), getString(R.string.local_update_import),
                getString(R.string.local_update_import_progress), true, false);
    }

    @Override
    public void onImportCompleted(Update update) {
        if (importDialog != null) {
            importDialog.dismiss();
            importDialog = null;
        }

        if (update == null) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.local_update_import)
                    .setMessage(R.string.local_update_import_failure)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        mAdapter.notifyDataSetChanged();

        final Runnable deleteUpdate = () -> UpdaterController.getInstance(requireContext())
                .deleteUpdate(update.getDownloadId());

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.local_update_import)
                .setMessage(getString(R.string.local_update_import_success, update.getVersion()))
                .setPositiveButton(R.string.local_update_import_install, (dialog, which) -> {
                    mAdapter.addItem(update.getDownloadId());
                    // Update UI
                    getUpdatesList();
                    Utils.triggerUpdate(requireContext(), update.getDownloadId());
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> deleteUpdate.run())
                .setOnCancelListener((dialog) -> deleteUpdate.run())
                .show();
    }

    private void loadUpdatesList(File jsonFile, boolean manualRefresh)
            throws IOException, JSONException {
        Log.d(TAG, "Adding remote updates");
        UpdaterController controller = mUpdaterService.getUpdaterController();
        boolean newUpdates = false;

        List<UpdateInfo> updates = Utils.parseJson(jsonFile, true);
        List<String> updatesOnline = new ArrayList<>();
        for (UpdateInfo update : updates) {
            newUpdates |= controller.addUpdate(update);
            updatesOnline.add(update.getDownloadId());
        }
        controller.setUpdatesAvailableOnline(updatesOnline, true);

        if (manualRefresh) {
            showSnackbar(
                    newUpdates ? R.string.snack_updates_found : R.string.snack_no_updates_found,
                    Snackbar.LENGTH_SHORT);
        }

        List<String> updateIds = new ArrayList<>();
        List<UpdateInfo> sortedUpdates = controller.getUpdates();
        View rootView = getView();
        if (rootView != null) {
            if (sortedUpdates.isEmpty()) {
                rootView.findViewById(R.id.no_new_updates_view).setVisibility(View.VISIBLE);
                rootView.findViewById(R.id.recycler_view).setVisibility(View.GONE);
            } else {
                rootView.findViewById(R.id.no_new_updates_view).setVisibility(View.GONE);
                rootView.findViewById(R.id.recycler_view).setVisibility(View.VISIBLE);
                sortedUpdates.sort((u1, u2) -> Long.compare(u2.getTimestamp(), u1.getTimestamp()));
                for (UpdateInfo update : sortedUpdates) {
                    updateIds.add(update.getDownloadId());
                }
                mAdapter.setData(updateIds);
                mAdapter.notifyDataSetChanged();
            }
        }
    }

    private void getUpdatesList() {
        File jsonFile = Utils.getCachedUpdateList(requireContext());
        if (jsonFile.exists()) {
            try {
                loadUpdatesList(jsonFile, false);
                Log.d(TAG, "Cached list parsed");
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Error while parsing json list", e);
            }
        } else {
            downloadUpdatesList(false);
        }
    }

    private void processNewJson(File json, File jsonNew, boolean manualRefresh) {
        try {
            loadUpdatesList(jsonNew, manualRefresh);
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(requireContext());
            long millis = System.currentTimeMillis();
            preferences.edit().putLong(Constants.PREF_LAST_UPDATE_CHECK, millis).apply();
            
            if (getView() != null) {
                updateLastCheckedString(getView());
            }

            if (json.exists() && Utils.isUpdateCheckEnabled(requireContext()) &&
                    Utils.checkForNewUpdates(json, jsonNew)) {
                UpdatesCheckReceiver.updateRepeatingUpdatesCheck(requireContext());
            }
            // In case we set a one-shot check because of a previous failure
            UpdatesCheckReceiver.cancelUpdatesCheck(requireContext());
            //noinspection ResultOfMethodCallIgnored
            jsonNew.renameTo(json);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Could not read json", e);
            showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
        }
    }

    private void downloadUpdatesList(final boolean manualRefresh) {
        final File jsonFile = Utils.getCachedUpdateList(requireContext());
        final File jsonFileTmp = new File(jsonFile.getAbsolutePath() + UUID.randomUUID());
        String url = Utils.getServerURL(requireContext());
        Log.d(TAG, "Checking " + url);

        DownloadClient.DownloadCallback callback = new DownloadClient.DownloadCallback() {
            @Override
            public void onFailure(final boolean cancelled) {
                Log.e(TAG, "Could not download updates list");
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (!cancelled) {
                            showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
                        }
                        refreshAnimationStop();
                    });
                }
            }

            @Override
            public void onResponse(DownloadClient.Headers headers) {
            }

            @Override
            public void onSuccess() {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Log.d(TAG, "List downloaded");
                        processNewJson(jsonFile, jsonFileTmp, manualRefresh);
                        refreshAnimationStop();
                    });
                }
            }
        };

        final DownloadClient downloadClient;
        try {
            downloadClient = new DownloadClient.Builder()
                    .setUrl(url)
                    .setDestination(jsonFileTmp)
                    .setDownloadCallback(callback)
                    .build();
        } catch (IOException exception) {
            Log.e(TAG, "Could not build download client");
            showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
            return;
        }

        refreshAnimationStart();
        downloadClient.start();
    }

    private void updateLastCheckedString(View view) {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(requireContext());
        long lastCheck = preferences.getLong(Constants.PREF_LAST_UPDATE_CHECK, -1) / 1000;
        String lastCheckString = getString(R.string.header_last_updates_check,
                StringGenerator.getDateLocalized(requireContext(), DateFormat.LONG, lastCheck),
                StringGenerator.getTimeLocalized(requireContext(), lastCheck));
        TextView headerLastCheck = view.findViewById(R.id.header_last_check);
        if (headerLastCheck != null) {
            headerLastCheck.setText(lastCheckString);
        }
    }

    private void handleDownloadStatusChange(String downloadId) {
        if (Update.LOCAL_ID.equals(downloadId)) {
            return;
        }

        UpdateInfo update = mUpdaterService.getUpdaterController().getUpdate(downloadId);
        switch (update.getStatus()) {
            case PAUSED_ERROR:
                showSnackbar(R.string.snack_download_failed, Snackbar.LENGTH_LONG);
                break;
            case VERIFICATION_FAILED:
                showSnackbar(R.string.snack_download_verification_failed, Snackbar.LENGTH_LONG);
                break;
            case VERIFIED:
                showSnackbar(R.string.snack_download_verified, Snackbar.LENGTH_LONG);
                break;
        }
    }

    @Override
    public void exportUpdate(UpdateInfo update) {
        mToBeExported = update;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, update.getName());

        mExportUpdate.launch(intent);
    }

    private void exportUpdate(Uri uri) {
        Intent intent = new Intent(requireContext(), ExportUpdateService.class);
        intent.setAction(ExportUpdateService.ACTION_START_EXPORTING);
        intent.putExtra(ExportUpdateService.EXTRA_SOURCE_FILE, mToBeExported.getFile());
        intent.putExtra(ExportUpdateService.EXTRA_DEST_URI, uri);
        requireContext().startService(intent);
    }

    private int getThemeColor(int attributeColor) {
        TypedValue typedValue = new TypedValue();
        if (getContext() != null && getContext().getTheme().resolveAttribute(attributeColor, typedValue, true)) {
            return typedValue.data;
        }
        return ContextCompat.getColor(requireContext(), R.color.snackbar_background_floating);
    }

    @Override
    public void showSnackbar(int stringId, int duration) {
        if (getView() != null) {
            Snackbar snackbar = Snackbar.make(getView().findViewById(R.id.main_container), stringId, duration);
            View snackbarView = snackbar.getView();
            snackbarView.setBackgroundTintList(null); 

            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.RECTANGLE);
            background.setCornerRadius(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28, getResources().getDisplayMetrics()));

            int backgroundColor = getThemeColor(com.google.android.material.R.attr.colorOnSurface);
            background.setColor(backgroundColor);
            snackbarView.setBackground(background);
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) snackbarView.getLayoutParams();
            int margin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics());
            params.setMargins(margin, margin, margin, margin);
            snackbarView.setLayoutParams(params);

            TextView textView = snackbarView.findViewById(com.google.android.material.R.id.snackbar_text);
            if (textView != null) {
                int textColor = ContextCompat.getColor(requireContext(), R.color.snackbar_text_color);
                textView.setTextColor(textColor);
                textView.setMaxLines(3);
            }

            if (getActivity() != null) {
                View anchorView = getActivity().findViewById(R.id.nav_card_wrapper);
                if (anchorView != null) {
                    snackbar.setAnchorView(anchorView);
                }
            }

            snackbar.show();
        }
    }

    private void refreshAnimationStart() {
        if (getView() == null) return;
        
        if (!mIsTV) {
            if (mRefreshIconView == null) {
                mRefreshIconView = getView().findViewById(R.id.menu_refresh);
            }
            if (mRefreshIconView != null) {
                mRefreshAnimation.setRepeatCount(Animation.INFINITE);
                mRefreshIconView.startAnimation(mRefreshAnimation);
                mRefreshIconView.setEnabled(false);
            }
        } else {
            getView().findViewById(R.id.recycler_view).setVisibility(View.GONE);
            getView().findViewById(R.id.no_new_updates_view).setVisibility(View.GONE);
            getView().findViewById(R.id.refresh_progress).setVisibility(View.VISIBLE);
        }
    }

    private void refreshAnimationStop() {
        if (getView() == null) return;

        if (!mIsTV) {
            if (mRefreshIconView != null) {
                mRefreshAnimation.setRepeatCount(0);
                mRefreshIconView.setEnabled(true);
            }
        } else {
            getView().findViewById(R.id.refresh_progress).setVisibility(View.GONE);
            if (mAdapter.getItemCount() > 0) {
                getView().findViewById(R.id.recycler_view).setVisibility(View.VISIBLE);
            } else {
                getView().findViewById(R.id.no_new_updates_view).setVisibility(View.VISIBLE);
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showPreferencesDialog() {
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.preferences_dialog, null);
        Spinner autoCheckInterval = view.findViewById(R.id.preferences_auto_updates_check_interval);
        SwitchCompat autoDelete = view.findViewById(R.id.preferences_auto_delete_updates);
        SwitchCompat meteredNetworkWarning = view.findViewById(
                R.id.preferences_metered_network_warning);
        SwitchCompat abPerfMode = view.findViewById(R.id.preferences_ab_perf_mode);
        SwitchCompat updateRecovery = view.findViewById(R.id.preferences_update_recovery);

        if (!Utils.isABDevice()) {
            abPerfMode.setVisibility(View.GONE);
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        autoCheckInterval.setSelection(Utils.getUpdateCheckSetting(requireContext()));
        autoDelete.setChecked(prefs.getBoolean(Constants.PREF_AUTO_DELETE_UPDATES, false));
        meteredNetworkWarning.setChecked(prefs.getBoolean(Constants.PREF_METERED_NETWORK_WARNING,
                prefs.getBoolean(Constants.PREF_MOBILE_DATA_WARNING, true)));
        abPerfMode.setChecked(prefs.getBoolean(Constants.PREF_AB_PERF_MODE, false));

        if (getResources().getBoolean(R.bool.config_hideRecoveryUpdate)) {
            updateRecovery.setVisibility(View.GONE);
        } else if (Utils.isRecoveryUpdateExecPresent()) {
            updateRecovery.setChecked(
                    SystemProperties.getBoolean(Constants.UPDATE_RECOVERY_PROPERTY, false));
        } else {
            updateRecovery.setChecked(true);
            updateRecovery.setOnTouchListener(new View.OnTouchListener() {
                private Toast forcedUpdateToast = null;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (forcedUpdateToast != null) {
                        forcedUpdateToast.cancel();
                    }
                    forcedUpdateToast = Toast.makeText(requireContext(),
                            getString(R.string.toast_forced_update_recovery), Toast.LENGTH_SHORT);
                    forcedUpdateToast.show();
                    return true;
                }
            });
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.menu_preferences)
                .setView(view)
                .setOnDismissListener(dialogInterface -> {
                    prefs.edit()
                            .putInt(Constants.PREF_AUTO_UPDATES_CHECK_INTERVAL,
                                    autoCheckInterval.getSelectedItemPosition())
                            .putBoolean(Constants.PREF_AUTO_DELETE_UPDATES, autoDelete.isChecked())
                            .putBoolean(Constants.PREF_METERED_NETWORK_WARNING,
                                    meteredNetworkWarning.isChecked())
                            .putBoolean(Constants.PREF_AB_PERF_MODE, abPerfMode.isChecked())
                            .apply();

                    if (Utils.isUpdateCheckEnabled(requireContext())) {
                        UpdatesCheckReceiver.scheduleRepeatingUpdatesCheck(requireContext());
                    } else {
                        UpdatesCheckReceiver.cancelRepeatingUpdatesCheck(requireContext());
                        UpdatesCheckReceiver.cancelUpdatesCheck(requireContext());
                    }

                    if (Utils.isABDevice()) {
                        boolean enableABPerfMode = abPerfMode.isChecked();
                        mUpdaterService.getUpdaterController().setPerformanceMode(enableABPerfMode);
                    }
                    if (Utils.isRecoveryUpdateExecPresent()) {
                        boolean enableRecoveryUpdate = updateRecovery.isChecked();
                        SystemProperties.set(Constants.UPDATE_RECOVERY_PROPERTY,
                                String.valueOf(enableRecoveryUpdate));
                    }
                })
                .show();
    }
}