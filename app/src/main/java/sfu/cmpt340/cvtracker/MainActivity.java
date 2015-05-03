package sfu.cmpt340.cvtracker;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;


public class MainActivity extends ActionBarActivity {

    // Set known rates
    private final int sample_rate = 256; // 1/3 second
    private final int max_length = 30000;
    private final int file_length = 8192; // 12 seconds of samples
    private final int graph_length = 2048;
    private final double ratio = 300.0000 * 60.00 /(double)file_length;
    Spinner file_spinner;
    String dataFile = "dataset1";
    Button btn;
    Button stop_btn;

    private TextView tvHR;
    private TextView tvRR;
    private TextView choose;

    private GraphView graph;

    private static Context context;

    // Handler class made non-static for access to global display object references
    private Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            // Extract bundle from message
            Bundle b = msg.getData();

            // Extract strings for HR and RR
            int key1 = b.getInt("HRKey");
            int key2 = b.getInt("RRKey");

            // Set text values to display objects
            tvHR.setText(Integer.toString(key1) + " BPM");
            tvRR.setText(Integer.toString(key2) + " BPM");
            tvHR.setTextColor(Color.YELLOW);
            file_spinner.setVisibility(View.INVISIBLE);
            btn.setVisibility(View.INVISIBLE);
            stop_btn.setVisibility(View.VISIBLE);

            choose.setVisibility(View.INVISIBLE);

            // heart rate target at ages from 3 to 5
            if (key1 > 120 || key1 < 75)    {
                tvHR.setTextColor(Color.RED);
            }   else {
                tvHR.setTextColor(Color.BLACK);
            }
            if (key2 > 30 || key2 < 20)    {
                tvRR.setTextColor(Color.RED);
            }   else {
                tvRR.setTextColor(Color.BLACK);
            }

            // Extract values for graph and build data points
            double[] graph_data = b.getDoubleArray("graphKey");
            DataPoint[] graph_vals = new DataPoint[graph_length];
            for (int i = 0; i < graph_length; i++) {
                graph_vals[i] = new DataPoint(i, graph_data[i]);
            }

