package id.afterlife.updater;

import id.afterlife.updater.model.UpdateInfo;

public interface UpdatesListCallback {
    void exportUpdate(UpdateInfo update);
    void showSnackbar(int stringId, int duration);
}
