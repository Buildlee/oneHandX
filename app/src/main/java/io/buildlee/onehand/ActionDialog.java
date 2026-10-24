package io.buildlee.onehand;

import android.content.Context;

import androidx.appcompat.app.AlertDialog;

public class ActionDialog {

    public interface OnPick {
        void onPicked(GestureAction action);
    }

    public static void show(Context ctx, String title, String currentId, OnPick cb) {
        final GestureAction[] items = GestureAction.PICKER_ORDER;
        String[] labels = new String[items.length];
        int checked = 0;
        for (int i = 0; i < items.length; i++) {
            labels[i] = items[i].label;
            if (items[i].id.equals(currentId)) checked = i;
        }

        new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    cb.onPicked(items[which]);
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
