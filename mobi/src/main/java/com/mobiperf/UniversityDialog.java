package com.mobiperf;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.DialogFragment;
import android.support.v7.app.AlertDialog;
import android.widget.ArrayAdapter;

public class UniversityDialog extends DialogFragment {

    public static UniversityDialog newInstance() {
        return new UniversityDialog();
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        AlertDialog.Builder alertBuilder = new AlertDialog.Builder(getActivity());
        alertBuilder.setTitle("Select your University");
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(getActivity(), R.layout.list_item);
        adapter.add("Cape Peninsula University of Technology");
        adapter.add("University of Cape Town");
        adapter.add("University of Western Cape");

        alertBuilder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                ((SpeedometerApp) getActivity()).userCancelled();
            }
        });

        alertBuilder.setAdapter(adapter, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String name = adapter.getItem(which);
                ((SpeedometerApp) getActivity()).universitySelected(name);
            }
        });

        return alertBuilder.create();
    }
}