            // Clear graph and refill with new values
            graph.removeAllSeries();
            LineGraphSeries<DataPoint> series = new LineGraphSeries<DataPoint>(graph_vals);
            graph.addSeries(series);
        }
    };

    private Runnable separateThread = new Runnable() {
        @Override
        public void run() {
            mainLoop();
        }
    };

    private Runnable stopThread = new Runnable() {
        @Override
        public void run() {
            stop_btn.setVisibility(View.INVISIBLE);
            btn.setVisibility(View.VISIBLE);
            file_spinner.setVisibility(View.VISIBLE);
        }
    };


    private void mainLoop() {

        dataFile = file_spinner.getSelectedItem().toString();


        for(int shift = 0; shift < max_length-file_length; shift = shift + sample_rate) {

            // Initialize message bundle
            Message msg = new Message();
            Bundle b = new Bundle();

            // Initialize arrays for file reading
            double[] real = new double[file_length];
            double[] imag = new double[file_length];
            int i = 0;

            // File reading
            BufferedReader reader = null;
            try {
                String file_path = dataFile.concat(".txt");
                reader = new BufferedReader(new InputStreamReader(getAssets().open(file_path)));

                // Skip amount of lines according to shift
                for (int j = 0; j < shift; j++) {
                    reader.readLine();
                }

                String mLine = reader.readLine();

                while (mLine != null && i < file_length) {
                    //process line
                    real[i] = Double.parseDouble(mLine);
                    imag[i] = 0;

                    i++;

                    mLine = reader.readLine();
                }
            } catch (IOException e) {
                //log the exception
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        //log the exception
                    }
                }
            }

            // Manually copy a subset of the read values to be outputted for the graph
            double[] graph_vals = new double[graph_length];
            for (int j = 0; j < graph_length; j++) {
                graph_vals[j] = real[j];
            }

            // Append graph values to bundle
            b.putDoubleArray("graphKey", graph_vals);

            // Fourier transform on PPG values, Radix
            FfT.transformRadix2(real, imag);

            //Log.d("myTag", "real[50] = " + real[50]);
            //Log.d("myTag", "imag[50] = " + imag[50]);

            // Set limit for fourier analysis
            int ft_limit = 512;
            DataPoint[] ft_vals = new DataPoint[ft_limit];
            for (i = 0; i < ft_limit; i++) {
                ft_vals[i] = new DataPoint(i, Math.sqrt(real[i] * real[i] + imag[i] * imag[i]));
            }

            // Code to find peaks / find HR & RR
            // Code should work for whatever length of data the fourier is applied to
            // Adjust the peak radius if we are getting two peaks that are to close together
            // Not sure if the heart and respiratory rates are correct, I applied the formula Jon
            // included in the email - (peak location) * 300 / (file_length)
            // Heart rate should be 60-100 beats per minute (1.0-1.66 Hz) at rest
            // (higher if the person is active/recently active or under stress).
            // I got 3.3, which is about 200 beats per minute, which is way too high, you'd be dead
            // long before you hit 200 bpm.
            // Respiratory rate should be 12-20 breaths per minute (0.2-0.33 Hz) higher if under some
            // sort of stress. I got 0.6 Hz (40 breaths per min) which is high but believable.
            // I think these values are a bit high, but the peak values/locations match the graph that
            // is displayed when you run the simulator.

            String logTag = "output tag";

            int HR = 0;
            int RR = 0;
            int peak_radius = 3;        //The minimum distance we require between peaks
            Double[] ft_mag = new Double[ft_vals.length];

            //Copy magnitudes into an array
            for (i = 0; i < ft_vals.length; i++) {
                ft_mag[i] = ft_vals[i].getY();
            }
            //Eliminate the peak at zero
            ft_mag[0] = 0.0;

            //Convert to  List to use the utilities it provides
            List<Double> find_peaks = Arrays.asList(ft_mag);

            //Get the first peak
            Double peak1 = Collections.max(find_peaks);
            //Log.d(logTag, "First peak value: " + peak1);
            //Log.d(logTag, " At index " + find_peaks.indexOf(peak1));
            int peak1_index = find_peaks.indexOf(peak1);

            //Eliminate the values in the first peak's radius so that we avoid the second peak
            //too close to the first one
            if (peak1_index - peak_radius < 0 && peak1_index + peak_radius > find_peaks.size()) {
                Log.e("Data Error", "Sample data is too small in size for the current peak radius");
            } else if (peak1_index - peak_radius < 0) {
                for (i = 0; i < peak1_index + peak_radius; i++) {
                    find_peaks.set(i, (Double) 0.0);
                }
            } else if (peak1_index + peak_radius > find_peaks.size()) {
                for (i = peak1_index - peak_radius; i < find_peaks.size(); i++) {
                    find_peaks.set(i, (Double) 0.0);
                }
            } else {
                for (i = peak1_index - peak_radius; i < peak1_index + peak_radius; i++) {
                    find_peaks.set(i, (Double) 0.0);
                }
            }

            //Get the second peak
            Double peak2 = Collections.max(find_peaks);
            int peak2_index = find_peaks.indexOf(peak2);
            //Log.d(logTag, "Second peak value: " + peak2);
            //Log.d(logTag, " At index " + find_peaks.indexOf(peak2));

            // Check to if the peak indices are out of order and reverse accordingly
            if (peak2_index > peak1_index) {
                RR = (int)Math.round(peak1_index * ratio);
                HR = (int)Math.round(peak2_index * ratio);
            } else {
                HR = (int)Math.round(peak1_index * ratio);
                RR = (int)Math.round(peak2_index * ratio);
            }

            // Append HR and RR values to message bundle
            b.putInt("HRKey", HR);
            b.putInt("RRKey", RR);

            // Send off message bundle
            msg.setData(b);
            handler.sendMessage(msg);

            // Log values to consoles
            Log.d(logTag, "RR = " + RR);
            Log.d(logTag, "HR = " + HR);

            //End of HR and RR code
        }

    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        context = getApplicationContext();

        // Initialize layout display objects
        tvHR = (TextView) findViewById(R.id.textView3);
        tvRR = (TextView) findViewById(R.id.textView4);
        choose = (TextView) findViewById(R.id.textView5);

        graph = (GraphView) findViewById(R.id.graph);
        file_spinner = (Spinner) findViewById(R.id.file_spinner);
        // Set graphview to manual axis
        graph.getViewport().setYAxisBoundsManual(true);
        graph.getViewport().setXAxisBoundsManual(true);

        // Set graphview viewport
        graph.getViewport().setMaxY(15.0);
        graph.getViewport().setMinY(-15.0);
        graph.getViewport().setMinX(0);
        graph.getViewport().setMaxX(graph_length);
        graph.getViewport().setBackgroundColor(0);

        // Set graphview grid and labels
        graph.getGridLabelRenderer().setVerticalLabelsVisible(false);
        graph.getGridLabelRenderer().setHorizontalLabelsVisible(false);
        graph.getGridLabelRenderer().setGridColor(0);

        // Initialize start button
        btn = (Button) findViewById(R.id.button);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Start new thread on on button click
                Thread t = new Thread(separateThread);
                t.start();
            }
        });

        // Initialize stop button
        stop_btn = (Button) findViewById(R.id.stop_btn);
        stop_btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Start new thread on on button click
//                Thread.currentThread().interrupt();
//
//                Thread t1 = new Thread(stopThread);
//                t1.start();

                Intent i = getIntent();
                if(i != null)
                    i = null;
                i = getBaseContext().getPackageManager()
                        .getLaunchIntentForPackage( getBaseContext().getPackageName() );
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                startActivity(i);
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}