package pe.com.map.sunshinewear;

import android.os.Bundle;
import android.support.wearable.activity.WearableActivity;
import android.support.wearable.view.BoxInsetLayout;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import butterknife.BindView;
import butterknife.ButterKnife;

public class MainActivity extends WearableActivity {

    private static final SimpleDateFormat AMBIENT_DATE_FORMAT =
            new SimpleDateFormat("HH:mm", Locale.US);

    private BoxInsetLayout mContainerView;
    private TextView timeView;
    private TextView dateView;
    private ImageView stateImageView;
    private TextView maxTempView;
    private TextView minTempView;
    private View separatorView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setAmbientEnabled();
        timeView = (TextView) findViewById(R.id.time);
        dateView = (TextView) findViewById(R.id.date);
        minTempView = (TextView) findViewById(R.id.minTemp);
        maxTempView = (TextView) findViewById(R.id.maxTemp);
        stateImageView = (ImageView) findViewById(R.id.stateImage);
        mContainerView = (BoxInsetLayout) findViewById(R.id.container);
        separatorView =  findViewById(R.id.separator);

        timeView.setText(AMBIENT_DATE_FORMAT.format(new Date()));
    }

    @Override
    public void onEnterAmbient(Bundle ambientDetails) {
        super.onEnterAmbient(ambientDetails);
        updateDisplay();
    }

    @Override
    public void onUpdateAmbient() {
        super.onUpdateAmbient();
        updateDisplay();
    }

    @Override
    public void onExitAmbient() {
        updateDisplay();
        super.onExitAmbient();
    }

    private void updateDisplay() {
        if (isAmbient()) {
            mContainerView.setBackgroundColor(getResources().getColor(R.color.black));
            minTempView.setVisibility(View.GONE);
            maxTempView.setVisibility(View.GONE);
            stateImageView.setVisibility(View.GONE);
            separatorView.setVisibility(View.GONE);
        } else {
            mContainerView.setBackgroundColor(getResources().getColor(R.color.sky));
            minTempView.setVisibility(View.VISIBLE);
            maxTempView.setVisibility(View.VISIBLE);
            stateImageView.setVisibility(View.VISIBLE);
            separatorView.setVisibility(View.VISIBLE);
        }
    }
}
